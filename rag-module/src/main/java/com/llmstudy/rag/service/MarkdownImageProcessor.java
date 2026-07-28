package com.llmstudy.rag.service;

import com.llmstudy.rag.dto.MineruContentElement;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Markdown 图片链接处理器。
 *
 * <p>用 CommonMark 解析出真正的图片节点，再按解析到的确切 destination
 * 在原文中做定点替换。不重新渲染整篇 Markdown，因此公式、表格和内嵌 HTML
 * 会原样保留——MinerU 输出里这三类内容都很常见，重新渲染极易破坏它们。</p>
 *
 * <p>只依赖 CommonMark 的核心 AST 能力做「识别」，替换阶段基于精确字面量匹配，
 * 因此不会误伤代码块或公式中形似图片语法的文本。</p>
 */
@Component
public class MarkdownImageProcessor {

    private static final Logger log = LoggerFactory.getLogger(MarkdownImageProcessor.class);

    /** 外部链接与内联数据，保持原样不处理 */
    private static final Set<String> EXTERNAL_PREFIXES = Set.of("http://", "https://", "data:", "//");

    private final Parser parser = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    /**
     * 提取 Markdown 中所有本地图片引用的 destination。
     *
     * @return 去重后的本地图片路径，保持文档中首次出现的顺序
     */
    public Set<String> extractLocalImagePaths(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return Set.of();
        }

        Set<String> destinations = new LinkedHashSet<>();
        Node document = parser.parse(markdown);
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Image image) {
                String destination = image.getDestination();
                if (isLocalImage(destination)) {
                    destinations.add(destination);
                }
                visitChildren(image);
            }
        });
        return destinations;
    }

    /**
     * 将 Markdown 中的本地图片替换为 MinIO 公网 URL，并把 alt 文本换成图片描述。
     *
     * @param markdown     原始 Markdown
     * @param urlMapping   图片相对路径 → MinIO 公网 URL
     * @param descriptions 图片相对路径 → 描述文本（可缺省）
     * @return 替换后的 Markdown
     */
    public String rewriteImages(String markdown,
                                Map<String, String> urlMapping,
                                Map<String, String> descriptions) {
        if (markdown == null || markdown.isBlank() || urlMapping == null || urlMapping.isEmpty()) {
            return markdown;
        }

        List<Integer> lineOffsets = calculateLineOffsets(markdown);
        List<ImageOccurrence> occurrences = findLocalImageOccurrences(markdown, lineOffsets);
        occurrences.sort(Comparator.comparingInt(ImageOccurrence::start).reversed());

        StringBuilder result = new StringBuilder(markdown);
        int replaced = 0;
        for (ImageOccurrence occurrence : occurrences) {
            String minioUrl = urlMapping.get(occurrence.destination());
            if (minioUrl == null) {
                continue;
            }

            String description = descriptions == null ? null : descriptions.get(occurrence.destination());
            String replacement;
            if (description == null || description.isBlank()) {
                // 没有模型描述时只改 URL，原有 alt 与 title 都保留。
                String original = occurrence.source();
                int destinationStart = original.indexOf(occurrence.destination());
                if (destinationStart < 0) {
                    log.warn("无法在图片源码中定位 destination，跳过替换: {}",
                            occurrence.destination());
                    continue;
                }
                replacement = original.substring(0, destinationStart)
                        + minioUrl
                        + original.substring(destinationStart + occurrence.destination().length());
            } else {
                replacement = "![" + escapeAltText(description) + "](" + minioUrl + ")";
            }
            result.replace(occurrence.start(), occurrence.end(), replacement);
            replaced++;
        }

        log.info("Markdown 图片链接替换完成: 替换={}个, 映射={}个", replaced, urlMapping.size());
        return result.toString();
    }

    /**
     * 把 content_list.json 中的图片路径同步改写为 MinIO URL，并回填视觉描述。
     *
     * <p>分块阶段直接消费 content_list，需要能拿到可访问的图片地址和描述文本，
     * 否则还要再调一次视觉模型。</p>
     *
     * <p>入参的 map 以 Markdown 中的原始 destination 为 key，而 content_list 里的
     * img_path 可能写法不同（如带 ./ 前缀），因此先统一归一化再匹配。</p>
     */
    public void rewriteContentList(List<MineruContentElement> contentList,
                                   Map<String, String> urlMapping,
                                   Map<String, String> descriptions) {
        if (contentList == null || contentList.isEmpty()) {
            return;
        }

        Map<String, String> urlByNormalized = normalizeKeys(urlMapping);
        Map<String, String> descByNormalized = normalizeKeys(descriptions);

        int rewritten = 0;
        for (MineruContentElement element : contentList) {
            if (!element.isVisualElement()) {
                continue;
            }
            String imgPath = normalizePath(element.getImgPath());
            if (imgPath == null) {
                continue;
            }

            String description = descByNormalized.get(imgPath);
            if (description != null && !description.isBlank()) {
                element.setVisionDescription(description);
            }

            String minioUrl = urlByNormalized.get(imgPath);
            if (minioUrl != null) {
                element.setImgPath(minioUrl);
                rewritten++;
            }
        }
        log.info("content_list 图片路径改写完成: {}个", rewritten);
    }

    /**
     * 将 map 的 key 归一化，使不同写法的同一路径能够匹配。
     */
    private Map<String, String> normalizeKeys(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = normalizePath(entry.getKey());
            if (key != null) {
                normalized.putIfAbsent(key, entry.getValue());
            }
        }
        return normalized;
    }

    /**
     * 通过 CommonMark 的源码位置信息定位图片节点。这样只改真正的图片语法，
     * 代码块、行内代码以及普通文本中的相似内容不会被全局正则误改。
     */
    private List<ImageOccurrence> findLocalImageOccurrences(String markdown,
                                                             List<Integer> lineOffsets) {
        List<ImageOccurrence> occurrences = new ArrayList<>();
        Node document = parser.parse(markdown);
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Image image) {
                String destination = image.getDestination();
                List<SourceSpan> spans = image.getSourceSpans();
                if (isLocalImage(destination) && spans != null && !spans.isEmpty()) {
                    SourceSpan first = spans.get(0);
                    SourceSpan last = spans.get(spans.size() - 1);
                    int start = toOffset(first.getLineIndex(), first.getColumnIndex(),
                            lineOffsets, markdown.length());
                    int lastStart = toOffset(last.getLineIndex(), last.getColumnIndex(),
                            lineOffsets, markdown.length());
                    int end = Math.min(markdown.length(), lastStart + last.getLength());
                    if (start >= 0 && end >= start) {
                        occurrences.add(new ImageOccurrence(
                                destination, start, end, markdown.substring(start, end)));
                    }
                }
                visitChildren(image);
            }
        });
        return occurrences;
    }

    private List<Integer> calculateLineOffsets(String text) {
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                offsets.add(i + 1);
            }
        }
        return offsets;
    }

    private int toOffset(int lineIndex,
                         int columnIndex,
                         List<Integer> lineOffsets,
                         int textLength) {
        if (lineIndex < 0 || lineIndex >= lineOffsets.size() || columnIndex < 0) {
            return -1;
        }
        int offset = lineOffsets.get(lineIndex) + columnIndex;
        return offset <= textLength ? offset : -1;
    }

    /**
     * 判断是否为需要处理的本地图片引用。
     */
    public boolean isLocalImage(String destination) {
        if (destination == null || destination.isBlank()) {
            return false;
        }
        String lower = destination.toLowerCase(Locale.ROOT);
        for (String prefix : EXTERNAL_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 规范化路径，使 Markdown 中的 destination 与 ZIP 条目名可以对齐。
     * 统一分隔符并去掉 ./ 前缀。
     */
    public String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized.isBlank() ? null : normalized;
    }

    /**
     * alt 文本转义：方括号会截断 Markdown 图片语法，换行会打断节点结构。
     */
    private String escapeAltText(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        return description
                .replace("\\", "\\\\")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replaceAll("\\s*[\\r\\n]+\\s*", " ")
                .trim();
    }

    private record ImageOccurrence(String destination, int start, int end, String source) {
    }
}

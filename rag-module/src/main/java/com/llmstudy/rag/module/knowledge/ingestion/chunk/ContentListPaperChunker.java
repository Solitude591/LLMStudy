package com.llmstudy.rag.module.knowledge.ingestion.chunk;

import com.llmstudy.rag.dto.MineruContentElement;
import com.llmstudy.rag.module.knowledge.ingestion.image.MarkdownImageProcessor;
import com.llmstudy.rag.module.knowledge.model.KnowledgeChunk;
import com.llmstudy.rag.util.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 以 MinerU {@code content_list.json} 为主输入的论文原子分片器。
 *
 * <p>图片/图表/表格强制独立 standalone 并形成切分边界；公式、代码、列表作为受保护原子块装箱；
 * 普通正文超限才生成 parent/child；参考文献章节在分片前丢弃。
 * 标题只更新 {@code header_path}，不单独成片。</p>
 */
public class ContentListPaperChunker {

    private static final Logger log = LoggerFactory.getLogger(ContentListPaperChunker.class);

    /** 作为受保护原子块装箱、不可从语法内部切开的类型。 */
    private static final Set<String> ATOMIC_TYPES = Set.of("equation", "code", "list");

    private final int chunkSize;
    private final TextChunkEmitter emitter;
    private final MarkdownImageProcessor imageProcessor;

    public ContentListPaperChunker(SnowflakeIdGenerator idGenerator,
                                   MarkdownImageProcessor imageProcessor,
                                   int chunkSize,
                                   int chunkOverlap) {
        this.chunkSize = chunkSize;
        this.imageProcessor = imageProcessor;
        this.emitter = new TextChunkEmitter(
                idGenerator, new SemanticTextSplitter(chunkSize, chunkOverlap), chunkSize);
    }

    /**
     * 按 content_list 元素顺序生成分片。
     *
     * @return 可能为空列表；调用方据此决定是否回退 Markdown AST
     */
    public List<KnowledgeChunk> split(List<MineruContentElement> contentList) {
        if (contentList == null || contentList.isEmpty()) {
            return List.of();
        }

        List<KnowledgeChunk> result = new ArrayList<>();
        HeaderPathStack headers = new HeaderPathStack();
        TextBuffer buffer = new TextBuffer();
        Integer referenceLevel = null;

        for (MineruContentElement element : contentList) {
            if (element == null || element.getType() == null) {
                continue;
            }
            String type = element.getType().toLowerCase(Locale.ROOT);

            if (referenceLevel != null) {
                boolean nextSection = element.isHeading()
                        && element.getTextLevel() <= referenceLevel
                        && !ReferenceSectionMatcher.isReferenceHeading(element.getText());
                if (!nextSection) {
                    continue;
                }
                referenceLevel = null;
            }
            if ("ref_text".equals(type)) {
                continue;
            }
            if ("text".equals(type)
                    && ReferenceSectionMatcher.isReferenceHeading(element.getText())) {
                flushText(result, buffer, headers.path());
                referenceLevel = element.isHeading()
                        ? element.getTextLevel() : Integer.MAX_VALUE;
                continue;
            }

            if (element.isHeading()) {
                // 新标题开始前先冲刷正文，避免跨章节粘连。
                flushText(result, buffer, headers.path());
                headers.push(element.getTextLevel(), element.getText());
                continue;
            }
            if (isImageOrChart(type)) {
                flushText(result, buffer, headers.path());
                result.add(emitter.standalone(
                        renderImage(element),
                        headers.path(),
                        pageOf(element),
                        pageOf(element)));
                continue;
            }
            if ("table".equals(type)) {
                flushText(result, buffer, headers.path());
                result.add(emitter.standalone(
                        renderTable(element),
                        headers.path(),
                        pageOf(element),
                        pageOf(element)));
                continue;
            }
            if (ATOMIC_TYPES.contains(type)) {
                // 公式/代码/列表不可从中间切开，记入受保护区间后与邻近正文一起装箱。
                String atomic = firstNonBlank(element.getText(), element.getTableBody());
                if (atomic != null) {
                    buffer.appendAtomic(atomic, pageOf(element));
                }
                continue;
            }
            if ("text".equals(type)) {
                if (element.getText() != null && !element.getText().isBlank()) {
                    buffer.appendText(element.getText(), pageOf(element));
                }
                continue;
            }

            // 未知类型：有文本则降级并入正文，避免 content_list 主路径「非空却丢块」。
            if (element.getText() != null && !element.getText().isBlank()) {
                log.warn("content_list 未知类型按正文保留: type={}, pageIdx={}",
                        element.getType(), element.getPageIdx());
                buffer.appendText(element.getText(), pageOf(element));
            } else {
                log.warn("content_list 未知类型且无文本，已跳过: type={}, pageIdx={}",
                        element.getType(), element.getPageIdx());
            }
        }
        flushText(result, buffer, headers.path());

        log.info("content_list 原子分片完成: 元素={}个, 输出={}片, chunkSize={}",
                contentList.size(), result.size(), chunkSize);
        return result;
    }

    private void flushText(List<KnowledgeChunk> result, TextBuffer buffer, String headerPath) {
        if (buffer.isEmpty()) {
            return;
        }
        result.addAll(emitter.emitText(
                buffer.text(),
                headerPath,
                buffer.pageStart(),
                buffer.pageEnd(),
                buffer.protectedRanges()));
        buffer.clear();
    }

    private static boolean isImageOrChart(String type) {
        return "image".equals(type) || "chart".equals(type);
    }

    private static Integer pageOf(MineruContentElement element) {
        return ChunkMetadataFactory.toUserPage(element.getPageIdx());
    }

    /**
     * 图注 + 脚注 + Markdown 图片节点；alt 优先视觉描述，便于 embedding 捕获语义。
     */
    private String renderImage(MineruContentElement element) {
        StringBuilder text = new StringBuilder();
        String caption = element.firstCaption();
        if (caption != null && !caption.isBlank()) {
            text.append(caption.trim()).append('\n');
        }
        appendLines(text, "脚注: ", element.getImageFootnote());
        appendLines(text, "脚注: ", element.getChartFootnote());
        appendImageNode(text, element, caption);
        return text.toString().strip();
    }

    /**
     * 表题 + table_body（或图片型表格的 Markdown 图）+ 表格脚注。
     *
     * <p>部分 MinerU 表格只有渲染图没有 HTML body，此时回退为图片节点，避免只剩表题。</p>
     */
    private String renderTable(MineruContentElement element) {
        StringBuilder text = new StringBuilder();
        String caption = element.firstCaption();
        if (caption != null && !caption.isBlank()) {
            text.append(caption.trim()).append('\n');
        }

        String body = element.getTableBody();
        if (body != null && !body.isBlank()) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(TableNormalizer.normalizeHtml(body));
        } else if (element.getImgPath() != null && !element.getImgPath().isBlank()) {
            // 图片型表格：保留可检索的视觉描述与图片链接。
            if (!text.isEmpty()) {
                text.append('\n');
            }
            appendImageNode(text, element, caption);
        }

        appendLines(text, "脚注: ", element.getTableFootnote());

        String rendered = text.toString().strip();
        if (rendered.isBlank()) {
            throw new IllegalStateException("表格元素缺少 table_body、图片与表题");
        }
        return rendered;
    }

    private void appendImageNode(StringBuilder text, MineruContentElement element, String caption) {
        String vision = element.getVisionDescription();
        String alt = vision != null && !vision.isBlank()
                ? vision.trim()
                : (caption == null ? "" : caption.trim());
        String url = element.getImgPath() == null ? "" : element.getImgPath().trim();
        text.append("![")
                .append(imageProcessor.escapeAltText(alt))
                .append("](")
                .append(url)
                .append(')');
    }

    private static void appendLines(StringBuilder target, String prefix, List<String> lines) {
        if (lines == null) {
            return;
        }
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                target.append(prefix).append(line.trim()).append('\n');
            }
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.strip();
        }
        if (second != null && !second.isBlank()) {
            return second.strip();
        }
        return null;
    }

    /** 累积跨页正文，并记录公式等受保护区间。 */
    private static final class TextBuffer {
        private final StringBuilder text = new StringBuilder();
        private final List<SemanticTextSplitter.IntRange> protectedRanges = new ArrayList<>();
        private Integer pageStart;
        private Integer pageEnd;

        private void appendText(String value, Integer page) {
            if (value == null || value.isBlank()) {
                return;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(value.strip());
            touchPage(page);
        }

        private void appendAtomic(String value, Integer page) {
            if (value == null || value.isBlank()) {
                return;
            }
            int base = text.length();
            if (!text.isEmpty()) {
                text.append('\n');
                base = text.length();
            }
            text.append(value.strip());
            // 区间相对当前 buffer，flush 时原样交给 SemanticTextSplitter。
            protectedRanges.add(new SemanticTextSplitter.IntRange(base, text.length()));
            touchPage(page);
        }

        private void touchPage(Integer page) {
            if (page == null) {
                return;
            }
            if (pageStart == null || page < pageStart) {
                pageStart = page;
            }
            if (pageEnd == null || page > pageEnd) {
                pageEnd = page;
            }
        }

        private boolean isEmpty() {
            return text.isEmpty();
        }

        private String text() {
            return text.toString();
        }

        private Integer pageStart() {
            return pageStart;
        }

        private Integer pageEnd() {
            return pageEnd;
        }

        private List<SemanticTextSplitter.IntRange> protectedRanges() {
            return List.copyOf(protectedRanges);
        }

        private void clear() {
            text.setLength(0);
            protectedRanges.clear();
            pageStart = null;
            pageEnd = null;
        }
    }
}

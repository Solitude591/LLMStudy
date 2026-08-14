package com.llmstudy.rag.module.knowledge.ingestion.chunk;

import com.llmstudy.rag.module.knowledge.model.KnowledgeChunk;
import com.llmstudy.rag.util.SnowflakeIdGenerator;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Markdown AST 降级分片器（content_list 不可用时使用）。
 *
 * <p>用 CommonMark source span 回切原文，避免重渲染破坏公式/HTML。
 * 无可靠页码，故不写 page_start/page_end。</p>
 */
public class MarkdownAstPaperChunker {

    private static final Logger log = LoggerFactory.getLogger(MarkdownAstPaperChunker.class);
    private static final Pattern TABLE_HTML = Pattern.compile(
            "<\\s*table\\b", Pattern.CASE_INSENSITIVE);

    private final Parser parser;
    private final TextChunkEmitter emitter;
    private final int chunkSize;

    public MarkdownAstPaperChunker(SnowflakeIdGenerator idGenerator,
                                   int chunkSize,
                                   int chunkOverlap) {
        this.chunkSize = chunkSize;
        this.emitter = new TextChunkEmitter(
                idGenerator, new SemanticTextSplitter(chunkSize, chunkOverlap), chunkSize);
        // 注册 GFM 表格扩展，否则 | a | b | 会被当成普通 Paragraph，超限后仍可能被切开。
        List<Extension> extensions = List.of(TablesExtension.create());
        this.parser = Parser.builder()
                .extensions(extensions)
                .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                .build();
    }

    /**
     * 按 Markdown 块结构生成原子分片。
     *
     * @return 可能为空；上游应保证 Markdown 非空时通常至少有一片
     */
    public List<KnowledgeChunk> split(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        // 统一换行，保证 SourceSpan 行列与 lineOffsets 对齐。
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        List<Integer> lineOffsets = lineOffsets(normalized);
        Node document = parser.parse(normalized);

        List<KnowledgeChunk> result = new ArrayList<>();
        HeaderPathStack headers = new HeaderPathStack();
        TextBuffer buffer = new TextBuffer();
        Integer referenceLevel = null;

        for (Node block = document.getFirstChild(); block != null; block = block.getNext()) {
            if (block instanceof Heading heading) {
                String title = headingText(heading);
                if (referenceLevel != null) {
                    boolean nextSection = heading.getLevel() <= referenceLevel
                            && !ReferenceSectionMatcher.isReferenceHeading(title);
                    if (!nextSection) {
                        continue;
                    }
                    referenceLevel = null;
                }
                if (ReferenceSectionMatcher.isReferenceHeading(title)) {
                    flush(result, buffer, headers.path());
                    referenceLevel = heading.getLevel();
                    continue;
                }
                flush(result, buffer, headers.path());
                headers.push(heading.getLevel(), title);
                continue;
            }
            if (referenceLevel != null) {
                continue;
            }
            if (block instanceof ThematicBreak) {
                flush(result, buffer, headers.path());
                continue;
            }
            if (block instanceof TableBlock) {
                // GFM pipe table：整表 standalone，形成与 HTML 表格一致的强制边界。
                String source = sourceOf(block, normalized, lineOffsets);
                if (source != null && !source.isBlank()) {
                    flush(result, buffer, headers.path());
                    result.add(emitter.standalone(
                            TableNormalizer.normalizeGfm(source), headers.path(), null, null));
                }
                continue;
            }
            if (block instanceof HtmlBlock htmlBlock) {
                String source = sourceOf(htmlBlock, normalized, lineOffsets);
                if (source == null || source.isBlank()) {
                    continue;
                }
                // MinerU 表格常以 HTML block 出现；整表 standalone，禁止二次切开。
                if (TABLE_HTML.matcher(source).find()) {
                    flush(result, buffer, headers.path());
                    result.add(emitter.standalone(
                            TableNormalizer.normalizeHtml(source.strip()),
                            headers.path(), null, null));
                } else {
                    buffer.appendAtomic(source.strip());
                }
                continue;
            }
            if (block instanceof FencedCodeBlock || block instanceof IndentedCodeBlock
                    || block instanceof BulletList || block instanceof OrderedList
                    || block instanceof BlockQuote || block instanceof CustomBlock) {
                String source = sourceOf(block, normalized, lineOffsets);
                if (source != null && !source.isBlank()) {
                    buffer.appendAtomic(source.strip());
                }
                continue;
            }
            if (block instanceof Paragraph paragraph) {
                handleParagraph(paragraph, normalized, lineOffsets, result, buffer, headers);
                continue;
            }
            String source = sourceOf(block, normalized, lineOffsets);
            if (source != null && !source.isBlank()) {
                buffer.appendText(source.strip(), protectedRanges(block, normalized, lineOffsets));
            }
        }
        flush(result, buffer, headers.path());

        log.info("Markdown AST 原子分片完成: 输出={}片, chunkSize={}", result.size(), chunkSize);
        return result;
    }

    /**
     * 段落内图片视为强制边界：纯图段 → standalone；混排则先冲刷前文再单独出图片片。
     */
    private void handleParagraph(Paragraph paragraph,
                                 String markdown,
                                 List<Integer> lineOffsets,
                                 List<KnowledgeChunk> result,
                                 TextBuffer buffer,
                                 HeaderPathStack headers) {
        List<Node> children = new ArrayList<>();
        for (Node child = paragraph.getFirstChild(); child != null; child = child.getNext()) {
            children.add(child);
        }
        if (children.size() == 1 && children.getFirst() instanceof Image image) {
            flush(result, buffer, headers.path());
            String source = sourceOf(image, markdown, lineOffsets);
            if (source != null && !source.isBlank()) {
                result.add(emitter.standalone(source.strip(), headers.path(), null, null));
            }
            return;
        }

        StringBuilder pending = new StringBuilder();
        List<SemanticTextSplitter.IntRange> pendingProtected = new ArrayList<>();
        int localOffset = 0;
        for (Node child : children) {
            if (child instanceof Image image) {
                if (!pending.isEmpty()) {
                    buffer.appendText(pending.toString(), pendingProtected);
                    flush(result, buffer, headers.path());
                    pending.setLength(0);
                    pendingProtected = new ArrayList<>();
                    localOffset = 0;
                } else {
                    flush(result, buffer, headers.path());
                }
                String imageSource = sourceOf(image, markdown, lineOffsets);
                if (imageSource != null && !imageSource.isBlank()) {
                    result.add(emitter.standalone(imageSource.strip(), headers.path(), null, null));
                }
                continue;
            }
            String piece = sourceOf(child, markdown, lineOffsets);
            if (piece == null || piece.isEmpty()) {
                continue;
            }
            if (isProtectedInline(child)) {
                int start = localOffset;
                pending.append(piece);
                localOffset += piece.length();
                pendingProtected.add(new SemanticTextSplitter.IntRange(start, localOffset));
            } else {
                pending.append(piece);
                localOffset += piece.length();
            }
        }
        if (!pending.isEmpty()) {
            buffer.appendText(pending.toString(), pendingProtected);
        }
    }

    private void flush(List<KnowledgeChunk> result, TextBuffer buffer, String headerPath) {
        if (buffer.isEmpty()) {
            return;
        }
        // AST 路径无页码：pageStart/pageEnd 传 null，metadata 中省略。
        result.addAll(emitter.emitText(
                buffer.text(), headerPath, null, null, buffer.protectedRanges()));
        buffer.clear();
    }

    /**
     * 行内受保护节点：切点不得落在语法内部。类名启发式兼容 CommonMark 扩展节点。
     */
    private static boolean isProtectedInline(Node node) {
        String name = node.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        return node instanceof Image
                || name.contains("code")
                || name.contains("link")
                || name.contains("html")
                || name.contains("formula")
                || name.contains("math");
    }

    /** 收集块内受保护行内节点相对块起点的区间。 */
    private static List<SemanticTextSplitter.IntRange> protectedRanges(
            Node block, String markdown, List<Integer> lineOffsets) {
        List<SemanticTextSplitter.IntRange> ranges = new ArrayList<>();
        int blockStart = startOffset(block, lineOffsets, markdown.length());
        if (blockStart < 0) {
            return ranges;
        }
        block.accept(new AbstractVisitor() {
            @Override
            protected void visitChildren(Node parent) {
                Node child = parent.getFirstChild();
                while (child != null) {
                    Node next = child.getNext();
                    if (isProtectedInline(child)) {
                        int start = startOffset(child, lineOffsets, markdown.length());
                        int end = endOffset(child, lineOffsets, markdown.length());
                        if (start >= blockStart && end > start) {
                            ranges.add(new SemanticTextSplitter.IntRange(
                                    start - blockStart, end - blockStart));
                        }
                    }
                    child.accept(this);
                    child = next;
                }
            }
        });
        return ranges;
    }

    private static String headingText(Heading heading) {
        StringBuilder text = new StringBuilder();
        heading.accept(new AbstractVisitor() {
            @Override
            public void visit(Text textNode) {
                text.append(textNode.getLiteral());
            }
        });
        return text.toString().trim();
    }

    /**
     * 用 source span 从原文切片，而不是 Renderer 重渲染，以保留 MinerU 公式/HTML。
     */
    private static String sourceOf(Node node, String markdown, List<Integer> lineOffsets) {
        int start = startOffset(node, lineOffsets, markdown.length());
        int end = endOffset(node, lineOffsets, markdown.length());
        if (start < 0 || end < start || end > markdown.length()) {
            return null;
        }
        return markdown.substring(start, end);
    }

    private static int startOffset(Node node, List<Integer> lineOffsets, int length) {
        List<SourceSpan> spans = node.getSourceSpans();
        if (spans == null || spans.isEmpty()) {
            return -1;
        }
        SourceSpan first = spans.getFirst();
        return toOffset(first.getLineIndex(), first.getColumnIndex(), lineOffsets, length);
    }

    private static int endOffset(Node node, List<Integer> lineOffsets, int length) {
        List<SourceSpan> spans = node.getSourceSpans();
        if (spans == null || spans.isEmpty()) {
            return -1;
        }
        SourceSpan last = spans.getLast();
        int start = toOffset(last.getLineIndex(), last.getColumnIndex(), lineOffsets, length);
        if (start < 0) {
            return -1;
        }
        return Math.min(length, start + last.getLength());
    }

    /** 与 {@link com.llmstudy.rag.module.knowledge.ingestion.image.MarkdownImageProcessor} 相同算法。 */
    private static List<Integer> lineOffsets(String text) {
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                offsets.add(i + 1);
            }
        }
        return offsets;
    }

    private static int toOffset(int lineIndex, int columnIndex,
                                List<Integer> lineOffsets, int textLength) {
        if (lineIndex < 0 || lineIndex >= lineOffsets.size() || columnIndex < 0) {
            return -1;
        }
        int offset = lineOffsets.get(lineIndex) + columnIndex;
        return offset <= textLength ? offset : -1;
    }

    private static final class TextBuffer {
        private final StringBuilder text = new StringBuilder();
        private final List<SemanticTextSplitter.IntRange> protectedRanges = new ArrayList<>();

        private void appendText(String value, List<SemanticTextSplitter.IntRange> ranges) {
            if (value == null || value.isBlank()) {
                return;
            }
            int base = text.length();
            if (!text.isEmpty()) {
                text.append('\n');
                base = text.length();
            }
            text.append(value);
            if (ranges != null) {
                for (SemanticTextSplitter.IntRange range : ranges) {
                    // 相对片段坐标平移到 buffer 全局坐标。
                    protectedRanges.add(new SemanticTextSplitter.IntRange(
                            base + range.start(), base + range.end()));
                }
            }
        }

        private void appendAtomic(String value) {
            if (value == null || value.isBlank()) {
                return;
            }
            int base = text.length();
            if (!text.isEmpty()) {
                text.append('\n');
                base = text.length();
            }
            text.append(value);
            protectedRanges.add(new SemanticTextSplitter.IntRange(base, text.length()));
        }

        private boolean isEmpty() {
            return text.isEmpty();
        }

        private String text() {
            return text.toString();
        }

        private List<SemanticTextSplitter.IntRange> protectedRanges() {
            return List.copyOf(protectedRanges);
        }

        private void clear() {
            text.setLength(0);
            protectedRanges.clear();
        }
    }
}

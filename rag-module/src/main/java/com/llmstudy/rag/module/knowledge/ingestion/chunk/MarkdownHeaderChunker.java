package com.llmstudy.rag.module.knowledge.ingestion.chunk;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import com.llmstudy.rag.enums.ChunkType;
import com.llmstudy.rag.module.knowledge.model.KnowledgeChunk;
import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import com.llmstudy.rag.util.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面向 MinerU Markdown 的标题父子分片器。
 *
 * <p>默认把一个 ATX 标题（# 到 ######）及其直属正文作为一个逻辑分片。
 * 逻辑分片未超过 chunkSize 时直接作为 standalone 分片；超过限制时保留完整父分片，
 * 再使用 LangChain4j 递归分片器生成用于向量化的子分片。</p>
 *
 * <p>父分片通过 metadata.skip_embedding=1 标记为不参与向量化；
 * 子分片通过 metadata.parent_chunk_id 指向完整父分片。检索命中子分片后，
 * 可以根据该 ID 回查父分片并提供完整章节上下文。</p>
 */
public class MarkdownHeaderChunker {

    private static final Logger log =
            LoggerFactory.getLogger(MarkdownHeaderChunker.class);

    /** 默认按字符计算的子分片上限。 */
    public static final int DEFAULT_CHUNK_SIZE = 1000;

    /** 默认相邻子分片重叠字符数。 */
    public static final int DEFAULT_CHUNK_OVERLAP = 100;

    public static final String CHUNK_ID = SegmentMetadataKeys.CHUNK_ID;
    public static final String PARENT_CHUNK_ID = SegmentMetadataKeys.PARENT_CHUNK_ID;
    public static final String CHUNK_TYPE = SegmentMetadataKeys.CHUNK_TYPE;
    public static final String CHUNK_INDEX = "chunk_index";
    public static final String CHILD_COUNT = "child_count";
    public static final String SKIP_EMBEDDING = SegmentMetadataKeys.SKIP_EMBEDDING;
    public static final String HEADER_LEVEL = "header_level";
    public static final String HEADER_PATH = SegmentMetadataKeys.HEADER_PATH;
    public static final String HEADER_TEXT = "header_text";
    public static final String CHAR_COUNT = "char_count";

    /** MinerU 使用标准 ATX 标题；最多允许 3 个前导空格，避免误判代码缩进。 */
    private static final Pattern ATX_HEADER_PATTERN = Pattern.compile(
            "^ {0,3}(#{1,6})(?:[\\t ]+(.*)|[\\t ]*)$");

    /** 识别反引号或波浪线围栏代码块，代码块中的 # 不能作为标题。 */
    private static final Pattern FENCE_PATTERN = Pattern.compile(
            "^ {0,3}(`{3,}|~{3,}).*$");

    /** 保留与旧实现一致的标题元数据键，便于后续数据库代码平滑接入。 */
    private static final List<String> HEADER_METADATA_KEYS = List.of(
            "title",
            "subtitle",
            "subsubtitle",
            "subsubsubtitle",
            "subsubsubsubtitle",
            "subsubsubsubsubtitle");

    private final int chunkSize;
    private final int chunkOverlap;

    /** 生成分片唯一 ID（雪花算法），替代随机 UUID 以获得对 MySQL 索引友好的有序 ID。 */
    private final SnowflakeIdGenerator idGenerator;

    /** 使用默认分片大小和重叠量创建分片器。 */
    public MarkdownHeaderChunker(SnowflakeIdGenerator idGenerator) {
        this(idGenerator, DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
    }

    /** 使用指定分片大小，并根据大小自动限制重叠量。 */
    public MarkdownHeaderChunker(SnowflakeIdGenerator idGenerator, int chunkSize) {
        this(idGenerator, chunkSize, Math.min(DEFAULT_CHUNK_OVERLAP, Math.max(0, chunkSize / 10)));
    }

    /** 使用完整分片参数创建分片器并校验参数边界。 */
    public MarkdownHeaderChunker(SnowflakeIdGenerator idGenerator,
                                 int chunkSize, int chunkOverlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0");
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap 必须大于等于 0 且小于 chunkSize");
        }
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.idGenerator = idGenerator;
    }

    /**
     * 按 Markdown 标题语义切分文档，小章节生成 standalone，大章节生成 parent + children。
     *
     * @param markdown     MinerU 生成的 Markdown 原文
     * @param baseMetadata 需复制到每个分片的文档级元数据
     * @return 按文档出现顺序排列的不可变业务分片
     */
    public List<KnowledgeChunk> split(String markdown, Map<String, Object> baseMetadata) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }

        // 解析阶段保留 Markdown 空行、图片、公式、HTML 表格和代码块原文，
        // 只把真正位于代码块外的 ATX 标题作为章节边界。
        List<MarkdownSection> sections = parseSections(markdown);
        Map<String, Object> normalizedMetadata = baseMetadata == null ? Map.of() : baseMetadata;

        List<KnowledgeChunk> result = new ArrayList<>();
        int parentCount = 0;
        int childCount = 0;

        for (MarkdownSection section : sections) {
            List<KnowledgeChunk> sectionSegments = splitSection(section, normalizedMetadata);
            result.addAll(sectionSegments);

            // 超限章节的第一个返回项固定为父分片，其余均为子分片。
            if (!sectionSegments.isEmpty()
                    && ChunkType.PARENT.value().equals(
                    sectionSegments.get(0).metadata().get(CHUNK_TYPE))) {
                parentCount++;
                childCount += sectionSegments.size() - 1;
            }
        }

        log.info("Markdown 父子分片完成: 章节={}个, 输出={}片, 父片={}个, 子片={}个, chunkSize={}, overlap={}",
                sections.size(), result.size(), parentCount, childCount, chunkSize, chunkOverlap);
        return result;
    }

    /**
     * 将 Markdown 解析为标题章节，标题层级只用于生成上下文路径，
     * 每个标题的直属正文仍是一个独立逻辑章节。
     */
    private List<MarkdownSection> parseSections(String markdown) {
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);

        List<MarkdownSection> sections = new ArrayList<>();
        List<MarkdownHeader> headerStack = new ArrayList<>();
        SectionBuilder current = new SectionBuilder(List.of());

        boolean inCodeFence = false;
        char fenceCharacter = 0;
        int fenceLength = 0;

        for (String line : lines) {
            String trimmed = line.stripLeading();

            if (inCodeFence) {
                // 围栏内部所有内容原样写入，包括形似 “## 标题” 的代码。
                current.addBodyLine(line);
                if (isClosingFence(trimmed, fenceCharacter, fenceLength)) {
                    inCodeFence = false;
                }
                continue;
            }

            Matcher fenceMatcher = FENCE_PATTERN.matcher(line);
            if (fenceMatcher.matches()) {
                String fence = fenceMatcher.group(1);
                fenceCharacter = fence.charAt(0);
                fenceLength = fence.length();
                inCodeFence = true;
                current.addBodyLine(line);
                continue;
            }

            MarkdownHeader header = parseHeader(line);
            if (header == null) {
                // 非标题内容全部保留，空行不能删除，否则会破坏段落、列表与 MinerU 公式布局。
                current.addBodyLine(line);
                continue;
            }

            // 遇到新标题先结束上一个章节；没有直属正文的纯容器标题不单独生成低价值分片。
            addSectionIfMeaningful(sections, current);

            // 回退标题栈，确保从 ### 回到 ## 时移除旧的同级和更深层标题。
            while (!headerStack.isEmpty()
                    && headerStack.get(headerStack.size() - 1).level() >= header.level()) {
                headerStack.remove(headerStack.size() - 1);
            }
            headerStack.add(header);

            // 新章节复制当前标题路径，后续栈变化不会影响已经完成的章节。
            current = new SectionBuilder(List.copyOf(headerStack));
        }

        addSectionIfMeaningful(sections, current);
        return sections;
    }

    private MarkdownHeader parseHeader(String line) {
        Matcher matcher = ATX_HEADER_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return null;
        }

        String marker = matcher.group(1);
        String rawText = matcher.group(2) == null ? "" : matcher.group(2).trim();

        // CommonMark 允许 “## 标题 ##” 形式，末尾 # 仅在前面有空格时视为闭合标记。
        String headerText = rawText.replaceFirst("[\\t ]+#+[\\t ]*$", "").trim();
        return new MarkdownHeader(marker.length(), headerText);
    }

    private boolean isClosingFence(String trimmed, char fenceCharacter, int minimumLength) {
        if (trimmed.isEmpty() || trimmed.charAt(0) != fenceCharacter) {
            return false;
        }

        int repeated = 0;
        while (repeated < trimmed.length() && trimmed.charAt(repeated) == fenceCharacter) {
            repeated++;
        }

        // 闭合围栏长度不能短于开启围栏，后面只允许空白。
        return repeated >= minimumLength && trimmed.substring(repeated).isBlank();
    }

    private void addSectionIfMeaningful(List<MarkdownSection> sections, SectionBuilder builder) {
        String body = builder.body();

        // 标题后没有直属正文时，它只是后续子标题的容器，通过 header_path 继承即可。
        if (body.isBlank()) {
            return;
        }
        sections.add(new MarkdownSection(builder.headers(), body));
    }

    /**
     * 小章节直接返回；大章节返回一个完整父片和若干子片。
     */
    private List<KnowledgeChunk> splitSection(MarkdownSection section,
                                              Map<String, Object> baseMetadata) {
        String fullContent = section.fullContent();
        Map<String, Object> sectionMetadata = createSectionMetadata(section, baseMetadata);

        if (fullContent.length() <= chunkSize) {
            String chunkId = nextChunkId();
            Map<String, Object> metadata = new HashMap<>(sectionMetadata);
            metadata.put(CHUNK_ID, chunkId);
            metadata.put(CHUNK_TYPE, ChunkType.STANDALONE.value());
            metadata.put(SKIP_EMBEDDING,
                    ChunkType.STANDALONE.shouldSkipEmbedding() ? 1 : 0);
            metadata.put(CHAR_COUNT, fullContent.length());
            return List.of(toSegment(fullContent, metadata));
        }

        String parentChunkId = nextChunkId();
        List<String> childContents = splitChildContents(section);
        List<KnowledgeChunk> result = new ArrayList<>(childContents.size() + 1);

        // 父片保存完整章节，供命中子片后回查；它本身过大，因此明确跳过向量化。
        Map<String, Object> parentMetadata = new HashMap<>(sectionMetadata);
        parentMetadata.put(CHUNK_ID, parentChunkId);
        parentMetadata.put(CHUNK_TYPE, ChunkType.PARENT.value());
        parentMetadata.put(SKIP_EMBEDDING,
                ChunkType.PARENT.shouldSkipEmbedding() ? 1 : 0);
        parentMetadata.put(CHILD_COUNT, childContents.size());
        parentMetadata.put(CHAR_COUNT, fullContent.length());
        result.add(toSegment(fullContent, parentMetadata));

        for (int index = 0; index < childContents.size(); index++) {
            String childContent = childContents.get(index);
            Map<String, Object> childMetadata = new HashMap<>(sectionMetadata);

            // 每个子片拥有独立 chunkId，并通过 parent_chunk_id 关联完整父章节。
            childMetadata.put(CHUNK_ID, nextChunkId());
            childMetadata.put(PARENT_CHUNK_ID, parentChunkId);
            childMetadata.put(CHUNK_TYPE, ChunkType.CHILD.value());
            childMetadata.put(CHUNK_INDEX, index);
            childMetadata.put(CHILD_COUNT, childContents.size());
            childMetadata.put(SKIP_EMBEDDING,
                    ChunkType.CHILD.shouldSkipEmbedding() ? 1 : 0);
            childMetadata.put(CHAR_COUNT, childContent.length());
            result.add(toSegment(childContent, childMetadata));
        }
        return result;
    }

    /**
     * 对超限章节的正文做二次递归分割。
     *
     * <p>子片正文不再重复拼接标题。标题已经保存在 header_text、header_path
     * 以及 title/subtitle 等 metadata 中，检索和组装上下文时可以直接读取；
     * 省下的字符空间用于保留更多真正的正文内容。</p>
     */
    private List<String> splitChildContents(MarkdownSection section) {
        // 子片只切正文，因此可以使用完整 chunkSize，不再为重复标题预留字符。
        int effectiveOverlap =
                Math.min(chunkOverlap, Math.max(0, chunkSize - 1));

        DocumentSplitter recursiveSplitter =
                DocumentSplitters.recursive(chunkSize, effectiveOverlap);
        List<TextSegment> rawChildren =
                recursiveSplitter.split(Document.from(section.body()));

        List<String> children = new ArrayList<>(rawChildren.size());
        for (TextSegment rawChild : rawChildren) {
            String bodyPart = rawChild.text().strip();
            if (bodyPart.isBlank()) {
                continue;
            }

            // 标题信息由 splitSection 复制到每个子片的 metadata，text 只保留正文。
            children.add(bodyPart);
        }

        // 理论上非空正文一定能产生子片；保留兜底避免第三方分片器异常返回空列表。
        if (children.isEmpty()) {
            children.add(section.body());
        }
        return children;
    }

    private Map<String, Object> createSectionMetadata(MarkdownSection section,
                                                       Map<String, Object> baseMetadata) {
        Map<String, Object> metadata = new HashMap<>(baseMetadata);
        List<MarkdownHeader> headers = section.headers();

        if (headers.isEmpty()) {
            metadata.put(HEADER_LEVEL, 0);
            metadata.put(HEADER_PATH, "");
            metadata.put(HEADER_TEXT, "");
            return metadata;
        }

        MarkdownHeader currentHeader = headers.get(headers.size() - 1);
        metadata.put(HEADER_LEVEL, currentHeader.level());
        metadata.put(HEADER_TEXT, currentHeader.text());
        metadata.put(HEADER_PATH, section.headerPath());

        for (MarkdownHeader header : headers) {
            // 将 1~6 级标题同步写入 title/subtitle 等兼容键。
            metadata.put(HEADER_METADATA_KEYS.get(header.level() - 1), header.text());
        }
        return metadata;
    }

    private KnowledgeChunk toSegment(String content, Map<String, Object> metadata) {
        Object id = metadata.get(CHUNK_ID);
        Object skip = metadata.get(SKIP_EMBEDDING);
        return new KnowledgeChunk(String.valueOf(id), content, metadata,
                skip instanceof Number number && number.intValue() == 1);
    }

    private String nextChunkId() {
        // 数据库 chunk_id 为 BIGINT，雪花 ID 以字符串形式传递，
        // 避免 JSON 序列化时超出 JS 安全整数范围导致前端精度丢失。
        return String.valueOf(idGenerator.nextId());
    }

    private record MarkdownHeader(int level, String text) {

        private String markdown() {
            return "#".repeat(level) + (text.isBlank() ? "" : " " + text);
        }
    }

    private record MarkdownSection(List<MarkdownHeader> headers, String body) {

        private String headerMarkdown() {
            return headers.stream()
                    .map(MarkdownHeader::markdown)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
        }

        private String headerPath() {
            return headers.stream()
                    .map(MarkdownHeader::text)
                    .filter(text -> !text.isBlank())
                    .reduce((left, right) -> left + " > " + right)
                    .orElse("");
        }

        private String fullContent() {
            String headersText = headerMarkdown();
            return headersText.isBlank() ? body : headersText + "\n\n" + body;
        }
    }

    private static class SectionBuilder {

        private final List<MarkdownHeader> headers;
        private final List<String> bodyLines = new ArrayList<>();

        private SectionBuilder(List<MarkdownHeader> headers) {
            this.headers = headers;
        }

        private void addBodyLine(String line) {
            bodyLines.add(line);
        }

        private List<MarkdownHeader> headers() {
            return headers;
        }

        private String body() {
            // 只清理章节首尾空白，正文内部空行保持不变。
            return String.join("\n", bodyLines).strip();
        }
    }
}

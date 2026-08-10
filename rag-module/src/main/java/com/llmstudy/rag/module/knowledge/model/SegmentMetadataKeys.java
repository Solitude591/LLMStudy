package com.llmstudy.rag.module.knowledge.model;

/** 知识入库与在线检索共享的 metadata 键协议。 */
public final class SegmentMetadataKeys {

    /** MySQL 精简 metadata：仅 child 写入。 */
    public static final String PARENT_CHUNK_ID = "parent_chunk_id";

    /** MySQL / ES：标题路径，有标题时写入。 */
    public static final String HEADER_PATH = "header_path";

    /** MySQL / ES：1-based 起始页码。 */
    public static final String PAGE_START = "page_start";

    /** MySQL / ES：1-based 结束页码。 */
    public static final String PAGE_END = "page_end";

    /** ES / 检索候选：由 segment 列或版本记录注入，不再写入 MySQL metadata。 */
    public static final String DOC_ID = "doc_id";
    public static final String VERSION_ID = "version_id";
    public static final String SOURCE_URL = "source_url";

    /**
     * 历史 MySQL metadata 或 Prompt 兜底可能仍出现；新版本不再写入。
     *
     * @deprecated 新分片契约不再持久化该字段
     */
    @Deprecated
    public static final String CHUNK_ID = "chunk_id";

    private SegmentMetadataKeys() {
    }
}

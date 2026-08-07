package com.llmstudy.rag.module.knowledge.model;

/** 知识入库与在线检索共享的 metadata 键协议。 */
public final class SegmentMetadataKeys {

    public static final String CHUNK_ID = "chunk_id";
    public static final String CHUNK_TYPE = "chunk_type";
    public static final String PARENT_CHUNK_ID = "parent_chunk_id";
    public static final String HEADER_PATH = "header_path";
    public static final String SKIP_EMBEDDING = "skip_embedding";
    public static final String DOC_ID = "doc_id";
    public static final String VERSION_ID = "version_id";
    public static final String SOURCE_URL = "source_url";

    private SegmentMetadataKeys() {
    }
}

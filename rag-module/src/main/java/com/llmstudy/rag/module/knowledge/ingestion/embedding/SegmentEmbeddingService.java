package com.llmstudy.rag.module.knowledge.ingestion.embedding;

/** 将已持久化分片向量化并写入向量存储的业务端口。 */
public interface SegmentEmbeddingService {

    /**
     * @param docId 已完成分片的文档 ID
     * @return 本次实际写入向量存储的分片数
     */
    int embedSegments(String docId);
}

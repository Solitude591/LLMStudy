package com.llmstudy.rag.module.rag.model;

/** 与助手消息一起持久化的结构化引用元数据。 */
public record RagReference(int citation, String docId, String chunkId,
                           String headerPath, String sourceUrl,
                           Integer pageStart, Integer pageEnd,
                           double score, Double rerankedScore) {
}

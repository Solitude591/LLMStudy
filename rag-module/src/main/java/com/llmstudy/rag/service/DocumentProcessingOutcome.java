package com.llmstudy.rag.service;

/** 上传文档的首个异步处理阶段结果。 */
public enum DocumentProcessingOutcome {
    RAG_PARSED,
    EXCEL_IMPORTED,
    SKIPPED
}

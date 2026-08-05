package com.llmstudy.rag.module.knowledge.model;

/** 上传后处理分支的结果，用于决定是否继续发布 RAG 入库事件。 */
public enum DocumentProcessingOutcome {
    RAG_PARSED,
    EXCEL_IMPORTED,
    SKIPPED
}

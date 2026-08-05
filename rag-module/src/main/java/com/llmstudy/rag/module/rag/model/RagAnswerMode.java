package com.llmstudy.rag.module.rag.model;

/** RAG 模块内部的回答策略，与 Chat 意图类型解耦。 */
public enum RagAnswerMode {
    PAPER_RETRIEVAL,
    PAPER_SUMMARY,
    PAPER_CONTENT_QA,
    METHOD_OR_CONCEPT,
    EXPERIMENT_OR_RESULT,
    COMPARISON_OR_CRITIQUE,
    ACADEMIC_PAPER_ASSISTANCE,
    GENERIC
}

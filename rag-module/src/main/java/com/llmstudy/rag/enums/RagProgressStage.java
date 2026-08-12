package com.llmstudy.rag.enums;

/**
 * RAG Pipeline 内部进度边界。
 *
 * <p>只覆盖检索编排内部阶段，不含聊天层的意图识别与最终生成。
 * 由 {@code RagChatFlow} 映射为 {@link ChatProgressStage}，避免
 * {@code module.rag} 反向依赖聊天模块。</p>
 */
public enum RagProgressStage {

    /** 即将执行查询改写。 */
    QUESTION_ANALYSIS,

    /** 即将执行混合检索（BM25 + KNN）。 */
    KNOWLEDGE_RETRIEVAL,

    /** 即将执行融合、重排、父片展开与 Prompt 注入。 */
    EVIDENCE_ORGANIZATION
}

package com.llmstudy.rag.module.chat.model;

/**
 * 用户问题的主意图分类。
 *
 * <p>除 GENERAL_CHAT 外，其余业务意图都属于研究论文知识库的覆盖范围。
 * UNKNOWN 只用于识别失败时的保守降级，并默认进入 RAG。</p>
 */
public enum ChatIntent {

    PAPER_RETRIEVAL(true),
    PAPER_SUMMARY(true),
    PAPER_CONTENT_QA(true),
    METHOD_OR_CONCEPT(true),
    EXPERIMENT_OR_RESULT(true),
    COMPARISON_OR_CRITIQUE(true),
    ACADEMIC_PAPER_ASSISTANCE(true),
    GENERAL_CHAT(false),
    UNKNOWN(true);

    private final boolean knowledgeBaseRelated;

    ChatIntent(boolean knowledgeBaseRelated) {
        this.knowledgeBaseRelated = knowledgeBaseRelated;
    }

    /** @return 该意图是否应路由到论文知识库 */
    public boolean isKnowledgeBaseRelated() {
        return knowledgeBaseRelated;
    }
}

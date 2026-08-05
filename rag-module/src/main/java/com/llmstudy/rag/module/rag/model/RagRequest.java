package com.llmstudy.rag.module.rag.model;

/** 在线 RAG Pipeline 的框架无关输入。 */
public record RagRequest(String question, String conversationContext,
                         RagIntentContext intentContext) {

    public RagRequest {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("用户问题不能为空");
        }
        question = question.trim();
        conversationContext = conversationContext == null || conversationContext.isBlank()
                ? "无" : conversationContext.trim();
        intentContext = intentContext == null ? RagIntentContext.generic() : intentContext;
    }

    /** 不指定意图时使用通用回答策略的兼容构造器。 */
    public RagRequest(String question, String conversationContext) {
        this(question, conversationContext, RagIntentContext.generic());
    }
}

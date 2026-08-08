package com.llmstudy.rag.module.rag.model;

import com.llmstudy.rag.auth.model.AccessContext;

/**
 * 在线 RAG Pipeline 的框架无关输入。
 *
 * <p>{@code accessContext} 是检索权限过滤的依据。来自真实 HTTP 请求的 RAG 调用
 * 必须携带该字段，不能在后台线程重新读取当前登录用户。</p>
 */
public record RagRequest(String question, String conversationContext,
                         RagIntentContext intentContext,
                         AccessContext accessContext) {

    public RagRequest {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("用户问题不能为空");
        }
        question = question.trim();
        // Prompt 模板需要稳定的上下文占位符，空历史统一写为“无”。
        conversationContext = conversationContext == null || conversationContext.isBlank()
                ? "无" : conversationContext.trim();
        intentContext = intentContext == null ? RagIntentContext.generic() : intentContext;
    }

    /**
     * 不指定意图和身份时使用通用回答策略的兼容构造器，主要服务于单元测试。
     */
    public RagRequest(String question, String conversationContext) {
        this(question, conversationContext, RagIntentContext.generic(), null);
    }

    public RagRequest(String question, String conversationContext,
                      RagIntentContext intentContext) {
        this(question, conversationContext, intentContext, null);
    }
}

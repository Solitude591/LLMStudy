package com.llmstudy.rag.module.llm.model;

/**
 * 一次 LLM 请求的角色化提示词。
 *
 * <p>System 消息只承载稳定角色和行为约束，User 消息承载会话历史、
 * 检索证据和当前问题等运行时数据。</p>
 */
public record LlmPrompt(String systemMessage, String userMessage) {

    public LlmPrompt {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("LLM User 消息不能为空");
        }
    }

    /** 构造不需要 System 消息的请求，例如普通聊天。 */
    public static LlmPrompt userOnly(String userMessage) {
        return new LlmPrompt(null, userMessage);
    }

    /** @return 是否存在可发送的 System 消息 */
    public boolean hasSystemMessage() {
        return systemMessage != null && !systemMessage.isBlank();
    }
}

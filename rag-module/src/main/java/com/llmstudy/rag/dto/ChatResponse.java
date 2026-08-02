package com.llmstudy.rag.dto;

/**
 * 非流式聊天响应。
 *
 * @param conversationId    会话 ID；新会话时前端需保存该值，后续请求再传回
 * @param conversationTitle 会话创建时写入数据库的临时标题
 * @param userMessageId     本次用户消息的业务 ID
 * @param assistantMessageId 本次助手回复消息的业务 ID
 * @param content           模型返回的完整文本
 * @param tokenCount        模型返回的 Token 总数；供应商未返回时为 null
 * @param modelName         实际响应该请求的模型名称；未返回时为 null
 */
public record ChatResponse(
        String conversationId,
        String conversationTitle,
        String userMessageId,
        String assistantMessageId,
        String content,
        Integer tokenCount,
        String modelName) {
}

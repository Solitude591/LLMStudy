package com.llmstudy.rag.dto;

/**
 * 聊天请求。
 *
 * @param conversationId 会话 ID；为空时由服务端创建新会话并生成 UUID
 * @param userId         用户 ID；登录功能尚未接入时允许为空，服务端会使用 default
 * @param query          本次发送给模型的用户问题
 */
public record ChatRequest(
        String conversationId,
        String userId,
        String query) {
}

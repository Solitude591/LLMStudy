package com.llmstudy.rag.dto;

/**
 * 聊天请求。
 *
 * <p>请求体不包含 userId，用户身份只从 Authorization Token 对应的
 * Sa-Token 登录态中取得，防止客户端伪造会话所有者。</p>
 *
 * @param conversationId 会话 ID；为空时由服务端创建新会话并生成 UUID
 * @param query          本次发送给模型的用户问题
 */
public record ChatRequest(
        String conversationId,
        String query) {
}

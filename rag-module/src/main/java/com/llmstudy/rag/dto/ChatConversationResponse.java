package com.llmstudy.rag.dto;

import com.llmstudy.rag.entity.ChatConversation;
import com.llmstudy.rag.enums.ConversationStatus;

import java.time.LocalDateTime;

/**
 * 会话摘要响应，用于前端同步异步更新后的会话标题。
 *
 * @param conversationId 会话业务 ID
 * @param title          当前数据库中的会话标题
 * @param status         会话状态
 * @param updatedAt      最近更新时间
 */
public record ChatConversationResponse(
        String conversationId,
        String title,
        ConversationStatus status,
        LocalDateTime updatedAt) {

    /**
     * 将数据库实体转换为对外 DTO，避免直接暴露自增主键和用户标识。
     */
    public static ChatConversationResponse from(ChatConversation conversation) {
        return new ChatConversationResponse(
                conversation.getConversationId(),
                conversation.getTitle(),
                conversation.getStatus(),
                conversation.getUpdatedAt());
    }
}

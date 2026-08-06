package com.llmstudy.rag.dto;

import com.llmstudy.rag.entity.ChatMessage;
import com.llmstudy.rag.enums.MessageType;

import java.time.LocalDateTime;

/** 聊天历史消息响应，仅暴露页面恢复会话所需的字段。 */
public record ChatMessageResponse(
        String messageId,
        MessageType type,
        String content,
        Integer tokenCount,
        String modelName,
        String ragReferences,
        LocalDateTime createdAt) {

    public static ChatMessageResponse from(ChatMessage message) {
        return new ChatMessageResponse(
                message.getMessageId(),
                message.getMessageType(),
                message.getContent(),
                message.getTokenCount(),
                message.getModelName(),
                message.getRagReferences(),
                message.getCreatedAt());
    }
}

package com.llmstudy.rag.module.chat.model;

/** 与 HTTP DTO 解耦的内部聊天命令。 */
public record ChatCommand(String conversationId, String userId, String query) {

    public ChatCommand {
        conversationId = conversationId == null || conversationId.isBlank()
                ? null : conversationId.trim();
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        userId = userId.trim();
        query = query.trim();
    }
}

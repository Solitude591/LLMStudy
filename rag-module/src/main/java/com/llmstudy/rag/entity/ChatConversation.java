package com.llmstudy.rag.entity;

import com.llmstudy.rag.enums.ConversationStatus;

import java.time.LocalDateTime;

/**
 * 聊天会话实体，对应 chat_conversation 表。
 */
public class ChatConversation {

    /** MySQL 自增主键。 */
    private Long id;

    /** 会话业务唯一标识。 */
    private String conversationId;

    /** 会话所属用户标识。 */
    private String userId;

    /** 会话标题。 */
    private String title;

    /** 会话状态，MyBatis 使用枚举名称持久化为大写字符串。 */
    private ConversationStatus status;

    /** 消息版本；每保存一条消息递增，用于校验历史缓存新鲜度。 */
    private Long messageVersion;

    /** 创建时间，由数据库写入。 */
    private LocalDateTime createdAt;

    /** 最近更新时间，由数据库维护。 */
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public ConversationStatus getStatus() {
        return status;
    }

    public void setStatus(ConversationStatus status) {
        this.status = status;
    }

    public ConversationStatus getConversationStatus() {
        return status;
    }

    public void setConversationStatus(ConversationStatus conversationStatus) {
        if (conversationStatus == null) {
            throw new IllegalArgumentException("会话状态不能为空");
        }
        this.status = conversationStatus;
    }

    public Long getMessageVersion() {
        return messageVersion;
    }

    public void setMessageVersion(Long messageVersion) {
        this.messageVersion = messageVersion;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "ChatConversation{" +
                "id=" + id +
                ", conversationId='" + conversationId + '\'' +
                ", userId='" + userId + '\'' +
                ", title='" + title + '\'' +
                ", status='" + status + '\'' +
                ", messageVersion=" + messageVersion +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}

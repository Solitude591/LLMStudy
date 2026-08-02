package com.llmstudy.rag.entity;

import com.llmstudy.rag.enums.MessageType;

import java.time.LocalDateTime;

/**
 * 聊天消息实体，对应 chat_message 表。
 */
public class ChatMessage {

    /** MySQL 自增主键。 */
    private Long id;

    /** 消息业务唯一标识。 */
    private String messageId;

    /** 所属会话标识。 */
    private String conversationId;

    /** 数据库中的消息类型字符串，业务代码通过 MessageType 访问。 */
    private String type;

    /** 消息原始内容。 */
    private String content;

    /** 改写后的内容，主要用于保存用户问题改写结果。 */
    private String transformContent;

    /** Token 数量；未统计时为 null。 */
    private Integer tokenCount;

    /** 生成或处理该消息的模型名称。 */
    private String modelName;

    /** RAG 引用内容的 JSON 字符串。 */
    private String ragReferences;

    /** 扩展元数据的 JSON 字符串。 */
    private String metadata;

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

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public MessageType getMessageType() {
        return MessageType.fromValue(type);
    }

    public void setMessageType(MessageType messageType) {
        if (messageType == null) {
            throw new IllegalArgumentException("消息类型不能为空");
        }
        this.type = messageType.value();
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getTransformContent() {
        return transformContent;
    }

    public void setTransformContent(String transformContent) {
        this.transformContent = transformContent;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getRagReferences() {
        return ragReferences;
    }

    public void setRagReferences(String ragReferences) {
        this.ragReferences = ragReferences;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
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
        return "ChatMessage{" +
                "id=" + id +
                ", messageId='" + messageId + '\'' +
                ", conversationId='" + conversationId + '\'' +
                ", type='" + type + '\'' +
                ", tokenCount=" + tokenCount +
                ", modelName='" + modelName + '\'' +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}

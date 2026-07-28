package com.llmstudy.rag.entity;

import java.time.LocalDateTime;

/**
 * 知识库文档分片片段实体 —— RAG 检索的基本单元
 */
public class KnowledgeSegment {

    private Long id;
    private String chunkId;
    private String text;
    private String docId;
    private Integer chunkOrder;
    private String embeddingId;
    private String status;
    private String metadata;
    private Boolean skipEmbedding;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getChunkId() {
        return chunkId;
    }

    public void setChunkId(String chunkId) {
        this.chunkId = chunkId;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public Integer getChunkOrder() {
        return chunkOrder;
    }

    public void setChunkOrder(Integer chunkOrder) {
        this.chunkOrder = chunkOrder;
    }

    public String getEmbeddingId() {
        return embeddingId;
    }

    public void setEmbeddingId(String embeddingId) {
        this.embeddingId = embeddingId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public Boolean getSkipEmbedding() {
        return skipEmbedding;
    }

    public void setSkipEmbedding(Boolean skipEmbedding) {
        this.skipEmbedding = skipEmbedding;
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
        return "KnowledgeSegment{" +
                "id=" + id +
                ", chunkId='" + chunkId + '\'' +
                ", docId='" + docId + '\'' +
                ", chunkOrder=" + chunkOrder +
                ", embeddingId='" + embeddingId + '\'' +
                ", status='" + status + '\'' +
                ", metadata='" + metadata + '\'' +
                ", skipEmbedding=" + skipEmbedding +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}

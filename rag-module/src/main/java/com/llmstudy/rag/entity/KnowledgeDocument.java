package com.llmstudy.rag.entity;

import java.time.LocalDateTime;

/**
 * 知识库逻辑文档实体。
 *
 * <p>只保存稳定的文档身份信息；文件、解析产物和处理状态等
 * 内容快照信息保存在 {@link KnowledgeDocumentVersion}。</p>
 */
public class KnowledgeDocument {

    /** MySQL 自增主键，仅用于数据库内部。 */
    private Long id;

    /** 逻辑文档业务 ID，整个版本生命周期内保持不变。 */
    private String docId;

    /** 逻辑文档标题。 */
    private String docTitle;

    /** 预留的可访问主体标识；当前不参与权限判定。 */
    private String accessibleBy;

    /** 当前对外生效的物理版本 ID；尚未首次发布时为 null。 */
    private String currentVersionId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public String getDocTitle() {
        return docTitle;
    }

    public void setDocTitle(String docTitle) {
        this.docTitle = docTitle;
    }

    public String getAccessibleBy() {
        return accessibleBy;
    }

    public void setAccessibleBy(String accessibleBy) {
        this.accessibleBy = accessibleBy;
    }

    public String getCurrentVersionId() {
        return currentVersionId;
    }

    public void setCurrentVersionId(String currentVersionId) {
        this.currentVersionId = currentVersionId;
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
        return "KnowledgeDocument{" +
                "id=" + id +
                ", docId='" + docId + '\'' +
                ", docTitle='" + docTitle + '\'' +
                ", accessibleBy='" + accessibleBy + '\'' +
                ", currentVersionId='" + currentVersionId + '\'' +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}

package com.llmstudy.rag.entity;

import com.llmstudy.rag.auth.model.DocumentVisibility;

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

    /** 文档所有者用户 ID，由上传入口的当前身份生成，不接受前端指定。 */
    private String ownerUserId;

    /** 可见范围数据库值：PRIVATE、ORGANIZATION 或 PUBLIC。 */
    private String visibility;

    /** 组织可见文档所属组织；其他可见范围为 null。 */
    private String organizationId;

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

    public String getOwnerUserId() {
        return ownerUserId;
    }
    public void setOwnerUserId(String ownerUserId) {
        this.ownerUserId = ownerUserId;
    }
    public String getVisibility() {
        return visibility;
    }
    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    /** @return 将数据库字符串转换为统一的强类型可见范围 */
    public DocumentVisibility getDocumentVisibility() {
        return DocumentVisibility.from(visibility);
    }

    /** 使用枚举写入规范化的大写数据库值。 */
    public void setDocumentVisibility(DocumentVisibility visibility) {
        this.visibility = visibility.name();
    }
    public String getOrganizationId() {
        return organizationId;
    }
    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
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
                ", ownerUserId='" + ownerUserId + '\'' +
                ", visibility='" + visibility + '\'' +
                ", organizationId='" + organizationId + '\'' +
                ", currentVersionId='" + currentVersionId + '\'' +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}

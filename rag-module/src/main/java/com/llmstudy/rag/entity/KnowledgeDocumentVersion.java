package com.llmstudy.rag.entity;

import com.llmstudy.rag.enums.DocumentReleaseStatus;
import com.llmstudy.rag.enums.DocumentStatus;

import java.time.LocalDateTime;

/** 知识库文档的不可变物理版本快照。 */
public class KnowledgeDocumentVersion {

    private Long id;
    private String versionId;
    private String docId;
    private Integer versionNo;
    private String fileType;
    private String contentHash;
    private String uploadedBy;
    private String docUrl;
    private String rawObjectKey;
    private String convertedDocUrl;
    private String processingStatus;
    private String releaseStatus;
    private String errorMessage;
    private Integer retryCount;
    private String changeSummary;
    private LocalDateTime readyAt;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(String uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public String getDocUrl() {
        return docUrl;
    }

    public void setDocUrl(String docUrl) {
        this.docUrl = docUrl;
    }

    public String getRawObjectKey() {
        return rawObjectKey;
    }

    public void setRawObjectKey(String rawObjectKey) {
        this.rawObjectKey = rawObjectKey;
    }

    public String getConvertedDocUrl() {
        return convertedDocUrl;
    }

    public void setConvertedDocUrl(String convertedDocUrl) {
        this.convertedDocUrl = convertedDocUrl;
    }

    public String getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(String processingStatus) {
        this.processingStatus = processingStatus;
    }

    public DocumentStatus getDocumentStatus() {
        return DocumentStatus.fromValue(processingStatus);
    }

    public void setDocumentStatus(DocumentStatus documentStatus) {
        if (documentStatus == null) {
            throw new IllegalArgumentException("文档处理状态不能为空");
        }
        this.processingStatus = documentStatus.value();
    }

    public String getReleaseStatus() {
        return releaseStatus;
    }

    public void setReleaseStatus(String releaseStatus) {
        this.releaseStatus = releaseStatus;
    }

    public DocumentReleaseStatus getDocumentReleaseStatus() {
        return DocumentReleaseStatus.fromValue(releaseStatus);
    }

    public void setDocumentReleaseStatus(DocumentReleaseStatus releaseStatus) {
        if (releaseStatus == null) {
            throw new IllegalArgumentException("文档版本发布状态不能为空");
        }
        this.releaseStatus = releaseStatus.value();
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public String getChangeSummary() {
        return changeSummary;
    }

    public void setChangeSummary(String changeSummary) {
        this.changeSummary = changeSummary;
    }

    public LocalDateTime getReadyAt() {
        return readyAt;
    }

    public void setReadyAt(LocalDateTime readyAt) {
        this.readyAt = readyAt;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
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
        return "KnowledgeDocumentVersion{" +
                "id=" + id +
                ", versionId='" + versionId + '\'' +
                ", docId='" + docId + '\'' +
                ", versionNo=" + versionNo +
                ", fileType='" + fileType + '\'' +
                ", uploadedBy='" + uploadedBy + '\'' +
                ", processingStatus='" + processingStatus + '\'' +
                ", releaseStatus='" + releaseStatus + '\'' +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}

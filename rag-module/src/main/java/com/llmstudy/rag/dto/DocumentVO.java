package com.llmstudy.rag.dto;

import java.time.LocalDateTime;

/**
 * 文档上传/查询的响应对象
 */
public class DocumentVO {

    private String docId;
    private String docTitle;
    private String originalName;
    private String fileType;
    private Long fileSize;
    private String fileMd5;
    private String targetTableName;
    private String uploader;
    private String docUrl;
    private String docStatus;
    private String convertedDocUrl;
    private String visibility;
    private LocalDateTime createdAt;
    private boolean duplicate;

    /** 本次上传/当前版本的物理版本 ID。 */
    private String versionId;

    /** 本次上传/当前版本的展示版本号（从 1 开始）。 */
    private Integer versionNo;

    /** 版本内容哈希（SHA-256）。 */
    private String contentHash;

    /** 版本处理状态：UPLOADED → … → VECTOR_STORED。 */
    private String processingStatus;

    /** 版本发布状态：PREPARING / READY / PUBLISHING / PUBLISHED / ARCHIVED。 */
    private String releaseStatus;

    public DocumentVO() {
    }

    private DocumentVO(Builder builder) {
        this.docId = builder.docId;
        this.docTitle = builder.docTitle;
        this.originalName = builder.originalName;
        this.fileType = builder.fileType;
        this.fileSize = builder.fileSize;
        this.fileMd5 = builder.fileMd5;
        this.targetTableName = builder.targetTableName;
        this.uploader = builder.uploader;
        this.docUrl = builder.docUrl;
        this.docStatus = builder.docStatus;
        this.convertedDocUrl = builder.convertedDocUrl;
        this.visibility = builder.visibility;
        this.createdAt = builder.createdAt;
        this.duplicate = builder.duplicate;
        this.versionId = builder.versionId;
        this.versionNo = builder.versionNo;
        this.contentHash = builder.contentHash;
        this.processingStatus = builder.processingStatus;
        this.releaseStatus = builder.releaseStatus;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ========== Getters & Setters ==========

    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }

    public String getDocTitle() { return docTitle; }
    public void setDocTitle(String docTitle) { this.docTitle = docTitle; }

    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public String getFileMd5() { return fileMd5; }
    public void setFileMd5(String fileMd5) { this.fileMd5 = fileMd5; }

    public String getTargetTableName() { return targetTableName; }
    public void setTargetTableName(String targetTableName) { this.targetTableName = targetTableName; }

    public String getUploader() { return uploader; }
    public void setUploader(String uploader) { this.uploader = uploader; }

    public String getDocUrl() { return docUrl; }
    public void setDocUrl(String docUrl) { this.docUrl = docUrl; }

    public String getDocStatus() { return docStatus; }
    public void setDocStatus(String docStatus) { this.docStatus = docStatus; }

    public String getConvertedDocUrl() { return convertedDocUrl; }
    public void setConvertedDocUrl(String convertedDocUrl) { this.convertedDocUrl = convertedDocUrl; }

    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public boolean isDuplicate() { return duplicate; }
    public void setDuplicate(boolean duplicate) { this.duplicate = duplicate; }

    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }

    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public String getProcessingStatus() { return processingStatus; }
    public void setProcessingStatus(String processingStatus) { this.processingStatus = processingStatus; }

    public String getReleaseStatus() { return releaseStatus; }
    public void setReleaseStatus(String releaseStatus) { this.releaseStatus = releaseStatus; }

    // ========== Builder ==========

    public static class Builder {
        private String docId;
        private String docTitle;
        private String originalName;
        private String fileType;
        private Long fileSize;
        private String fileMd5;
        private String targetTableName;
        private String uploader;
        private String docUrl;
        private String docStatus;
        private String convertedDocUrl;
        private String visibility;
        private LocalDateTime createdAt;
        private boolean duplicate;
        private String versionId;
        private Integer versionNo;
        private String contentHash;
        private String processingStatus;
        private String releaseStatus;

        public Builder docId(String docId) { this.docId = docId; return this; }
        public Builder docTitle(String docTitle) { this.docTitle = docTitle; return this; }
        public Builder originalName(String originalName) { this.originalName = originalName; return this; }
        public Builder fileType(String fileType) { this.fileType = fileType; return this; }
        public Builder fileSize(Long fileSize) { this.fileSize = fileSize; return this; }
        public Builder fileMd5(String fileMd5) { this.fileMd5 = fileMd5; return this; }
        public Builder targetTableName(String targetTableName) { this.targetTableName = targetTableName; return this; }
        public Builder uploader(String uploader) { this.uploader = uploader; return this; }
        public Builder docUrl(String docUrl) { this.docUrl = docUrl; return this; }
        public Builder docStatus(String docStatus) { this.docStatus = docStatus; return this; }
        public Builder convertedDocUrl(String convertedDocUrl) { this.convertedDocUrl = convertedDocUrl; return this; }
        public Builder visibility(String visibility) { this.visibility = visibility; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder duplicate(boolean duplicate) { this.duplicate = duplicate; return this; }
        public Builder versionId(String versionId) { this.versionId = versionId; return this; }
        public Builder versionNo(Integer versionNo) { this.versionNo = versionNo; return this; }
        public Builder contentHash(String contentHash) { this.contentHash = contentHash; return this; }
        public Builder processingStatus(String processingStatus) { this.processingStatus = processingStatus; return this; }
        public Builder releaseStatus(String releaseStatus) { this.releaseStatus = releaseStatus; return this; }

        public DocumentVO build() {
            return new DocumentVO(this);
        }
    }
}

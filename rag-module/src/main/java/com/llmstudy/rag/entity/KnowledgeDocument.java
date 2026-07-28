package com.llmstudy.rag.entity;

import java.time.LocalDateTime;

/**
 * 知识库文档元数据实体
 */
public class KnowledgeDocument {

    private Long id;
    private String docId;
    private String docTitle;
    private String originalName;
    private String fileType;
    private Long fileSize;
    private String fileMd5;
    private String uploader;
    private String docUrl;
    private String docStatus;
    private String convertedDocUrl;
    private String visibility;
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

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFileMd5() {
        return fileMd5;
    }

    public void setFileMd5(String fileMd5) {
        this.fileMd5 = fileMd5;
    }

    public String getUploader() {
        return uploader;
    }

    public void setUploader(String uploader) {
        this.uploader = uploader;
    }

    public String getDocUrl() {
        return docUrl;
    }

    public void setDocUrl(String docUrl) {
        this.docUrl = docUrl;
    }

    public String getDocStatus() {
        return docStatus;
    }

    public void setDocStatus(String docStatus) {
        this.docStatus = docStatus;
    }

    public String getConvertedDocUrl() {
        return convertedDocUrl;
    }

    public void setConvertedDocUrl(String convertedDocUrl) {
        this.convertedDocUrl = convertedDocUrl;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
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
                ", originalName='" + originalName + '\'' +
                ", fileType='" + fileType + '\'' +
                ", fileSize=" + fileSize +
                ", fileMd5='" + fileMd5 + '\'' +
                ", uploader='" + uploader + '\'' +
                ", docUrl='" + docUrl + '\'' +
                ", docStatus='" + docStatus + '\'' +
                ", convertedDocUrl='" + convertedDocUrl + '\'' +
                ", visibility='" + visibility + '\'' +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}

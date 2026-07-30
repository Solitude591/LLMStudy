package com.llmstudy.rag.entity;

import com.llmstudy.rag.enums.DocumentStatus;

import java.time.LocalDateTime;

/**
 * 知识库文档元数据实体
 */
public class KnowledgeDocument {

    /** MySQL 自增主键，仅用于数据库内部关联，不作为对外暴露的文档标识。 */
    private Long id;

    /** 业务文档 ID，上传时生成的无横线 UUID，贯穿 MinIO 路径、解析和分片流程。 */
    private String docId;

    /** 展示给用户的文档标题；上传时未指定则使用去掉扩展名后的原文件名。 */
    private String docTitle;

    /** 用户上传时的原始文件名，包含文件扩展名。 */
    private String originalName;

    /** 规范化为小写的文件扩展名，用于校验文件类型以及选择对应的解析策略。 */
    private String fileType;

    /** 原始文件大小，单位为字节。 */
    private Long fileSize;

    /** 原始文件内容的 MD5，用于在同一上传者范围内判断文件是否重复。 */
    private String fileMd5;

    /** 上传者标识；当前由请求参数传入，接入登录体系后应取当前登录用户。 */
    private String uploader;

    /** 原始文件在 MinIO 中的可访问地址，也是 PDF 提交给 MinerU 时使用的地址。 */
    private String docUrl;

    /** 数据库中的文档状态字符串；业务代码通过 DocumentStatus 访问。 */
    private String docStatus;

    /** 解析完成后的 Markdown 文件地址；尚未完成解析时为空。 */
    private String convertedDocUrl;

    /** 文档处理失败时的错误信息；成功时为 null。用于事件驱动流程中记录各阶段失败原因。 */
    private String errorMessage;

    /** 文档可见范围，例如 private、internal、public。 */
    private String visibility;

    /** 文档元数据记录的创建时间，由数据库写入。 */
    private LocalDateTime createdAt;

    /** 文档元数据最近一次更新时间，由数据库在记录变更时维护。 */
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

    /**
     * 将数据库字符串转换为强类型枚举，避免业务层直接比较魔法字符串。
     */
    public DocumentStatus getDocumentStatus() {
        return DocumentStatus.fromValue(docStatus);
    }

    /**
     * 业务层使用枚举设置状态，落库前仍转换为兼容现有表结构的小写字符串。
     */
    public void setDocumentStatus(DocumentStatus documentStatus) {
        if (documentStatus == null) {
            throw new IllegalArgumentException("文档状态不能为空");
        }
        this.docStatus = documentStatus.value();
    }

    public String getConvertedDocUrl() {
        return convertedDocUrl;
    }

    public void setConvertedDocUrl(String convertedDocUrl) {
        this.convertedDocUrl = convertedDocUrl;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
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
                ", errorMessage='" + errorMessage + '\'' +
                ", visibility='" + visibility + '\'' +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}

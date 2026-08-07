package com.llmstudy.rag.entity;

import com.llmstudy.rag.enums.SegmentStatus;

import java.time.LocalDateTime;

/**
 * 知识库文档分片片段实体 —— RAG 检索的基本单元
 */
public class KnowledgeSegment {

    /** MySQL 自增主键，仅作为数据库内部记录标识。 */
    private Long id;

    /** 分片的业务唯一 ID，后续可用于关联向量数据库中的对应向量。 */
    private String chunkId;

    /** 分片的实际文本内容，是生成 embedding 和执行语义检索的输入。 */
    private String text;

    /** 所属文档的业务 ID，对应 knowledge_document.doc_id。 */
    private String docId;

    /** 所属物理版本 ID，与 docId 共同唯一确定片段的文档快照。 */
    private String versionId;

    /** 分片在原文中的顺序，从而在召回后仍可恢复相邻片段和原始上下文。 */
    private Integer chunkOrder;

    /** 向量写入向量数据库后返回或生成的标识，用于关联、更新和删除 embedding。 */
    private String embeddingId;

    /** 分片处理状态，用于记录待向量化、已完成或处理失败等阶段。 */
    private String status;

    /** 分片附加元数据的 JSON 字符串，可保存标题层级、页码、来源路径等信息。 */
    private String metadata;

    /** 是否跳过向量化；适用于仅保留结构但不参与语义检索的特殊片段。 */
    private Boolean skipEmbedding;

    /** 分片记录的创建时间，由数据库写入。 */
    private LocalDateTime createdAt;

    /** 分片记录最近一次更新时间，由数据库在记录变更时维护。 */
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

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = versionId;
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

    /**
     * 将数据库字符串转换为强类型枚举，业务层无需直接使用状态字符串。
     */
    public SegmentStatus getSegmentStatus() {
        return SegmentStatus.fromValue(status);
    }

    /**
     * 使用枚举设置分片状态，数据库保存统一的大写状态值。
     */
    public void setSegmentStatus(SegmentStatus segmentStatus) {
        if (segmentStatus == null) {
            throw new IllegalArgumentException("分片状态不能为空");
        }
        this.status = segmentStatus.value();
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
                ", versionId='" + versionId + '\'' +
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

package com.llmstudy.rag.dto;

/**
 * 文档分片接口返回结果。
 */
public class DocumentSplitResult {

    /** 本次操作对应的物理版本 ID。 */
    private final String versionId;

    /** 当前版本在 knowledge_segment 表中的分片数量。 */
    private final int segmentCount;

    /** 分片完成后的版本处理状态。 */
    private final String processingStatus;

    /** true 表示该版本此前已经分片，本次没有重复写入。 */
    private final boolean alreadySplit;

    public DocumentSplitResult(String versionId,
                               int segmentCount,
                               String processingStatus,
                               boolean alreadySplit) {
        this.versionId = versionId;
        this.segmentCount = segmentCount;
        this.processingStatus = processingStatus;
        this.alreadySplit = alreadySplit;
    }

    public String getVersionId() {
        return versionId;
    }

    public int getSegmentCount() {
        return segmentCount;
    }

    public String getProcessingStatus() {
        return processingStatus;
    }

    public boolean isAlreadySplit() {
        return alreadySplit;
    }
}

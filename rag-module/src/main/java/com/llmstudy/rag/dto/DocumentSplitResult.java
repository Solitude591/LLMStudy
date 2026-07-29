package com.llmstudy.rag.dto;

/**
 * 文档分片接口返回结果。
 */
public class DocumentSplitResult {

    /** 本次操作对应的文档 ID。 */
    private final String docId;

    /** 当前文档在 knowledge_segment 表中的分片数量。 */
    private final int segmentCount;

    /** 分片完成后的文档状态。 */
    private final String documentStatus;

    /** true 表示文档此前已经分片，本次没有重复写入。 */
    private final boolean alreadySplit;

    public DocumentSplitResult(String docId,
                               int segmentCount,
                               String documentStatus,
                               boolean alreadySplit) {
        this.docId = docId;
        this.segmentCount = segmentCount;
        this.documentStatus = documentStatus;
        this.alreadySplit = alreadySplit;
    }

    public String getDocId() {
        return docId;
    }

    public int getSegmentCount() {
        return segmentCount;
    }

    public String getDocumentStatus() {
        return documentStatus;
    }

    public boolean isAlreadySplit() {
        return alreadySplit;
    }
}

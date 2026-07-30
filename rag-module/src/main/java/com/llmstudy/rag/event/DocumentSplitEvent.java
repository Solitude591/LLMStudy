package com.llmstudy.rag.event;

import org.springframework.context.ApplicationEvent;

/**
 * 文档分片完成事件。
 *
 * <p>当 Markdown 完成父子分片并写入 knowledge_segment 表后发布，触发后续的向量化流程。</p>
 *
 * <p><b>状态流转</b>：chunked → (监听器触发) → vectoring</p>
 */
public class DocumentSplitEvent extends ApplicationEvent {

    /**
     * 文档业务 ID。
     */
    private final String docId;

    /**
     * 本次分片生成的 segment 数量。
     */
    private final int segmentCount;

    /**
     * 构造文档分片完成事件。
     *
     * @param source 事件源，通常是发布事件的 Service
     * @param docId 文档业务 ID
     * @param segmentCount 生成的 segment 数量
     */
    public DocumentSplitEvent(Object source, String docId, int segmentCount) {
        super(source);
        this.docId = docId;
        this.segmentCount = segmentCount;
    }

    /**
     * 获取文档业务 ID。
     *
     * @return 文档 ID
     */
    public String getDocId() {
        return docId;
    }

    /**
     * 获取分片数量。
     *
     * @return segment 数量
     */
    public int getSegmentCount() {
        return segmentCount;
    }
}

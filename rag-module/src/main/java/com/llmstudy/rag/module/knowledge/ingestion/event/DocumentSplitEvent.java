package com.llmstudy.rag.module.knowledge.ingestion.event;

import org.springframework.context.ApplicationEvent;

/**
 * 文档版本分片完成事件。
 *
 * <p>当 Markdown 完成父子分片并写入 knowledge_segment 表后发布，触发后续的向量化流程。</p>
 *
 * <p>事件携带物理版本 ID（versionId），向量化阶段直接以版本为主键处理。</p>
 *
 * <p><b>状态流转</b>：chunked → (监听器触发) → vectoring</p>
 */
public class DocumentSplitEvent extends ApplicationEvent {

    /**
     * 物理版本 ID。
     */
    private final String versionId;

    /**
     * 本次分片生成的 segment 数量。
     */
    private final int segmentCount;

    /**
     * 构造文档版本分片完成事件。
     *
     * @param source       事件源，通常是发布事件的 Service
     * @param versionId    物理版本 ID
     * @param segmentCount 生成的 segment 数量
     */
    public DocumentSplitEvent(Object source, String versionId, int segmentCount) {
        super(source);
        this.versionId = versionId;
        this.segmentCount = segmentCount;
    }

    /**
     * 获取物理版本 ID。
     *
     * @return 版本 ID
     */
    public String getVersionId() {
        return versionId;
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

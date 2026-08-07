package com.llmstudy.rag.module.knowledge.ingestion.event;

import org.springframework.context.ApplicationEvent;

/**
 * 文档版本向量化完成事件。
 *
 * <p>当某版本所有待处理的 segment 完成 embedding 并写入 Elasticsearch 后发布，
 * 标志着该版本索引构建流程结束。</p>
 *
 * <p><b>状态流转</b>：vector_stored / ready → (流程结束，可触发通知等后续动作)</p>
 */
public class DocumentEmbeddedEvent extends ApplicationEvent {

    /**
     * 物理版本 ID。
     */
    private final String versionId;

    /**
     * 本次向量化的 segment 数量。
     */
    private final int embeddedCount;

    /**
     * 构造文档版本向量化完成事件。
     *
     * @param source        事件源，通常是发布事件的 Service
     * @param versionId     物理版本 ID
     * @param embeddedCount 向量化的 segment 数量
     */
    public DocumentEmbeddedEvent(Object source, String versionId, int embeddedCount) {
        super(source);
        this.versionId = versionId;
        this.embeddedCount = embeddedCount;
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
     * 获取向量化数量。
     *
     * @return 向量化的 segment 数量
     */
    public int getEmbeddedCount() {
        return embeddedCount;
    }
}

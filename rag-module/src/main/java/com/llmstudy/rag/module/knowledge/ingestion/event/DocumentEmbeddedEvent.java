package com.llmstudy.rag.module.knowledge.ingestion.event;

import org.springframework.context.ApplicationEvent;

/**
 * 文档向量化完成事件。
 *
 * <p>当所有待处理的 segment 完成 embedding 并写入 Elasticsearch 后发布，
 * 标志着整个文档索引构建流程结束。</p>
 *
 * <p><b>状态流转</b>：vector_stored → (流程结束，可触发通知等后续动作)</p>
 */
public class DocumentEmbeddedEvent extends ApplicationEvent {

    /**
     * 文档业务 ID。
     */
    private final String docId;

    /**
     * 本次向量化的 segment 数量。
     */
    private final int embeddedCount;

    /**
     * 构造文档向量化完成事件。
     *
     * @param source 事件源，通常是发布事件的 Service
     * @param docId 文档业务 ID
     * @param embeddedCount 向量化的 segment 数量
     */
    public DocumentEmbeddedEvent(Object source, String docId, int embeddedCount) {
        super(source);
        this.docId = docId;
        this.embeddedCount = embeddedCount;
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
     * 获取向量化数量。
     *
     * @return 向量化的 segment 数量
     */
    public int getEmbeddedCount() {
        return embeddedCount;
    }
}

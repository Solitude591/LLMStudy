package com.llmstudy.rag.module.knowledge.ingestion.event;

import org.springframework.context.ApplicationEvent;

/**
 * 文档上传完成事件。
 *
 * <p>当文档成功上传到 MinIO 并在数据库中创建记录后发布，触发后续的解析流程。
 * 事件驱动架构使上传接口可以立即返回，避免同步阻塞。</p>
 *
 * <p><b>状态流转</b>：uploaded → (监听器触发) → converting</p>
 */
public class DocumentUploadedEvent extends ApplicationEvent {

    /**
     * 文档业务 ID，全局唯一标识符。
     */
    private final String docId;

    /**
     * 构造文档上传完成事件。
     *
     * @param source 事件源，通常是发布事件的 Service
     * @param docId 文档业务 ID
     */
    public DocumentUploadedEvent(Object source, String docId) {
        super(source);
        this.docId = docId;
    }

    /**
     * 获取文档业务 ID。
     *
     * @return 文档 ID
     */
    public String getDocId() {
        return docId;
    }
}

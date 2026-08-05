package com.llmstudy.rag.module.knowledge.ingestion.event;

import org.springframework.context.ApplicationEvent;

/**
 * 文档解析完成事件。
 *
 * <p>当 MinerU 解析完成、Markdown 及图片上传到 MinIO、converted_doc_url 写入数据库后发布，
 * 触发后续的分片流程。</p>
 *
 * <p><b>状态流转</b>：converted → (监听器触发) → chunking</p>
 */
public class DocumentParsedEvent extends ApplicationEvent {

    /**
     * 文档业务 ID。
     */
    private final String docId;

    /**
     * 构造文档解析完成事件。
     *
     * @param source 事件源，通常是发布事件的 Service
     * @param docId 文档业务 ID
     */
    public DocumentParsedEvent(Object source, String docId) {
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

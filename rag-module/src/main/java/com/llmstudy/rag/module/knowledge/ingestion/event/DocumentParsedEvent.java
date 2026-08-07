package com.llmstudy.rag.module.knowledge.ingestion.event;

import org.springframework.context.ApplicationEvent;

/**
 * 文档版本解析完成事件。
 *
 * <p>当 MinerU 解析完成、Markdown 及图片上传到 MinIO、converted_doc_url 写入版本记录后发布，
 * 触发后续的分片流程。</p>
 *
 * <p>事件携带物理版本 ID（versionId），分片阶段直接以版本为主键处理。</p>
 *
 * <p><b>状态流转</b>：converted → (监听器触发) → chunking</p>
 */
public class DocumentParsedEvent extends ApplicationEvent {

    /**
     * 物理版本 ID。
     */
    private final String versionId;

    /**
     * 构造文档版本解析完成事件。
     *
     * @param source    事件源，通常是发布事件的 Service
     * @param versionId 物理版本 ID
     */
    public DocumentParsedEvent(Object source, String versionId) {
        super(source);
        this.versionId = versionId;
    }

    /**
     * 获取物理版本 ID。
     *
     * @return 版本 ID
     */
    public String getVersionId() {
        return versionId;
    }
}

package com.llmstudy.rag.module.knowledge.ingestion.event;

import org.springframework.context.ApplicationEvent;

/**
 * 文档版本上传完成事件。
 *
 * <p>当原始文件成功上传到 MinIO 并在数据库中创建逻辑文档与版本记录后发布，
 * 触发后续的解析流程。事件驱动架构使上传接口可以立即返回，避免同步阻塞。</p>
 *
 * <p><b>注意</b>：事件携带的是物理版本 ID（versionId），不是逻辑文档 ID（docId）。
 * 监听器如需逻辑文档信息，先通过版本记录反查 docId。</p>
 *
 * <p><b>状态流转</b>：uploaded → (监听器触发) → converting</p>
 */
public class DocumentUploadedEvent extends ApplicationEvent {

    /**
     * 物理版本 ID，流水线后续各阶段均以此标识该版本。
     */
    private final String versionId;

    /**
     * 构造文档版本上传完成事件。
     *
     * @param source    事件源，通常是发布事件的 Service
     * @param versionId 物理版本 ID
     */
    public DocumentUploadedEvent(Object source, String versionId) {
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

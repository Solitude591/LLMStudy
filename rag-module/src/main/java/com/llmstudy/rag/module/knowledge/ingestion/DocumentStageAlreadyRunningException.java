package com.llmstudy.rag.module.knowledge.ingestion;

/**
 * 同一文档阶段已经被其他线程抢占。
 *
 * <p>这是重复事件或并发重试产生的正常竞争，不应被当作处理失败，
 * 更不能把正在执行的文档状态回退到上一个阶段。</p>
 */
public class DocumentStageAlreadyRunningException extends RuntimeException {

    /** @param message 说明被哪个文档阶段的并发任务抢占 */
    public DocumentStageAlreadyRunningException(String message) {
        super(message);
    }
}

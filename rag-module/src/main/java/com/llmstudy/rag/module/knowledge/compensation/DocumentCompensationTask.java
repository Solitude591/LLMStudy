package com.llmstudy.rag.module.knowledge.compensation;

import com.llmstudy.rag.config.CompensationProperties;
import com.llmstudy.rag.entity.KnowledgeDocument;
import com.llmstudy.rag.enums.DocumentStatus;
import com.llmstudy.rag.module.knowledge.ingestion.event.DocumentParsedEvent;
import com.llmstudy.rag.module.knowledge.ingestion.event.DocumentSplitEvent;
import com.llmstudy.rag.module.knowledge.ingestion.event.DocumentUploadedEvent;
import com.llmstudy.rag.mapper.KnowledgeDocumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档处理失败补偿定时任务。
 *
 * <p>文档流水线（解析 → 分片 → 向量化）失败时，各阶段服务会把状态回退到
 * 稳定态并写入 error_message，等待补偿。本任务定时扫描两类文档并重新触发处理：</p>
 * <ul>
 *     <li><b>失败文档</b>：状态停留在 uploaded/converted/chunked 且带错误信息，
 *     按其当前状态发布对应阶段事件，重新进入异步流水线；</li>
 *     <li><b>卡死中间态</b>：进程崩溃等原因导致长期停留在 converting/splitting/vectoring，
 *     先 CAS 回退到上一稳定状态并记录错误，再重新触发对应阶段。</li>
 * </ul>
 *
 * <p>补偿只负责“发现 + 发布事件”，实际处理仍由 {@code KnowledgeIngestionCoordinator}
 * 的异步流水线执行，天然复用各阶段的 CAS 抢占、幂等与失败回退机制：
 * 多实例并发扫描时只有一个能抢注成功，不会产生重复处理。</p>
 */
@Component
public class DocumentCompensationTask {

    private static final Logger log =
            LoggerFactory.getLogger(DocumentCompensationTask.class);

    private final KnowledgeDocumentMapper documentMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final CompensationProperties properties;

    public DocumentCompensationTask(KnowledgeDocumentMapper documentMapper,
                                    ApplicationEventPublisher eventPublisher,
                                    CompensationProperties properties) {
        this.documentMapper = documentMapper;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    /**
     * 定时补偿入口：扫描失败文档并重新触发对应阶段的处理事件。
     *
     * <p>只处理失败时间早于冷却期的文档，避免与原处理流程竞争。
     * 通过 CAS 递增 retry_count 抢占重试名额：多实例并发或与流水线并发时
     * 只有一个调用方成功，且重试次数达上限后自动停止补偿。</p>
     */
    @Scheduled(cron = "${rag.compensation.cron:0 */5 * * * ?}")
    public void compensate() {
        LocalDateTime before = LocalDateTime.now().minus(properties.getRetryDelay());
        List<KnowledgeDocument> failed = documentMapper.findFailedForCompensation(
                properties.getMaxRetryCount(), before, properties.getBatchSize());
        if (failed.isEmpty()) {
            return;
        }

        log.info("补偿任务扫描到失败文档 {} 篇，开始补偿", failed.size());
        int compensated = 0;
        for (KnowledgeDocument doc : failed) {
            String docId = doc.getDocId();
            DocumentStatus status = doc.getDocumentStatus();
            // 抢占一次重试名额，防止并发实例重复发布事件
            if (documentMapper.incrementRetryCount(
                    docId, status, properties.getMaxRetryCount()) != 1) {
                log.info("文档重试名额被占用或已达上限，跳过: docId={}, status={}",
                        docId, status);
                continue;
            }
            publishStageEvent(docId, status);
            compensated++;
        }
        log.info("补偿任务完成: 提交补偿 {} 篇", compensated);
    }


    /**
     * 按文档当前状态发布对应阶段的处理事件，由异步监听器接管后续流水线。
     *
     * <p>补偿场景下分片数量未知，DocumentSplitEvent 的 segmentCount 传 0，
     * 该参数仅用于日志展示，不影响处理逻辑。</p>
     */
    private void publishStageEvent(String docId, DocumentStatus status) {
        switch (status) {
            case UPLOADED -> {
                log.info("重新触发文档解析: docId={}", docId);
                eventPublisher.publishEvent(new DocumentUploadedEvent(this, docId));
            }
            case CONVERTED -> {
                log.info("重新触发文档分片: docId={}", docId);
                eventPublisher.publishEvent(new DocumentParsedEvent(this, docId));
            }
            case CHUNKED -> {
                log.info("重新触发文档向量化: docId={}", docId);
                eventPublisher.publishEvent(new DocumentSplitEvent(this, docId, 0));
            }
            default -> log.warn("文档状态无需补偿触发: docId={}, status={}", docId, status);
        }
    }
}

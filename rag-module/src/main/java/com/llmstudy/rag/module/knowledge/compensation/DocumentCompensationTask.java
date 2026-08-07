package com.llmstudy.rag.module.knowledge.compensation;

import com.llmstudy.rag.config.CompensationProperties;
import com.llmstudy.rag.entity.KnowledgeDocumentVersion;
import com.llmstudy.rag.enums.DocumentStatus;
import com.llmstudy.rag.mapper.KnowledgeDocumentVersionMapper;
import com.llmstudy.rag.module.knowledge.ingestion.event.DocumentParsedEvent;
import com.llmstudy.rag.module.knowledge.ingestion.event.DocumentSplitEvent;
import com.llmstudy.rag.module.knowledge.ingestion.event.DocumentUploadedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 版本处理失败补偿定时任务。
 *
 * <p>版本流水线（解析 → 分片 → 向量化）失败时，各阶段服务会把处理状态回退到
 * 稳定态并写入 error_message，等待补偿。本任务定时扫描 knowledge_document_version
 * 并重新触发处理：</p>
 * <ul>
 *     <li><b>失败版本</b>：processing_status 停留在 uploaded/converted/chunked 且带错误信息，
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

    private final KnowledgeDocumentVersionMapper versionMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final CompensationProperties properties;

    public DocumentCompensationTask(KnowledgeDocumentVersionMapper versionMapper,
                                    ApplicationEventPublisher eventPublisher,
                                    CompensationProperties properties) {
        this.versionMapper = versionMapper;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    /**
     * 定时补偿入口：扫描失败版本并重新触发对应阶段的处理事件。
     *
     * <p>只处理失败时间早于冷却期的版本，避免与原处理流程竞争。
     * 通过 CAS 递增 retry_count 抢占重试名额：多实例并发或与流水线并发时
     * 只有一个调用方成功，且重试次数达上限后自动停止补偿。</p>
     */
    @Scheduled(cron = "${rag.compensation.cron:0 */5 * * * ?}")
    public void compensate() {
        compensateFailedVersions();
        recoverStalledStableVersions();
        recoverStaleVersions();
    }

    /** 补偿带错误信息、停留稳定态的失败版本。 */
    private void compensateFailedVersions() {
        LocalDateTime before = LocalDateTime.now().minus(properties.getRetryDelay());
        List<KnowledgeDocumentVersion> failed = versionMapper.findFailedForCompensation(
                properties.getMaxRetryCount(), before, properties.getBatchSize());
        if (failed.isEmpty()) {
            return;
        }

        log.info("补偿任务扫描到失败版本 {} 个，开始补偿", failed.size());
        int compensated = 0;
        for (KnowledgeDocumentVersion version : failed) {
            String versionId = version.getVersionId();
            DocumentStatus status = version.getDocumentStatus();
            int retryCount = version.getRetryCount() == null ? 0 : version.getRetryCount();
            // 抢占一次重试名额，防止并发实例重复发布事件
            if (versionMapper.incrementRetryCount(
                    versionId, status, retryCount, properties.getMaxRetryCount()) != 1) {
                log.info("版本重试名额被占用或已达上限，跳过: versionId={}, status={}",
                        versionId, status);
                continue;
            }
            publishStageEvent(versionId, status);
            compensated++;
        }
        log.info("补偿任务完成: 提交补偿 {} 个版本", compensated);
    }

    /**
     * 恢复数据库已提交、但本地事件可能因进程退出而丢失的稳定态版本。
     */
    private void recoverStalledStableVersions() {
        LocalDateTime deadline = LocalDateTime.now().minus(properties.getStaleTimeout());
        List<KnowledgeDocumentVersion> stalled = versionMapper.findStalledStable(
                properties.getMaxRetryCount(), deadline, properties.getBatchSize());
        for (KnowledgeDocumentVersion version : stalled) {
            String versionId = version.getVersionId();
            DocumentStatus status = version.getDocumentStatus();
            int retryCount = version.getRetryCount() == null ? 0 : version.getRetryCount();
            if (versionMapper.incrementRetryCount(
                    versionId, status, retryCount, properties.getMaxRetryCount()) == 1) {
                log.info("重新触发长时间未推进的版本: versionId={}, status={}", versionId, status);
                publishStageEvent(versionId, status);
            }
        }
    }

    /** 回退卡死在执行中间态的版本，再重新触发对应阶段。 */
    private void recoverStaleVersions() {
        LocalDateTime deadline = LocalDateTime.now().minus(properties.getStaleTimeout());
        List<KnowledgeDocumentVersion> stale = versionMapper.findStaleIntermediate(
                deadline, properties.getBatchSize());
        if (stale.isEmpty()) {
            return;
        }

        log.info("补偿任务扫描到卡死中间态版本 {} 个，开始回退", stale.size());
        for (KnowledgeDocumentVersion version : stale) {
            String versionId = version.getVersionId();
            DocumentStatus executing = version.getDocumentStatus();
            DocumentStatus fallback = fallbackOf(executing);
            if (versionMapper.compareAndSetProcessingStatusWithError(
                    versionId, fallback, executing, "中间态超时，回退后重新触发") == 1) {
                publishStageEvent(versionId, fallback);
            }
        }
    }

    /**
     * 按版本当前处理状态发布对应阶段的处理事件，由异步监听器接管后续流水线。
     *
     * <p>补偿场景下分片数量未知，DocumentSplitEvent 的 segmentCount 传 0，
     * 该参数仅用于日志展示，不影响处理逻辑。</p>
     */
    private void publishStageEvent(String versionId, DocumentStatus status) {
        switch (status) {
            case UPLOADED -> {
                log.info("重新触发版本解析: versionId={}", versionId);
                eventPublisher.publishEvent(new DocumentUploadedEvent(this, versionId));
            }
            case CONVERTED -> {
                log.info("重新触发版本分片: versionId={}", versionId);
                eventPublisher.publishEvent(new DocumentParsedEvent(this, versionId));
            }
            case CHUNKED -> {
                log.info("重新触发版本向量化: versionId={}", versionId);
                eventPublisher.publishEvent(new DocumentSplitEvent(this, versionId, 0));
            }
            default -> log.warn("版本状态无需补偿触发: versionId={}, status={}", versionId, status);
        }
    }

    /** 执行中间态回退到的上一个稳定状态。 */
    private static DocumentStatus fallbackOf(DocumentStatus executing) {
        return switch (executing) {
            case CONVERTING -> DocumentStatus.UPLOADED;
            case SPLITTING -> DocumentStatus.CONVERTED;
            case VECTORING -> DocumentStatus.CHUNKED;
            default -> throw new IllegalArgumentException("非执行态无需回退: " + executing);
        };
    }
}

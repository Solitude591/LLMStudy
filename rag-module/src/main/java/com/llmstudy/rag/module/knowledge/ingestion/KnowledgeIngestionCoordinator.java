package com.llmstudy.rag.module.knowledge.ingestion;

import com.llmstudy.rag.dto.DocumentSplitResult;
import com.llmstudy.rag.module.knowledge.document.KnowledgeDocumentService;
import com.llmstudy.rag.module.knowledge.ingestion.chunk.DocumentChunkingService;
import com.llmstudy.rag.module.knowledge.ingestion.embedding.SegmentEmbeddingService;
import com.llmstudy.rag.module.knowledge.ingestion.event.DocumentEmbeddedEvent;
import com.llmstudy.rag.module.knowledge.ingestion.event.DocumentParsedEvent;
import com.llmstudy.rag.module.knowledge.ingestion.event.DocumentSplitEvent;
import com.llmstudy.rag.module.knowledge.ingestion.event.DocumentUploadedEvent;
import com.llmstudy.rag.module.knowledge.model.DocumentProcessingOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 文档版本生命周期事件监听器。
 *
 * <p>采用事件驱动架构串联版本处理流水线，全程以 versionId 为主键：
 * <pre>
 * upload → parse → split → embed
 *   ↑         ↑       ↑       ↑
 *   发布      发布    发布    发布
 *   Event    Event   Event   Event
 * </pre>
 *
 * <p>每个阶段在独立异步线程中执行，不阻塞上传接口的 HTTP 响应。
 * 失败时由各阶段服务通过带期望状态的 CAS 记录错误信息，
 * 前端轮询版本状态即可获知进度或失败原因。</p>
 */
@Component
public class KnowledgeIngestionCoordinator {

    private static final Logger log =
            LoggerFactory.getLogger(KnowledgeIngestionCoordinator.class);

    /** 文档解析/上传领域服务；负责 MinIO 上传、MinerU 解析、Markdown 图片改写等。 */
    private final KnowledgeDocumentService documentService;

    /** 文档分片服务；负责父子分片和 MySQL segment 写入。 */
    private final DocumentChunkingService chunkingService;

    /** 分片向量化服务；负责 Embedding 计算、ES 写入和状态迁移。 */
    private final SegmentEmbeddingService embeddingService;

    /** Spring 事件发布器；本阶段完成后发布下一阶段事件。 */
    private final ApplicationEventPublisher eventPublisher;

    public KnowledgeIngestionCoordinator(KnowledgeDocumentService documentService,
                                         DocumentChunkingService chunkingService,
                                         SegmentEmbeddingService embeddingService,
                                         ApplicationEventPublisher eventPublisher) {
        this.documentService = documentService;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 监听版本上传完成事件，异步触发解析。
     *
     * <p>{@code @Async} 使此方法运行在 Spring 异步任务线程池中。
     * 监听器本身不持有事务，MinerU、MinIO 等外部调用不会长期占用数据库连接。</p>
     */
    @Async
    @EventListener
    public void onDocumentUploaded(DocumentUploadedEvent event) {
        String versionId = event.getVersionId();
        log.info("收到文档上传事件，开始异步解析: versionId={}", versionId);

        try {
            // PDF/Word 解析为 Markdown 后继续 RAG 流水线。
            DocumentProcessingOutcome outcome = documentService.processDocument(versionId);
            if (outcome == DocumentProcessingOutcome.SKIPPED) {
                log.info("忽略重复或迟到的文档上传事件: versionId={}", versionId);
                return;
            }
            if (outcome == DocumentProcessingOutcome.EXCEL_IMPORTED) {
                log.info("Excel 结构化导入完成，不进入分片和向量化: versionId={}", versionId);
                return;
            }

            // 解析成功后才发布下一阶段事件。这样监听器 onDocumentParsed 拿到的一定是
            // processing_status=converted 的版本，无需在分片入口重复校验状态。
            log.info("异步解析完成，发布解析事件: versionId={}", versionId);
            eventPublisher.publishEvent(new DocumentParsedEvent(this, versionId));
        } catch (DocumentStageAlreadyRunningException e) {
            // 同一阶段已被其他事件抢占属于正常幂等竞争，不发布下游事件，也不回退状态。
            log.info("忽略重复的文档上传事件: versionId={}, reason={}", versionId, e.getMessage());
        } catch (Exception e) {
            log.error("异步解析失败: versionId={}", versionId, e);
            // processDocument 已使用当前执行态 -> uploaded 的 CAS 记录失败，
            // 监听器不再无条件覆盖版本状态。
        }
    }

    /**
     * 监听版本解析完成事件，异步触发分片。
     *
     * <p>只有解析成功（processing_status=converted）的版本才会发布此事件，
     * 因此监听器无需重复校验版本状态。</p>
     */
    @Async
    @EventListener
    public void onDocumentParsed(DocumentParsedEvent event) {
        String versionId = event.getVersionId();
        log.info("收到文档解析事件，开始异步分片: versionId={}", versionId);

        try {
            // splitDocument 从 MinIO 下载 converted Markdown，按标题父子切分后写入
            // knowledge_segment 表。内部已通过已有分片计数和状态 CAS 做幂等检查：
            // 已有 segment 则跳过，只有 converted 且无数据时才真正执行分片。
            DocumentSplitResult result = chunkingService.splitDocument(versionId);

            // 携带 segment 数量给下游，方便向量化阶段预估 batch 数和日志粒度。
            log.info("异步分片完成: versionId={}, segmentCount={}", versionId, result.getSegmentCount());
            eventPublisher.publishEvent(
                    new DocumentSplitEvent(this, versionId, result.getSegmentCount()));
        } catch (DocumentStageAlreadyRunningException e) {
            log.info("忽略重复的文档解析完成事件: versionId={}, reason={}", versionId, e.getMessage());
        } catch (Exception e) {
            log.error("异步分片失败: versionId={}", versionId, e);
            // splitDocument 已使用 splitting -> 原稳定状态的 CAS 记录失败。
        }
    }

    /**
     * 监听版本分片完成事件，异步触发向量化。
     *
     * <p>仅状态为 chunked 且存在待向量化 segment 的版本才会执行。
     * 全部 segment 均为 skip_embedding 的版本（纯标题/目录）直接标记为 READY。</p>
     */
    @Async
    @EventListener
    public void onDocumentSplit(DocumentSplitEvent event) {
        String versionId = event.getVersionId();
        log.info("收到文档分片事件，开始异步向量化: versionId={}, segmentCount={}",
                versionId, event.getSegmentCount());

        try {
            // embedSegments 只处理 status='INIT' 且 skip_embedding=0 的 segment：
            // standalone 和 child 参与向量化，parent（完整章节）被跳过。
            // 内部按 embeddingBatchSize 分批调用 embedding API，每批独立写入 ES。
            int embedded = embeddingService.embedSegments(versionId);

            log.info("异步向量化完成: versionId={}, embeddedCount={}", versionId, embedded);
            // 向量化完成是该版本处理流水线的终点，版本进入 READY 等待手动发布。
            // DocumentEmbeddedEvent 预留供后续扩展：通知推送、混合检索索引重建等。
            eventPublisher.publishEvent(
                    new DocumentEmbeddedEvent(this, versionId, embedded));
        } catch (DocumentStageAlreadyRunningException e) {
            log.info("忽略重复的文档分片完成事件: versionId={}, reason={}", versionId, e.getMessage());
        } catch (Exception e) {
            log.error("异步向量化失败: versionId={}", versionId, e);
            // embedSegments 已使用 vectoring -> 原稳定状态的 CAS 记录失败。
        }
    }
}

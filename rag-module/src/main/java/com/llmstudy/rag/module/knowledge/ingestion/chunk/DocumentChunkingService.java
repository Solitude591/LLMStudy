package com.llmstudy.rag.module.knowledge.ingestion.chunk;

import com.llmstudy.rag.dto.DocumentSplitResult;
import com.llmstudy.rag.entity.KnowledgeDocumentVersion;
import com.llmstudy.rag.entity.KnowledgeSegment;
import com.llmstudy.rag.enums.DocumentStatus;
import com.llmstudy.rag.enums.SegmentStatus;
import com.llmstudy.rag.mapper.KnowledgeDocumentVersionMapper;
import com.llmstudy.rag.mapper.KnowledgeSegmentMapper;
import com.llmstudy.rag.module.knowledge.ingestion.DocumentStageAlreadyRunningException;
import com.llmstudy.rag.module.knowledge.model.KnowledgeChunk;
import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 下载转换后的 Markdown，生成父子分片，并以事务方式持久化。 */
@Service
public class DocumentChunkingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunkingService.class);
    private final KnowledgeDocumentVersionMapper versionMapper;
    private final KnowledgeSegmentMapper segmentMapper;
    private final MarkdownHeaderChunker chunker;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transactionTemplate;

    public DocumentChunkingService(KnowledgeDocumentVersionMapper versionMapper,
                                   KnowledgeSegmentMapper segmentMapper,
                                   MarkdownHeaderChunker chunker,
                                   HttpClient httpClient,
                                   JsonMapper jsonMapper,
                                   PlatformTransactionManager transactionManager) {
        this.versionMapper = versionMapper;
        this.segmentMapper = segmentMapper;
        this.chunker = chunker;
        this.httpClient = httpClient;
        this.jsonMapper = jsonMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 抢占版本分片阶段，完成 Markdown 下载、切分、入库与状态迁移。
     *
     * <p>已有分片时按幂等语义直接返回；新分片与 converted → chunked
     * 状态迁移在同一数据库事务中完成。</p>
     *
     * @param versionId 物理版本 ID
     * @return 分片数量、最终状态和幂等命中标记
     */
    public DocumentSplitResult splitDocument(String versionId) {
        requireVersionId(versionId);
        KnowledgeDocumentVersion version = versionMapper.findByVersionId(versionId);
        if (version == null) {
            throw new IllegalArgumentException("版本不存在: " + versionId);
        }
        // 先以持久化分片作为幂等事实，用于恢复“分片已写入但事件未发布”的场景。
        int existing = segmentMapper.countByVersionId(versionId);
        if (existing > 0) {
            if (version.getDocumentStatus() == DocumentStatus.CONVERTED) {
                versionMapper.compareAndSetProcessingStatusAndClearError(
                        versionId, DocumentStatus.CHUNKED, DocumentStatus.CONVERTED);
            }
            String status = version.getDocumentStatus() == DocumentStatus.CONVERTED
                    ? DocumentStatus.CHUNKED.value() : version.getProcessingStatus();
            return new DocumentSplitResult(versionId, existing, status, true);
        }
        DocumentStatus previous = version.getDocumentStatus();
        if (previous == DocumentStatus.SPLITTING) {
            throw new DocumentStageAlreadyRunningException("版本分片阶段已经被抢占: " + versionId);
        }
        if (previous != DocumentStatus.CONVERTED && previous != DocumentStatus.CHUNKED) {
            throw new IllegalStateException("版本尚未解析完成，当前状态: " + version.getProcessingStatus());
        }
        if (version.getConvertedDocUrl() == null || version.getConvertedDocUrl().isBlank()) {
            throw new IllegalStateException("版本 converted_doc_url 为空: " + versionId);
        }
        // CAS 抢占后才进行网络下载，防止重复事件并发生成两套分片。
        if (versionMapper.compareAndSetProcessingStatus(
                versionId, DocumentStatus.SPLITTING, previous) != 1) {
            throw new DocumentStageAlreadyRunningException("版本分片阶段已经被抢占: " + versionId);
        }
        try {
            String markdown = download(version.getConvertedDocUrl());
            List<KnowledgeChunk> chunks = chunker.split(markdown, Map.of(
                    SegmentMetadataKeys.DOC_ID, version.getDocId(),
                    SegmentMetadataKeys.VERSION_ID, versionId,
                    SegmentMetadataKeys.SOURCE_URL, version.getConvertedDocUrl()));
            if (chunks.isEmpty()) {
                throw new IllegalStateException("版本未生成任何分片: " + versionId);
            }
            List<KnowledgeSegment> segments = toEntities(version, chunks);
            // 分片数据与最终状态在同一个事务中提交，要么同时成功，要么同时回滚。
            transactionTemplate.executeWithoutResult(status -> {
                int inserted = segmentMapper.batchInsert(segments);
                if (inserted != segments.size()) {
                    throw new IllegalStateException("分片保存数量不一致");
                }
                if (versionMapper.compareAndSetProcessingStatusAndClearError(
                        versionId, DocumentStatus.CHUNKED, DocumentStatus.SPLITTING) != 1) {
                    throw new IllegalStateException("更新版本分片状态失败: " + versionId);
                }
            });
            return new DocumentSplitResult(versionId, segments.size(),
                    DocumentStatus.CHUNKED.value(), false);
        } catch (Exception e) {
            log.error("版本分片失败: versionId={}", versionId, e);
            versionMapper.compareAndSetProcessingStatusWithError(versionId, previous,
                    DocumentStatus.SPLITTING, truncate("分片失败: " + e.getMessage()));
            throw new RuntimeException("版本分片失败: " + e.getMessage(), e);
        }
    }

    /** 通过已持久化的 converted URL 下载 UTF-8 Markdown。 */
    private String download(String url) {
        try {
            HttpResponse<byte[]> response = httpClient.send(HttpRequest.newBuilder()
                    .uri(URI.create(url)).header("Accept", "text/markdown,text/plain,*/*")
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Markdown 下载失败，HTTP status=" + response.statusCode());
            }
            String markdown = new String(response.body(), StandardCharsets.UTF_8);
            if (markdown.isBlank()) {
                throw new IllegalStateException("下载到的 Markdown 内容为空");
            }
            return markdown;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Markdown 下载被中断", e);
        } catch (Exception e) {
            throw new RuntimeException("读取转换后 Markdown 失败: " + url, e);
        }
    }

    /** 将框架无关分片转换为 MySQL 实体，在适配器边界序列化 metadata。 */
    private List<KnowledgeSegment> toEntities(KnowledgeDocumentVersion version,
                                              List<KnowledgeChunk> chunks) {
        List<KnowledgeSegment> segments = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            KnowledgeChunk chunk = chunks.get(index);
            KnowledgeSegment segment = new KnowledgeSegment();
            segment.setChunkId(chunk.chunkId());
            segment.setText(chunk.text());
            segment.setDocId(version.getDocId());
            segment.setVersionId(version.getVersionId());
            segment.setChunkOrder(index);
            segment.setEmbeddingId("");
            segment.setSegmentStatus(SegmentStatus.INIT);
            segment.setSkipEmbedding(chunk.skipEmbedding());
            try {
                segment.setMetadata(jsonMapper.writeValueAsString(chunk.metadata()));
            } catch (Exception e) {
                throw new IllegalStateException("分片 metadata 序列化失败", e);
            }
            segments.add(segment);
        }
        return segments;
    }

    private static void requireVersionId(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("versionId 不能为空");
        }
    }

    private static String truncate(String message) {
        return message != null && message.length() > 2000
                ? message.substring(0, 2000) + "..." : message;
    }
}

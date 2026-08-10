package com.llmstudy.rag.module.knowledge.ingestion.chunk;

import com.llmstudy.rag.dto.DocumentSplitResult;
import com.llmstudy.rag.dto.MineruContentElement;
import com.llmstudy.rag.entity.KnowledgeDocumentVersion;
import com.llmstudy.rag.entity.KnowledgeSegment;
import com.llmstudy.rag.enums.DocumentStatus;
import com.llmstudy.rag.enums.SegmentStatus;
import com.llmstudy.rag.mapper.KnowledgeDocumentVersionMapper;
import com.llmstudy.rag.mapper.KnowledgeSegmentMapper;
import com.llmstudy.rag.module.knowledge.ingestion.DocumentStageAlreadyRunningException;
import com.llmstudy.rag.module.knowledge.model.KnowledgeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 下载 content_list 或 Markdown，生成原子分片，并以事务方式持久化。
 *
 * <p>优先消费 {@code content_list_url}；缺失、下载失败或解析失败时回退 Markdown AST。</p>
 */
@Service
public class DocumentChunkingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunkingService.class);
    private final KnowledgeDocumentVersionMapper versionMapper;
    private final KnowledgeSegmentMapper segmentMapper;
    private final ContentListPaperChunker contentListChunker;
    private final MarkdownAstPaperChunker markdownChunker;
    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transactionTemplate;

    public DocumentChunkingService(KnowledgeDocumentVersionMapper versionMapper,
                                   KnowledgeSegmentMapper segmentMapper,
                                   ContentListPaperChunker contentListChunker,
                                   MarkdownAstPaperChunker markdownChunker,
                                   HttpClient httpClient,
                                   JsonMapper jsonMapper,
                                   PlatformTransactionManager transactionManager) {
        this.versionMapper = versionMapper;
        this.segmentMapper = segmentMapper;
        this.contentListChunker = contentListChunker;
        this.markdownChunker = markdownChunker;
        this.httpClient = httpClient;
        this.jsonMapper = jsonMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 抢占版本分片阶段，完成下载、切分、入库与状态迁移。
     *
     * <p>已有分片时按幂等直接返回；新分片与 {@code SPLITTING → CHUNKED}
     * 在同一事务提交，避免只写一半。</p>
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
        // 以 DB 分片数为幂等事实，恢复「已写入但事件未发」的场景。
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
        // CAS 后再下载，防止重复事件并发生成两套分片。
        if (versionMapper.compareAndSetProcessingStatus(
                versionId, DocumentStatus.SPLITTING, previous) != 1) {
            throw new DocumentStageAlreadyRunningException("版本分片阶段已经被抢占: " + versionId);
        }
        try {
            List<KnowledgeChunk> chunks = splitVersion(version);
            if (chunks.isEmpty()) {
                throw new IllegalStateException("版本未生成任何分片: " + versionId);
            }
            List<KnowledgeSegment> segments = toEntities(version, chunks);
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

    /** 主路径 content_list；失败或空结果时回退 Markdown AST。 */
    private List<KnowledgeChunk> splitVersion(KnowledgeDocumentVersion version) {
        List<KnowledgeChunk> fromContentList = trySplitContentList(version);
        if (!fromContentList.isEmpty()) {
            return fromContentList;
        }
        log.info("回退 Markdown AST 分片: versionId={}", version.getVersionId());
        String markdown = downloadText(version.getConvertedDocUrl());
        return markdownChunker.split(markdown);
    }

    /**
     * 尝试 content_list 分片。任何下载/解析/空结果都返回空列表，由调用方回退，
     * 不把 content_list 故障升级为整版本失败。
     */
    private List<KnowledgeChunk> trySplitContentList(KnowledgeDocumentVersion version) {
        String contentListUrl = version.getContentListUrl();
        if (contentListUrl == null || contentListUrl.isBlank()) {
            return List.of();
        }
        try {
            String json = downloadText(contentListUrl);
            List<MineruContentElement> elements = jsonMapper.readValue(
                    json, new TypeReference<List<MineruContentElement>>() {
                    });
            if (elements == null || elements.isEmpty()) {
                log.warn("content_list 为空，回退 Markdown: versionId={}", version.getVersionId());
                return List.of();
            }
            List<KnowledgeChunk> chunks = contentListChunker.split(elements);
            if (chunks.isEmpty()) {
                log.warn("content_list 未产出分片，回退 Markdown: versionId={}",
                        version.getVersionId());
            }
            return chunks;
        } catch (Exception e) {
            log.warn("content_list 下载或解析失败，回退 Markdown: versionId={}, cause={}",
                    version.getVersionId(), e.toString());
            return List.of();
        }
    }

    /** 下载 UTF-8 文本（Markdown 或 content_list JSON）。 */
    private String downloadText(String url) {
        try {
            HttpResponse<byte[]> response = httpClient.send(HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json,text/markdown,text/plain,*/*")
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("下载失败，HTTP status=" + response.statusCode());
            }
            String body = new String(response.body(), StandardCharsets.UTF_8);
            if (body.isBlank()) {
                throw new IllegalStateException("下载到的内容为空");
            }
            return body;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("下载被中断", e);
        } catch (Exception e) {
            throw new RuntimeException("读取远程内容失败: " + url, e);
        }
    }

    /**
     * 转为 MySQL 实体。doc_id/version_id/skip_embedding 走独立列，
     * metadata JSON 仅保留精简结构字段。
     */
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

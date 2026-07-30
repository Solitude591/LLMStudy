package com.llmstudy.rag.service.impl;

import com.llmstudy.rag.config.MarkdownSplitterProperties;
import com.llmstudy.rag.dto.DocumentSplitResult;
import com.llmstudy.rag.entity.KnowledgeDocument;
import com.llmstudy.rag.entity.KnowledgeSegment;
import com.llmstudy.rag.enums.DocumentStatus;
import com.llmstudy.rag.enums.SegmentStatus;
import com.llmstudy.rag.mapper.KnowledgeDocumentMapper;
import com.llmstudy.rag.mapper.KnowledgeSegmentMapper;
import com.llmstudy.rag.service.DocumentSegmentService;
import com.llmstudy.rag.service.DocumentStageAlreadyRunningException;
import com.llmstudy.rag.service.splitter.MarkdownHeaderParentTextSplitter;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 从转换后 Markdown 生成父子分片并保存到 knowledge_segment。
 */
@Service
public class DocumentSegmentServiceImpl implements DocumentSegmentService {

    private static final Logger log =
            LoggerFactory.getLogger(DocumentSegmentServiceImpl.class);

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeSegmentMapper segmentMapper;
    private final MarkdownHeaderParentTextSplitter textSplitter;
    private final HttpClient httpClient;
    private final JsonMapper objectMapper;
    private final OpenAiEmbeddingModel embeddingModel;
    private final ElasticsearchEmbeddingStore embeddingStore;
    private final MarkdownSplitterProperties splitterProperties;
    private final TransactionTemplate transactionTemplate;

    public DocumentSegmentServiceImpl(KnowledgeDocumentMapper documentMapper,
                                      KnowledgeSegmentMapper segmentMapper,
                                      MarkdownHeaderParentTextSplitter textSplitter,
                                      HttpClient httpClient,
                                      JsonMapper objectMapper,
                                      OpenAiEmbeddingModel embeddingModel,
                                      ElasticsearchEmbeddingStore embeddingStore,
                                      MarkdownSplitterProperties splitterProperties,
                                      PlatformTransactionManager transactionManager) {
        this.documentMapper = documentMapper;
        this.segmentMapper = segmentMapper;
        this.textSplitter = textSplitter;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.splitterProperties = splitterProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public DocumentSplitResult splitDocument(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId 不能为空");
        }

        KnowledgeDocument document = documentMapper.findByDocId(docId);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在: " + docId);
        }

        int existingCount = segmentMapper.countByDocId(docId);
        if (existingCount > 0) {
            // 历史数据存在分片但文档仍是 converted 时，顺便修正生命周期状态。
            if (document.getDocumentStatus() == DocumentStatus.CONVERTED) {
                documentMapper.compareAndSetStatusAndClearError(
                        docId, DocumentStatus.CHUNKED, DocumentStatus.CONVERTED);
            }
            log.info("文档已经完成分片，跳过重复处理: docId={}, segmentCount={}",
                    docId, existingCount);
            return new DocumentSplitResult(
                    docId, existingCount, currentStatusAfterExisting(document), true);
        }

        // splitting 表示其他线程已经抢占该阶段。重复事件应直接结束，不能回退其状态。
        DocumentStatus documentStatus = document.getDocumentStatus();
        if (documentStatus == DocumentStatus.SPLITTING) {
            throw new DocumentStageAlreadyRunningException(
                    "文档分片阶段已经被其他线程抢占: " + docId);
        }
        // 只有已解析完成的 Markdown 才能进入分片；chunked 但无数据时允许补偿重建。
        if (documentStatus != DocumentStatus.CONVERTED
                && documentStatus != DocumentStatus.CHUNKED) {
            throw new IllegalStateException(
                    "文档尚未解析完成，当前状态: " + document.getDocStatus());
        }
        if (document.getConvertedDocUrl() == null
                || document.getConvertedDocUrl().isBlank()) {
            throw new IllegalStateException("文档 converted_doc_url 为空: " + docId);
        }

        // 通过短 SQL 原子抢占分片权，随后立即提交；网络下载和 CPU 分片不占数据库事务。
        if (documentMapper.compareAndSetStatus(
                docId, DocumentStatus.SPLITTING, documentStatus) != 1) {
            throw new DocumentStageAlreadyRunningException(
                    "文档分片阶段已经被其他线程抢占: " + docId);
        }

        try {
            // 从 converted_doc_url 下载最终 Markdown，内容已经包含 MinIO 图片地址和图片描述。
            String markdown = downloadMarkdown(document.getConvertedDocUrl());
            Document langchainDocument = Document.from(
                    markdown,
                    Metadata.from(Map.of(
                            "doc_id", docId,
                            "source_url", document.getConvertedDocUrl())));

            // 分片器会生成 chunk_id、chunk_type、parent_chunk_id 和标题路径等元数据。
            List<TextSegment> textSegments = textSplitter.split(langchainDocument);
            if (textSegments.isEmpty()) {
                throw new IllegalStateException("文档未生成任何分片: " + docId);
            }

            List<KnowledgeSegment> segments = convertSegments(docId, textSegments);

            // 只把 MySQL 分片落库和状态完成放在同一个短事务中。
            transactionTemplate.executeWithoutResult(transactionStatus -> {
                KnowledgeDocument locked = documentMapper.findByDocIdForUpdate(docId);
                if (locked == null
                        || locked.getDocumentStatus() != DocumentStatus.SPLITTING) {
                    throw new IllegalStateException("文档分片状态已发生变化: " + docId);
                }
                int inserted = segmentMapper.batchInsert(segments);
                if (inserted != segments.size()) {
                    throw new IllegalStateException(
                            "分片保存数量不一致，期望=" + segments.size() + "，实际=" + inserted);
                }
                if (documentMapper.compareAndSetStatusAndClearError(
                        docId, DocumentStatus.CHUNKED, DocumentStatus.SPLITTING) != 1) {
                    throw new IllegalStateException("更新文档分片状态失败: " + docId);
                }
            });

            log.info("文档分片并入库完成: docId={}, segmentCount={}, status={}",
                    docId, segments.size(), DocumentStatus.CHUNKED.value());
            return new DocumentSplitResult(
                    docId, segments.size(), DocumentStatus.CHUNKED.value(), false);
        } catch (Exception e) {
            log.error("文档分片失败: docId={}", docId, e);
            documentMapper.compareAndSetStatusWithError(
                    docId,
                    documentStatus,
                    DocumentStatus.SPLITTING,
                    truncateError("分片失败: " + e.getMessage()));
            throw new RuntimeException("文档分片失败: " + e.getMessage(), e);
        }
    }

    @Override
    public int embedSegments(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId 不能为空");
        }

        KnowledgeDocument document = documentMapper.findByDocId(docId);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在: " + docId);
        }

        DocumentStatus currentStatus = document.getDocumentStatus();
        if (currentStatus == DocumentStatus.VECTORING) {
            throw new DocumentStageAlreadyRunningException(
                    "文档向量化阶段已经被其他线程抢占: " + docId);
        }
        if (currentStatus != DocumentStatus.CHUNKED
                && currentStatus != DocumentStatus.VECTOR_STORED) {
            throw new IllegalStateException(
                    "文档状态不允许向量化，当前状态: " + currentStatus.value());
        }

        // 只处理待向量化的 segment：status=init 且 skip_embedding=0
        List<KnowledgeSegment> pending = segmentMapper.findPendingByDocId(docId);
        if (pending.isEmpty()) {
            // 没有待处理 segment，但状态仍然正确——可能全部已向量化，或没有可向量化的段。
            // 如果文档仍是 chunked，说明只有 parent 段（全部 skip_embedding），直接跳到 vector_stored。
            if (currentStatus == DocumentStatus.CHUNKED) {
                if (documentMapper.compareAndSetStatusAndClearError(
                        docId, DocumentStatus.VECTOR_STORED, DocumentStatus.CHUNKED) != 1) {
                    throw new DocumentStageAlreadyRunningException(
                            "文档向量化状态已被其他线程修改: " + docId);
                }
            }
            log.info("文档没有待向量化的 segment: docId={}, 状态由 {} -> vector_stored",
                    docId, currentStatus.value());
            return 0;
        }

        log.info("开始向量化: docId={}, pendingCount={}", docId, pending.size());

        // 原子状态迁移：仅当文档仍处于 chunked 或 vector_stored 时才进入 vectoring
        if (documentMapper.compareAndSetStatus(
                docId, DocumentStatus.VECTORING, currentStatus) != 1) {
            throw new DocumentStageAlreadyRunningException(
                    "文档向量化阶段已经被其他线程抢占: " + docId);
        }

        try {
            int batchSize = Math.max(1, splitterProperties.getEmbeddingBatchSize());
            int offset = 0;
            int totalWritten = 0;

            // 每批独立执行 embed → ES → MySQL 三步，前一批成功后才处理下一批。
            // 某批失败时前面已写入 ES + MySQL 的 segment 不会回滚（ES 不可回滚），
            // 但重试时 findPendingByDocId 自动跳过已完成 segment，无数据浪费。
            while (offset < pending.size()) {
                int end = Math.min(offset + batchSize, pending.size());
                List<KnowledgeSegment> batch = pending.subList(offset, end);
                log.info("Embedding 批次: docId={}, 进度={}/{}, 批次大小={}",
                        docId, offset, pending.size(), batch.size());

                // 第一步：调用 embedding API 获取向量（最耗时，且可能被限流）
                List<String> batchTexts = batch.stream()
                        .map(KnowledgeSegment::getText)
                        .collect(Collectors.toList());
                List<float[]> batchVectors = embeddingModel.embed(batchTexts);

                // 防御性校验：API 返回向量数量必须与本批输入严格一致，
                // 否则索引错位会导致后续检索命中完全错误的 segment。
                if (batchVectors.size() != batch.size()) {
                    throw new IllegalStateException("Embedding 模型返回数量与输入不符: "
                            + "期望=" + batch.size() + ", 实际=" + batchVectors.size());
                }

                // 第二步：组装 LangChain4j 对象并写入 Elasticsearch。
                // embedding_id 直接使用 chunkId——ES 写入时已通过 addAll 的 ID 列表指定，
                // 同一 chunkId 重复写入会覆盖旧向量，天然支持重试。
                List<String> chunkIds = new ArrayList<>(batch.size());
                List<Embedding> embeddings = new ArrayList<>(batch.size());
                List<TextSegment> textSegments = new ArrayList<>(batch.size());

                for (int i = 0; i < batch.size(); i++) {
                    KnowledgeSegment segment = batch.get(i);
                    chunkIds.add(segment.getChunkId());
                    embeddings.add(Embedding.from(batchVectors.get(i)));

                    // ES 内部 metadata 写入 splitter 产出的完整字段：
                    // chunk_type、parent_chunk_id、header_path 等，
                    // 检索命中后无需回 MySQL 查 chunk_type 或回找父分片。
                    Map<String, Object> esMetadata = parseMetadataMap(segment.getMetadata());
                    esMetadata.put("doc_id", docId);
                    textSegments.add(TextSegment.from(
                            segment.getText(), Metadata.from(esMetadata)));
                }

                embeddingStore.addAll(chunkIds, embeddings, textSegments);

                // 第三步：批量更新 MySQL 中对应 segment 的状态。
                // 使用一次 SQL 更新整批，避免逐条 UPDATE 造成的数据库连接抖动。
                int updated = segmentMapper.batchUpdateEmbedding(
                        chunkIds, SegmentStatus.VECTOR_STORED.value());
                if (updated != chunkIds.size()) {
                    throw new IllegalStateException(
                            "批量更新 segment 状态数量不一致: 期望="
                                    + chunkIds.size() + ", 实际=" + updated);
                }

                totalWritten += batch.size();
                offset = end;
                log.info("批次写入 ES 完成: docId={}, 已处理={}/{}, 批次={}条",
                        docId, offset, pending.size(), batch.size());
            }

            log.info("全部写入 ES 完成: docId={}, total={}", docId, totalWritten);

            // 原子迁移到最终状态
            if (documentMapper.compareAndSetStatusAndClearError(
                    docId, DocumentStatus.VECTOR_STORED, DocumentStatus.VECTORING) != 1) {
                throw new IllegalStateException("文档状态迁移到 vector_stored 失败: " + docId);
            }

            log.info("向量化完成: docId={}, count={}", docId, pending.size());
            return pending.size();
        } catch (Exception e) {
            log.error("向量化失败: docId={}", docId, e);
            documentMapper.compareAndSetStatusWithError(
                    docId,
                    currentStatus,
                    DocumentStatus.VECTORING,
                    truncateError("向量化失败: " + e.getMessage()));
            throw new RuntimeException("向量化失败: " + e.getMessage(), e);
        }
    }

    private String truncateError(String message) {
        if (message == null) {
            return "未知错误";
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000) + "...";
    }

    /**
     * 将 metadata JSON 反序列化为 Map，供 ES 写入时复用 splitter 产出的
     * 完整元数据（chunk_type、parent_chunk_id、header_path 等）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadataMap(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(metadataJson, Map.class);
        } catch (Exception e) {
            log.warn("metadata JSON 反序列化失败，降级为空 Map: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String downloadMarkdown(String convertedDocUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(convertedDocUrl))
                    .header("Accept", "text/markdown,text/plain,*/*")
                    .GET()
                    .build();
            HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Markdown 下载失败，HTTP status=" + response.statusCode());
            }

            String markdown =
                    new String(response.body(), StandardCharsets.UTF_8);
            if (markdown.isBlank()) {
                throw new IllegalStateException("下载到的 Markdown 内容为空");
            }
            return markdown;
        } catch (InterruptedException e) {
            // 恢复线程中断标记，避免上层线程池无法感知取消信号。
            Thread.currentThread().interrupt();
            throw new RuntimeException("Markdown 下载被中断", e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "读取转换后 Markdown 失败: " + convertedDocUrl, e);
        }
    }

    private List<KnowledgeSegment> convertSegments(
            String docId,
            List<TextSegment> textSegments) {
        List<KnowledgeSegment> segments =
                new ArrayList<>(textSegments.size());

        for (int index = 0; index < textSegments.size(); index++) {
            TextSegment textSegment = textSegments.get(index);
            Map<String, Object> metadata = textSegment.metadata().toMap();

            KnowledgeSegment segment = new KnowledgeSegment();
            segment.setChunkId(
                    textSegment.metadata().getString(
                            MarkdownHeaderParentTextSplitter.CHUNK_ID));
            segment.setText(textSegment.text());
            segment.setDocId(docId);
            segment.setChunkOrder(index);
            // 尚未向量化时没有 embedding_id，显式写空字符串以兼容表的非空约束。
            segment.setEmbeddingId("");
            segment.setSegmentStatus(SegmentStatus.INIT);
            segment.setMetadata(serializeMetadata(metadata));

            Integer skipEmbedding = textSegment.metadata().getInteger(
                    MarkdownHeaderParentTextSplitter.SKIP_EMBEDDING);
            segment.setSkipEmbedding(
                    skipEmbedding != null && skipEmbedding == 1);
            segments.add(segment);
        }
        return segments;
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            throw new RuntimeException("分片 metadata 序列化失败", e);
        }
    }

    private String currentStatusAfterExisting(KnowledgeDocument document) {
        return document.getDocumentStatus() == DocumentStatus.CONVERTED
                ? DocumentStatus.CHUNKED.value()
                : document.getDocStatus();
    }
}

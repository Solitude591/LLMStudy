package com.llmstudy.rag.service.impl;

import com.llmstudy.rag.dto.DocumentSplitResult;
import com.llmstudy.rag.entity.KnowledgeDocument;
import com.llmstudy.rag.entity.KnowledgeSegment;
import com.llmstudy.rag.enums.DocumentStatus;
import com.llmstudy.rag.enums.SegmentStatus;
import com.llmstudy.rag.mapper.KnowledgeDocumentMapper;
import com.llmstudy.rag.mapper.KnowledgeSegmentMapper;
import com.llmstudy.rag.service.DocumentSegmentService;
import com.llmstudy.rag.service.splitter.MarkdownHeaderParentTextSplitter;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    public DocumentSegmentServiceImpl(KnowledgeDocumentMapper documentMapper,
                                      KnowledgeSegmentMapper segmentMapper,
                                      MarkdownHeaderParentTextSplitter textSplitter,
                                      HttpClient httpClient,
                                      JsonMapper objectMapper) {
        this.documentMapper = documentMapper;
        this.segmentMapper = segmentMapper;
        this.textSplitter = textSplitter;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DocumentSplitResult splitDocument(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId 不能为空");
        }

        // 锁定文档行，使同一 docId 的并发请求串行执行，避免重复写入分片。
        KnowledgeDocument document = documentMapper.findByDocIdForUpdate(docId);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在: " + docId);
        }

        int existingCount = segmentMapper.countByDocId(docId);
        if (existingCount > 0) {
            // 历史数据存在分片但文档仍是 converted 时，顺便修正生命周期状态。
            if (document.getDocumentStatus() == DocumentStatus.CONVERTED) {
                documentMapper.updateStatus(docId, DocumentStatus.CHUNKED);
            }
            log.info("文档已经完成分片，跳过重复处理: docId={}, segmentCount={}",
                    docId, existingCount);
            return new DocumentSplitResult(
                    docId, existingCount, currentStatusAfterExisting(document), true);
        }

        // 只有已解析完成的 Markdown 才能进入分片；chunked 但无数据时允许补偿重建。
        DocumentStatus documentStatus = document.getDocumentStatus();
        if (documentStatus != DocumentStatus.CONVERTED
                && documentStatus != DocumentStatus.CHUNKED) {
            throw new IllegalStateException(
                    "文档尚未解析完成，当前状态: " + document.getDocStatus());
        }
        if (document.getConvertedDocUrl() == null
                || document.getConvertedDocUrl().isBlank()) {
            throw new IllegalStateException("文档 converted_doc_url 为空: " + docId);
        }

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

        List<KnowledgeSegment> segments =
                convertSegments(docId, textSegments);
        int inserted = segmentMapper.batchInsert(segments);
        if (inserted != segments.size()) {
            throw new IllegalStateException(
                    "分片保存数量不一致，期望=" + segments.size() + "，实际=" + inserted);
        }

        // 当前链路只保存文本分片，不调用 Embedding 模型，所有分片状态保持 init。
        if (documentMapper.updateStatus(docId, DocumentStatus.CHUNKED) != 1) {
            throw new IllegalStateException("更新文档分片状态失败: " + docId);
        }

        log.info("文档分片并入库完成: docId={}, segmentCount={}, status={}",
                docId, segments.size(), DocumentStatus.CHUNKED.value());
        return new DocumentSplitResult(
                docId, segments.size(), DocumentStatus.CHUNKED.value(), false);
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

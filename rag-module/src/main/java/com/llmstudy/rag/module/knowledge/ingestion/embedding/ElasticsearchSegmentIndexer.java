package com.llmstudy.rag.module.knowledge.ingestion.embedding;

import com.llmstudy.rag.config.MarkdownSplitterProperties;
import com.llmstudy.rag.entity.KnowledgeDocument;
import com.llmstudy.rag.entity.KnowledgeSegment;
import com.llmstudy.rag.enums.DocumentStatus;
import com.llmstudy.rag.enums.SegmentStatus;
import com.llmstudy.rag.mapper.KnowledgeDocumentMapper;
import com.llmstudy.rag.mapper.KnowledgeSegmentMapper;
import com.llmstudy.rag.module.knowledge.ingestion.DocumentStageAlreadyRunningException;
import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 在 Embedding Store 边界将持久化业务分片转换为 LangChain4j 类型的适配器。 */
@Service
public class ElasticsearchSegmentIndexer implements SegmentEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchSegmentIndexer.class);
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeSegmentMapper segmentMapper;
    private final OpenAiEmbeddingModel embeddingModel;
    private final ElasticsearchEmbeddingStore embeddingStore;
    private final MarkdownSplitterProperties properties;
    private final JsonMapper jsonMapper;

    public ElasticsearchSegmentIndexer(KnowledgeDocumentMapper documentMapper,
                                        KnowledgeSegmentMapper segmentMapper,
                                        OpenAiEmbeddingModel embeddingModel,
                                        ElasticsearchEmbeddingStore embeddingStore,
                                        MarkdownSplitterProperties properties,
                                        JsonMapper jsonMapper) {
        this.documentMapper = documentMapper;
        this.segmentMapper = segmentMapper;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    /** {@inheritDoc} */
    @Override
    public int embedSegments(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId 不能为空");
        }
        KnowledgeDocument document = documentMapper.findByDocId(docId);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在: " + docId);
        }
        DocumentStatus previous = document.getDocumentStatus();
        if (previous == DocumentStatus.VECTORING) {
            throw new DocumentStageAlreadyRunningException("文档向量化阶段已经被抢占: " + docId);
        }
        if (previous != DocumentStatus.CHUNKED && previous != DocumentStatus.VECTOR_STORED) {
            throw new IllegalStateException("文档状态不允许向量化，当前状态: " + previous.value());
        }
        // Mapper 只返回 INIT 且 skip_embedding=0 的分片，parent 不会被重复向量化。
        List<KnowledgeSegment> pending = segmentMapper.findPendingByDocId(docId);
        if (pending.isEmpty()) {
            if (previous == DocumentStatus.CHUNKED
                    && documentMapper.compareAndSetStatusAndClearError(docId,
                    DocumentStatus.VECTOR_STORED, DocumentStatus.CHUNKED) != 1) {
                throw new DocumentStageAlreadyRunningException("文档向量化状态已被修改: " + docId);
            }
            return 0;
        }
        // 先 CAS 抢占整个文档的向量化权，再进行外部 Embedding/ES 调用。
        if (documentMapper.compareAndSetStatus(docId, DocumentStatus.VECTORING, previous) != 1) {
            throw new DocumentStageAlreadyRunningException("文档向量化阶段已经被抢占: " + docId);
        }
        try {
            int batchSize = Math.max(1, properties.getEmbeddingBatchSize());
            // 每批向量先写 ES，成功后再更新对应 segment 状态，便于失败后继续补偿。
            for (int offset = 0; offset < pending.size(); offset += batchSize) {
                List<KnowledgeSegment> batch = pending.subList(offset,
                        Math.min(offset + batchSize, pending.size()));
                List<float[]> vectors = embeddingModel.embed(batch.stream()
                        .map(KnowledgeSegment::getText).toList());
                if (vectors.size() != batch.size()) {
                    throw new IllegalStateException("Embedding 模型返回数量与输入不符");
                }
                List<String> ids = new ArrayList<>(batch.size());
                List<Embedding> embeddings = new ArrayList<>(batch.size());
                List<TextSegment> segments = new ArrayList<>(batch.size());
                for (int index = 0; index < batch.size(); index++) {
                    KnowledgeSegment segment = batch.get(index);
                    ids.add(segment.getChunkId());
                    embeddings.add(Embedding.from(vectors.get(index)));
                    Map<String, Object> metadata = parseMetadata(segment.getMetadata());
                    metadata.put(SegmentMetadataKeys.DOC_ID, docId);
                    segments.add(TextSegment.from(segment.getText(), Metadata.from(metadata)));
                }
                embeddingStore.addAll(ids, embeddings, segments);
                int updated = segmentMapper.batchUpdateEmbedding(
                        ids, SegmentStatus.VECTOR_STORED.value());
                if (updated != ids.size()) {
                    throw new IllegalStateException("批量更新 segment 状态数量不一致");
                }
            }
            if (documentMapper.compareAndSetStatusAndClearError(docId,
                    DocumentStatus.VECTOR_STORED, DocumentStatus.VECTORING) != 1) {
                throw new IllegalStateException("文档状态迁移到 vector_stored 失败: " + docId);
            }
            return pending.size();
        } catch (Exception e) {
            log.error("向量化失败: docId={}", docId, e);
            documentMapper.compareAndSetStatusWithError(docId, previous,
                    DocumentStatus.VECTORING, truncate("向量化失败: " + e.getMessage()));
            throw new RuntimeException("向量化失败: " + e.getMessage(), e);
        }
    }

    /** 在 ES 适配器边界解析 metadata，单条损坏数据不阻断正文向量化。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(jsonMapper.readValue(json, Map.class));
        } catch (Exception e) {
            log.warn("metadata JSON 解析失败，使用空 metadata", e);
            return new LinkedHashMap<>();
        }
    }

    private static String truncate(String message) {
        return message != null && message.length() > 2000
                ? message.substring(0, 2000) + "..." : message;
    }
}

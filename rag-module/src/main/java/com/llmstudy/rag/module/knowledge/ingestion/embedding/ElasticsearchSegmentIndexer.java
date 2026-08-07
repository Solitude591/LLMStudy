package com.llmstudy.rag.module.knowledge.ingestion.embedding;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.llmstudy.rag.config.ElasticsearchProperties;
import com.llmstudy.rag.config.MarkdownSplitterProperties;
import com.llmstudy.rag.entity.KnowledgeDocumentVersion;
import com.llmstudy.rag.entity.KnowledgeSegment;
import com.llmstudy.rag.enums.DocumentStatus;
import com.llmstudy.rag.enums.SegmentStatus;
import com.llmstudy.rag.mapper.KnowledgeDocumentVersionMapper;
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
    private final KnowledgeDocumentVersionMapper versionMapper;
    private final KnowledgeSegmentMapper segmentMapper;
    private final OpenAiEmbeddingModel embeddingModel;
    private final ElasticsearchEmbeddingStore embeddingStore;
    private final ElasticsearchClient elasticsearchClient;
    private final ElasticsearchProperties elasticsearchProperties;
    private final MarkdownSplitterProperties properties;
    private final JsonMapper jsonMapper;

    public ElasticsearchSegmentIndexer(KnowledgeDocumentVersionMapper versionMapper,
                                        KnowledgeSegmentMapper segmentMapper,
                                        OpenAiEmbeddingModel embeddingModel,
                                        ElasticsearchEmbeddingStore embeddingStore,
                                        ElasticsearchClient elasticsearchClient,
                                        ElasticsearchProperties elasticsearchProperties,
                                        MarkdownSplitterProperties properties,
                                        JsonMapper jsonMapper) {
        this.versionMapper = versionMapper;
        this.segmentMapper = segmentMapper;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.elasticsearchClient = elasticsearchClient;
        this.elasticsearchProperties = elasticsearchProperties;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    /** {@inheritDoc} */
    @Override
    public int embedSegments(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("versionId 不能为空");
        }
        KnowledgeDocumentVersion version = versionMapper.findByVersionId(versionId);
        if (version == null) {
            throw new IllegalArgumentException("版本不存在: " + versionId);
        }
        DocumentStatus previous = version.getDocumentStatus();
        if (previous == DocumentStatus.VECTORING) {
            throw new DocumentStageAlreadyRunningException("版本向量化阶段已经被抢占: " + versionId);
        }
        // 异步事件可能重复投递；已经完成向量化的版本直接按幂等语义返回。
        if (previous == DocumentStatus.VECTOR_STORED) {
            return 0;
        }
        if (previous != DocumentStatus.CHUNKED) {
            throw new IllegalStateException("版本状态不允许向量化，当前状态: " + previous.value());
        }
        // Mapper 只返回 INIT 且 skip_embedding=0 的分片，parent 不会被重复向量化。
        List<KnowledgeSegment> pending = segmentMapper.findPendingByVersionId(versionId);
        if (pending.isEmpty()) {
            // 可能是纯标题文档，也可能是上次已写完 ES、但在 READY 前失败的补偿场景。
            if (versionMapper.compareAndSetProcessingStatus(
                    versionId, DocumentStatus.VECTORING, DocumentStatus.CHUNKED) != 1) {
                throw new DocumentStageAlreadyRunningException("版本向量化状态已被修改: " + versionId);
            }
            try {
                refreshIndex();
                if (versionMapper.markReady(versionId) != 1) {
                    throw new IllegalStateException("版本状态迁移到 ready 失败: " + versionId);
                }
                return 0;
            } catch (Exception e) {
                versionMapper.compareAndSetProcessingStatusWithError(
                        versionId, DocumentStatus.CHUNKED, DocumentStatus.VECTORING,
                        truncate("向量化收尾失败: " + e.getMessage()));
                throw new RuntimeException("向量化收尾失败: " + e.getMessage(), e);
            }
        }
        // 先 CAS 抢占整个版本的向量化权，再进行外部 Embedding/ES 调用。
        if (versionMapper.compareAndSetProcessingStatus(
                versionId, DocumentStatus.VECTORING, previous) != 1) {
            throw new DocumentStageAlreadyRunningException("版本向量化阶段已经被抢占: " + versionId);
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
                    Map<String, Object> metadata = buildElasticsearchMetadata(segment, version);
                    segments.add(TextSegment.from(segment.getText(), Metadata.from(metadata)));
                }
                embeddingStore.addAll(ids, embeddings, segments);
                int updated = segmentMapper.batchUpdateEmbedding(
                        ids, SegmentStatus.VECTOR_STORED.value());
                if (updated != ids.size()) {
                    throw new IllegalStateException("批量更新 segment 状态数量不一致");
                }
            }
            // READY 表示该版本已经可以被线上检索。显式刷新索引，消除 ES 默认近实时刷新
            // 带来的短暂不可见窗口，发布事务才可以安全地立即切换 current_version_id。
            refreshIndex();
            // 向量化成功后，版本进入 READY，等待手动发布。
            if (versionMapper.markReady(versionId) != 1) {
                throw new IllegalStateException("版本状态迁移到 ready 失败: " + versionId);
            }
            return pending.size();
        } catch (Exception e) {
            log.error("向量化失败: versionId={}", versionId, e);
            versionMapper.compareAndSetProcessingStatusWithError(versionId, previous,
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

    /**
     * ES 使用 strict mapping，只允许写入检索过滤、父分片回查和引用展示所需字段。
     * MySQL metadata 中的 chunk_type、skip_embedding、标题分级等字段不得透传到 ES。
     */
    private Map<String, Object> buildElasticsearchMetadata(
            KnowledgeSegment segment, KnowledgeDocumentVersion version) {
        if (!version.getDocId().equals(segment.getDocId())
                || !version.getVersionId().equals(segment.getVersionId())) {
            throw new IllegalStateException("分片归属与待向量化版本不一致: chunkId="
                    + segment.getChunkId());
        }

        Map<String, Object> source = parseMetadata(segment.getMetadata());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(SegmentMetadataKeys.DOC_ID, version.getDocId());
        metadata.put(SegmentMetadataKeys.VERSION_ID, version.getVersionId());
        copyIfPresent(source, metadata, SegmentMetadataKeys.PARENT_CHUNK_ID);
        copyIfPresent(source, metadata, SegmentMetadataKeys.HEADER_PATH);

        Object sourceUrl = source.get(SegmentMetadataKeys.SOURCE_URL);
        if (sourceUrl == null || sourceUrl.toString().isBlank()) {
            sourceUrl = version.getConvertedDocUrl();
        }
        if (sourceUrl != null && !sourceUrl.toString().isBlank()) {
            metadata.put(SegmentMetadataKeys.SOURCE_URL, sourceUrl.toString());
        }
        return metadata;
    }

    private static void copyIfPresent(Map<String, Object> source,
                                      Map<String, Object> target,
                                      String key) {
        Object value = source.get(key);
        if (value != null && !value.toString().isBlank()) {
            target.put(key, value.toString());
        }
    }

    private static String truncate(String message) {
        return message != null && message.length() > 2000
                ? message.substring(0, 2000) + "..." : message;
    }

    private void refreshIndex() throws Exception {
        elasticsearchClient.indices().refresh(
                request -> request.index(elasticsearchProperties.getIndexName()));
    }
}

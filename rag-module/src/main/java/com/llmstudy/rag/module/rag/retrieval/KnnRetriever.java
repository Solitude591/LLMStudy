package com.llmstudy.rag.module.rag.retrieval;

import com.llmstudy.rag.entity.KnowledgeSegment;
import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** KNN 语义检索适配器，支持将命中子分片替换为完整父分片。 */
@Component
public class KnnRetriever {

    private static final Logger log = LoggerFactory.getLogger(KnnRetriever.class);
    private static final int RESULTS_PER_CHANNEL = 5;
    private final OpenAiEmbeddingModel embeddingModel;
    private final ElasticsearchEmbeddingStore embeddingStore;
    private final ParentChunkResolver parentResolver;
    private final JsonMapper jsonMapper;

    public KnnRetriever(OpenAiEmbeddingModel embeddingModel,
                        ElasticsearchEmbeddingStore embeddingStore,
                        ParentChunkResolver parentResolver,
                        JsonMapper jsonMapper) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.parentResolver = parentResolver;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 对改写问题向量化后执行 KNN，并对子分片做父分片回查与去重。
     *
     * @param rewrittenQuestion 适合独立语义检索的改写问题
     * @return 按向量相似度排列的项目自有候选
     */
    public List<RetrievalCandidate> retrieve(String rewrittenQuestion) {
        float[] vector = embeddingModel.embed(rewrittenQuestion);
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(Embedding.from(vector))
                        .maxResults(RESULTS_PER_CHANNEL)
                        .minScore(0.0)
                        .build()).matches();
        List<RetrievalCandidate> candidates = new ArrayList<>();
        // 请求级缓存避免多个子分片指向同一父分片时重复访问 Redis/MySQL。
        Map<String, KnowledgeSegment> requestCache = new HashMap<>();
        Set<String> emitted = new HashSet<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            TextSegment segment = match.embedded();
            if (match.embeddingId() == null || match.embeddingId().isBlank()
                    || segment == null || segment.text() == null || segment.text().isBlank()) {
                log.warn("KNN 命中缺少 embeddingId 或文本，已跳过");
                continue;
            }
            String id = match.embeddingId();
            String text = segment.text();
            Map<String, Object> metadata = new LinkedHashMap<>(segment.metadata().toMap());
            Object parentValue = metadata.get(SegmentMetadataKeys.PARENT_CHUNK_ID);
            String parentId = parentValue == null ? null : parentValue.toString().trim();
            if (parentId != null && !parentId.isBlank()) {
                // 父分片回查失败时保留原子分片，不因上下文增强失败丢失可用命中。
                KnowledgeSegment parent = parentResolver.resolve(parentId, requestCache);
                if (parent != null && parent.getText() != null && !parent.getText().isBlank()) {
                    id = parent.getChunkId();
                    text = parent.getText();
                    metadata = parseMetadata(parent.getMetadata());
                    if (parent.getDocId() != null) {
                        metadata.put(SegmentMetadataKeys.DOC_ID, parent.getDocId());
                    }
                }
            }
            // 多个子分片可能替换为同一父分片，只保留首次（最高分）命中。
            if (emitted.add(id)) {
                candidates.add(new RetrievalCandidate(id, text, metadata,
                        match.score() == null ? 0.0 : match.score(), null));
            }
        }
        return candidates;
    }

    /** 将 MySQL 中的 metadata JSON 恢复为可变 Map，便于补充共享元数据键。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadata(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(jsonMapper.readValue(json, Map.class));
        } catch (Exception e) {
            log.warn("父分片 metadata 解析失败，使用空 metadata", e);
            return new LinkedHashMap<>();
        }
    }
}

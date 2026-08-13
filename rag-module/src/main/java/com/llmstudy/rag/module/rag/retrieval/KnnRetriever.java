package com.llmstudy.rag.module.rag.retrieval;

import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量近邻检索适配器。
 *
 * <p>只返回 child/standalone 命中；parent 展开统一延迟到融合重排之后。
 * 中英文查询由上层一次 {@link #embedAll(List)} 后再分别 {@link #search}。</p>
 */
@Component
public class KnnRetriever {

    private static final Logger log = LoggerFactory.getLogger(KnnRetriever.class);

    private final OpenAiEmbeddingModel embeddingModel;
    private final ElasticsearchEmbeddingStore embeddingStore;

    public KnnRetriever(OpenAiEmbeddingModel embeddingModel,
                        ElasticsearchEmbeddingStore embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    /**
     * 单查询兼容入口，主要供单测使用。
     *
     * @param question 语义检索文本
     * @return 按向量相似度排序的候选
     */
    public List<RetrievalCandidate> retrieve(String question) {
        return search(embeddingModel.embed(question), null, 10);
    }

    /**
     * 一次批量编码全部查询，保证中英文 KNN 只打一次 embedding 接口。
     *
     * @param queries 已去重的查询文本，顺序与返回向量一一对应
     * @return 与输入等长的向量列表
     */
    public List<float[]> embedAll(List<String> queries) {
        List<float[]> vectors = embeddingModel.embed(queries);
        if (vectors == null || vectors.size() != queries.size()) {
            throw new IllegalStateException("Embedding 模型返回数量与输入不符");
        }
        return vectors;
    }

    /**
     * 用已编码向量检索 standalone/child 命中。
     *
     * @param vector            查询向量
     * @param currentVersionIds 当前可读版本；{@code null} 不加过滤
     * @param topK              本路最多返回条数
     * @return 原始 KNN 命中，分数写入 {@code rawScore}
     */
    public List<RetrievalCandidate> search(float[] vector, List<String> currentVersionIds,
                                           int topK) {
        if (currentVersionIds != null && currentVersionIds.isEmpty()) {
            return List.of();
        }
        EmbeddingSearchRequest.EmbeddingSearchRequestBuilder request =
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(Embedding.from(vector))
                        .maxResults(Math.max(1, topK))
                        .minScore(0.0);
        if (currentVersionIds != null) {
            request.filter(MetadataFilterBuilder
                    .metadataKey(SegmentMetadataKeys.VERSION_ID)
                    .isIn(currentVersionIds));
        }
        List<EmbeddingMatch<TextSegment>> matches =
                embeddingStore.search(request.build()).matches();
        List<RetrievalCandidate> candidates = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            TextSegment segment = match.embedded();
            if (match.embeddingId() == null || match.embeddingId().isBlank()
                    || segment == null || segment.text() == null || segment.text().isBlank()) {
                log.warn("KNN 命中缺少 embeddingId 或文本，已跳过");
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>(segment.metadata().toMap());
            candidates.add(new RetrievalCandidate(
                    match.embeddingId(),
                    segment.text(),
                    metadata,
                    match.score() == null ? 0.0 : match.score(),
                    null));
        }
        return candidates;
    }
}

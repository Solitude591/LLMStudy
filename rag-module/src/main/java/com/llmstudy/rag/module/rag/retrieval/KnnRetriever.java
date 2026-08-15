package com.llmstudy.rag.module.rag.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.llmstudy.rag.config.ElasticsearchProperties;
import com.llmstudy.rag.enums.DocumentLanguage;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RetrievalQueryPlan;
import dev.langchain4j.store.embedding.elasticsearch.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 向量近邻检索适配器。
 *
 * <p>只返回 child/standalone 命中；parent 展开统一延迟到融合重排之后。
 * 中英文查询一次批量 embedding，并合并进单次 ES KNN 请求。</p>
 */
@Component
public class KnnRetriever {

    private static final Logger log = LoggerFactory.getLogger(KnnRetriever.class);

    private final OpenAiEmbeddingModel embeddingModel;
    private final ElasticsearchClient client;
    private final ElasticsearchProperties properties;

    public KnnRetriever(OpenAiEmbeddingModel embeddingModel,
                        ElasticsearchClient client,
                        ElasticsearchProperties properties) {
        this.embeddingModel = embeddingModel;
        this.client = client;
        this.properties = properties;
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
        try {
            return search(List.of(knn(vector, currentVersionIds, null, 1.0f, topK)), topK);
        } catch (IOException e) {
            throw new IllegalStateException("KNN Elasticsearch 检索失败", e);
        }
    }

    /**
     * 一次批量 embedding，随后用单次 ES Search API 完成语言感知 KNN。
     */
    public List<RetrievalCandidate> retrieve(RetrievalQueryPlan plan,
                                             List<String> currentVersionIds,
                                             int topK) throws IOException {
        if (currentVersionIds != null && currentVersionIds.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = embedAll(plan.uniqueQueries());
        if (plan.duplicateLanguage()) {
            return search(List.of(knn(vectors.getFirst(), currentVersionIds,
                    null, 1.0f, topK)), topK);
        }
        List<KnnSearch> searches = List.of(
                knn(vectors.get(0), currentVersionIds, DocumentLanguage.ZH, 1.0f, topK),
                knn(vectors.get(1), currentVersionIds, DocumentLanguage.EN, 1.0f, topK),
                knn(vectors.get(0), currentVersionIds,
                        DocumentLanguage.UNKNOWN, 0.5f, topK),
                knn(vectors.get(1), currentVersionIds,
                        DocumentLanguage.UNKNOWN, 0.5f, topK));
        return search(searches, topK);
    }

    private List<RetrievalCandidate> search(List<KnnSearch> searches, int topK)
            throws IOException {
        SearchResponse<Document> response = client.search(request -> request
                        .index(properties.getIndexName())
                        .knn(searches)
                        .size(Math.max(1, topK)),
                Document.class);
        List<RetrievalCandidate> candidates = new ArrayList<>();
        for (Hit<Document> hit : response.hits().hits()) {
            Document source = hit.source();
            if (hit.id() == null || hit.id().isBlank() || source == null
                    || source.getText() == null || source.getText().isBlank()) {
                log.warn("KNN 命中缺少 _id、_source 或文本，已跳过: {}", hit.id());
                continue;
            }
            candidates.add(new RetrievalCandidate(
                    hit.id(),
                    source.getText(),
                    source.getMetadata() == null ? Map.of() : source.getMetadata(),
                    hit.score() == null ? 0.0 : hit.score(),
                    null));
        }
        return candidates;
    }

    private static KnnSearch knn(float[] vector,
                                 List<String> currentVersionIds,
                                 DocumentLanguage language,
                                 float boost,
                                 int topK) {
        List<Query> filters = new ArrayList<>();
        if (currentVersionIds != null) {
            filters.add(terms("metadata.version_id.keyword", currentVersionIds));
        }
        if (language != null) {
            filters.add(terms("metadata.language", List.of(language.value())));
        }
        int k = Math.max(1, topK);
        return KnnSearch.of(search -> search
                .field("vector")
                .queryVector(toList(vector))
                .k(k)
                .numCandidates(Math.max(100, k * 10))
                .boost(boost)
                .filter(filters));
    }

    private static Query terms(String field, List<String> values) {
        return Query.of(query -> query.terms(terms -> terms
                .field(field)
                .terms(value -> value.value(values.stream().map(FieldValue::of).toList()))));
    }

    private static List<Float> toList(float[] vector) {
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }
}

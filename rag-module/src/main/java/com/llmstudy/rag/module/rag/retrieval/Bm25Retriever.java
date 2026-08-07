package com.llmstudy.rag.module.rag.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.llmstudy.rag.config.ElasticsearchProperties;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import dev.langchain4j.store.embedding.elasticsearch.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Elasticsearch BM25 词面检索适配器。 */
@Component
public class Bm25Retriever {

    private static final Logger log = LoggerFactory.getLogger(Bm25Retriever.class);
    private static final int RESULTS_PER_CHANNEL = 5;
    private final ElasticsearchClient client;
    private final ElasticsearchProperties properties;

    public Bm25Retriever(ElasticsearchClient client, ElasticsearchProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * 使用用户原问题检索 text 字段，保留专有名词与精确字面匹配。
     *
     * @param originalQuestion 未改写的用户问题
     * @return 按 Elasticsearch 相关分数排序的候选
     * @throws IOException Elasticsearch 请求失败
     */
    public List<RetrievalCandidate> retrieve(String originalQuestion) throws IOException {
        return search(originalQuestion, null);
    }

    /** 仅在当前已发布版本集合内执行词面检索。 */
    public List<RetrievalCandidate> retrieve(String originalQuestion,
                                             List<String> currentVersionIds) throws IOException {
        if (currentVersionIds == null || currentVersionIds.isEmpty()) {
            return List.of();
        }
        return search(originalQuestion, currentVersionIds);
    }

    private List<RetrievalCandidate> search(String originalQuestion,
                                            List<String> currentVersionIds) throws IOException {
        SearchResponse<Document> response = client.search(request -> request
                        .index(properties.getIndexName())
                        .query(query -> currentVersionIds == null
                                ? query.match(match -> match
                                        .field("text").query(originalQuestion))
                                : query.bool(bool -> bool
                                        .must(must -> must.match(match -> match
                                                .field("text").query(originalQuestion)))
                                        .filter(filter -> filter.terms(terms -> terms
                                                .field("metadata.version_id.keyword")
                                                .terms(values -> values.value(currentVersionIds.stream()
                                                        .map(FieldValue::of)
                                                        .toList()))))))
                        .size(RESULTS_PER_CHANNEL),
                Document.class);
        List<RetrievalCandidate> candidates = new ArrayList<>();
        // 在适配器边界过滤不完整 ES 文档，不让无效候选流入融合阶段。
        for (Hit<Document> hit : response.hits().hits()) {
            Document source = hit.source();
            if (hit.id() == null || hit.id().isBlank() || source == null
                    || source.getText() == null || source.getText().isBlank()) {
                log.warn("BM25 命中缺少 _id、_source 或文本，已跳过: {}", hit.id());
                continue;
            }
            candidates.add(new RetrievalCandidate(hit.id(), source.getText(),
                    source.getMetadata() == null ? Map.of() : source.getMetadata(),
                    hit.score() == null ? 0.0 : hit.score(), null));
        }
        return candidates;
    }
}

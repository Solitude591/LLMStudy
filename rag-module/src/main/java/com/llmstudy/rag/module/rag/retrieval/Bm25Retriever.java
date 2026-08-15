package com.llmstudy.rag.module.rag.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
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
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Elasticsearch BM25 词面检索适配器。 */
@Component
public class Bm25Retriever {

    private static final Logger log = LoggerFactory.getLogger(Bm25Retriever.class);
    private final ElasticsearchClient client;
    private final ElasticsearchProperties properties;

    public Bm25Retriever(ElasticsearchClient client, ElasticsearchProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * 无版本过滤的兼容入口，主要供单测使用。
     *
     * @param question 检索文本
     * @return 按 Elasticsearch 相关分数排序的候选
     */
    public List<RetrievalCandidate> retrieve(String question) throws IOException {
        return search(question, null, 10);
    }

    /**
     * 在指定已发布版本集合内执行词面检索。
     *
     * @param question          中文或英文独立查询
     * @param currentVersionIds 当前可读版本快照；{@code null} 表示不加版本过滤
     * @param topK              本路最多返回条数
     * @return 原始 BM25 命中；空版本集合由上层短路，此处再防一层
     */
    public List<RetrievalCandidate> retrieve(String question, List<String> currentVersionIds,
                                             int topK) throws IOException {
        if (currentVersionIds != null && currentVersionIds.isEmpty()) {
            return List.of();
        }
        return search(question, currentVersionIds, topK);
    }

    /**
     * 单次 ES 请求内按文档语言选择中英文 query。
     *
     * <p>ZH 文档只用中文 query，EN 文档只用英文 query。
     * UNKNOWN 同时接受两者，但每个分支权重减半，避免因命中两个
     * should 分支获得不合理的双倍加成。</p>
     */
    public List<RetrievalCandidate> retrieve(RetrievalQueryPlan plan,
                                             List<String> currentVersionIds,
                                             int topK) throws IOException {
        if (currentVersionIds != null && currentVersionIds.isEmpty()) {
            return List.of();
        }
        if (plan.duplicateLanguage()) {
            return search(plan.standaloneZh(), currentVersionIds, topK);
        }
        Query languageAware = Query.of(query -> query.bool(bool -> bool
                .should(languageBranch(plan.standaloneZh(), DocumentLanguage.ZH, 1.0f))
                .should(languageBranch(plan.standaloneEn(), DocumentLanguage.EN, 1.0f))
                .should(languageBranch(plan.standaloneZh(), DocumentLanguage.UNKNOWN, 0.5f))
                .should(languageBranch(plan.standaloneEn(), DocumentLanguage.UNKNOWN, 0.5f))
                .minimumShouldMatch("1")));
        return search(languageAware, currentVersionIds, topK);
    }

    /**
     * 对 text 字段做 match 查询。
     *
     * <p>{@code currentVersionIds == null} 时不加 filter，保持单测和独立调用语义。
     * 有快照时必须用同一份 version_id 集合，避免和 KNN 权限漂移。</p>
     */
    private List<RetrievalCandidate> search(String question, List<String> currentVersionIds,
                                            int topK) throws IOException {
        Query match = Query.of(query -> query.match(value -> value
                .field("text").query(question)));
        return search(match, currentVersionIds, topK);
    }

    private List<RetrievalCandidate> search(Query contentQuery,
                                            List<String> currentVersionIds,
                                            int topK) throws IOException {
        SearchResponse<Document> response = client.search(request -> request
                        .index(properties.getIndexName())
                        .query(currentVersionIds == null
                                ? contentQuery
                                : Query.of(query -> query.bool(bool -> bool
                                        .must(contentQuery)
                                        .filter(terms("metadata.version_id.keyword",
                                                currentVersionIds)))))
                        .size(Math.max(1, topK)),
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

    private static Query languageBranch(String text, DocumentLanguage language, float boost) {
        return Query.of(query -> query.bool(bool -> bool
                .must(must -> must.match(match -> match.field("text").query(text)))
                .filter(terms("metadata.language", List.of(language.value())))
                .boost(boost)));
    }

    private static Query terms(String field, List<String> values) {
        return Query.of(query -> query.terms(terms -> terms
                .field(field)
                .terms(value -> value.value(values.stream().map(FieldValue::of).toList()))));
    }
}

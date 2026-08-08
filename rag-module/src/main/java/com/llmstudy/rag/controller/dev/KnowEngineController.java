package com.llmstudy.rag.controller.dev;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.llmstudy.rag.config.ElasticsearchProperties;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@RestController
@Profile("dev")
@RequestMapping("/knowengine")
@SaCheckRole("SYS_ADMIN")
public class KnowEngineController {

    private static final String FOOTBALL_TEST_ID = "know-engine-test-football";
    private static final String WEATHER_TEST_ID = "know-engine-test-weather";

    private final RestClient restClient;
    private final ElasticsearchEmbeddingStore embeddingStore;
    private final OpenAiEmbeddingModel openAiEmbeddingModel;
    private final ElasticsearchProperties elasticsearchProperties;

    public KnowEngineController(RestClient restClient,
                                ElasticsearchEmbeddingStore embeddingStore,
                                OpenAiEmbeddingModel openAiEmbeddingModel,
                                ElasticsearchProperties elasticsearchProperties) {
        this.restClient = restClient;
        this.embeddingStore = embeddingStore;
        this.openAiEmbeddingModel = openAiEmbeddingModel;
        this.elasticsearchProperties = elasticsearchProperties;
    }

    @GetMapping("/adder")
    public Map<String, Object> adder(
            @RequestParam(defaultValue = "What is your favourite sport?") String query) throws IOException {

        TextSegment footballSegment = TextSegment.from(
                "I like football.",
                Metadata.from(Map.of("version", "1", "source", "es-test")));
        TextSegment weatherSegment = TextSegment.from(
                "The weather is good today.",
                Metadata.from(Map.of("version", "2", "source", "es-test")));

        Embedding footballEmbedding =
                Embedding.from(openAiEmbeddingModel.embed(footballSegment.text()));
        Embedding weatherEmbedding =
                Embedding.from(openAiEmbeddingModel.embed(weatherSegment.text()));

        // 使用固定 ID 覆盖测试数据，重复调用接口不会持续制造重复文档。
        embeddingStore.addAll(
                List.of(FOOTBALL_TEST_ID, WEATHER_TEST_ID),
                List.of(footballEmbedding, weatherEmbedding),
                List.of(footballSegment, weatherSegment));

        // ES 默认近实时刷新，主动 refresh 后即可在同一个请求内验证刚写入的数据。
        restClient.performRequest(new Request(
                "POST",
                "/" + elasticsearchProperties.getIndexName() + "/_refresh"));

        Embedding queryEmbedding = Embedding.from(openAiEmbeddingModel.embed(query));
        Filter version = metadataKey("version").isEqualTo("1");
        EmbeddingSearchResult<TextSegment> relevant = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(5)
                        .minScore(0.0)
                        .filter(version)
                        .build());

        List<Map<String, Object>> matches = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : relevant.matches()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("embeddingId", match.embeddingId());
            item.put("score", match.score());
            item.put("text", match.embedded() == null ? null : match.embedded().text());
            item.put("metadata", match.embedded() == null
                    ? Map.of()
                    : match.embedded().metadata().toMap());
            matches.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("indexName", elasticsearchProperties.getIndexName());
        result.put("query", query);
        result.put("embeddingDimensions", queryEmbedding.dimension());
        result.put("writtenIds", List.of(FOOTBALL_TEST_ID, WEATHER_TEST_ID));
        result.put("filter", "version = 1");
        result.put("matchCount", matches.size());
        result.put("matches", matches);
        return result;
    }
}

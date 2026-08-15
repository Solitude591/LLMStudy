package com.llmstudy.rag.module.rag.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ShardStatistics;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.TotalHits;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import co.elastic.clients.util.ObjectBuilder;
import com.llmstudy.rag.config.ElasticsearchProperties;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RetrievalQueryPlan;
import dev.langchain4j.store.embedding.elasticsearch.Document;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiEmbeddingModel;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnnRetrieverTest {

    @Test
    void languageAwareQueriesUseOneEmbeddingBatchAndOneElasticsearchRequest()
            throws Exception {
        OpenAiEmbeddingModel embedding = mock(OpenAiEmbeddingModel.class);
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        ElasticsearchProperties properties = new ElasticsearchProperties();
        properties.setIndexName("knowledge");
        RetrievalQueryPlan plan = new RetrievalQueryPlan("q", "中文", "English");
        when(embedding.embed(List.of("中文", "English")))
                .thenReturn(List.of(new float[]{0.1f}, new float[]{0.2f}));
        AtomicReference<SearchRequest> captured = new AtomicReference<>();
        when(client.search(anySearchFunction(), eq(Document.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>> builder =
                    invocation.getArgument(0);
            captured.set(builder.apply(new SearchRequest.Builder()).build());
            return response(new Hit.Builder<Document>()
                    .index("knowledge").id("c-1").score(0.9)
                    .source(document("child text", Map.of("language", "EN")))
                    .build());
        });

        List<RetrievalCandidate> result = new KnnRetriever(embedding, client, properties)
                .retrieve(plan, List.of("v-1"), 10);

        verify(embedding).embed(List.of("中文", "English"));
        verify(client).search(anySearchFunction(), eq(Document.class));
        assertEquals(4, captured.get().knn().size());
        assertTrue(captured.get().knn().stream().allMatch(knn -> knn.filter().size() == 2));
        String requestJson = captured.get().toString();
        assertTrue(requestJson.contains("metadata.language"));
        assertTrue(requestJson.contains("metadata.version_id.keyword"));
        assertTrue(requestJson.contains("ZH"));
        assertTrue(requestJson.contains("EN"));
        assertTrue(requestJson.contains("UNKNOWN"));
        assertEquals("c-1", result.getFirst().id());
        assertEquals("child text", result.getFirst().text());
    }

    @SuppressWarnings("unchecked")
    private static Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>
    anySearchFunction() {
        return any();
    }

    private static SearchResponse<Document> response(Hit<Document> hit) {
        return new SearchResponse.Builder<Document>()
                .took(1).timedOut(false)
                .shards(new ShardStatistics.Builder().total(1).successful(1)
                        .skipped(0).failed(0).build())
                .hits(new HitsMetadata.Builder<Document>()
                        .total(new TotalHits.Builder().value(1)
                                .relation(TotalHitsRelation.Eq).build())
                        .hits(List.of(hit)).build())
                .build();
    }

    private static Document document(String text, Map<String, Object> metadata) {
        try {
            Constructor<Document> constructor = Document.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            Document document = constructor.newInstance();
            document.setText(text);
            document.setMetadata(metadata);
            return document;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}

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
import dev.langchain4j.store.embedding.elasticsearch.Document;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Bm25RetrieverTest {

    @Test
    void mapsElasticsearchHitToBusinessCandidate() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        ElasticsearchProperties properties = new ElasticsearchProperties();
        properties.setIndexName("knowledge");
        when(client.search(anySearchFunction(), eq(Document.class)))
                .thenReturn(response(new Hit.Builder<Document>()
                        .index("knowledge").id("c-1").score(2.5)
                        .source(document("evidence", Map.of("doc_id", "d-1")))
                        .build()));

        var candidates = new Bm25Retriever(client, properties).retrieve("question");

        assertEquals("c-1", candidates.getFirst().id());
        assertEquals("evidence", candidates.getFirst().text());
        assertEquals("d-1", candidates.getFirst().metadata().get("doc_id"));
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

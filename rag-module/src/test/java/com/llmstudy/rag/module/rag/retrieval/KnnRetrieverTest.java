package com.llmstudy.rag.module.rag.retrieval;

import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiEmbeddingModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnnRetrieverTest {

    @Test
    void returnsChildHitsWithoutParentExpansion() {
        OpenAiEmbeddingModel embedding = mock(OpenAiEmbeddingModel.class);
        ElasticsearchEmbeddingStore store = mock(ElasticsearchEmbeddingStore.class);
        when(embedding.embed("rewritten")).thenReturn(new float[]{0.1f});
        when(store.search(any())).thenReturn(new EmbeddingSearchResult<>(List.of(
                new EmbeddingMatch<>(0.9, "c-1", null, TextSegment.from("child text")))));

        List<RetrievalCandidate> result = new KnnRetriever(embedding, store)
                .retrieve("rewritten");

        assertEquals(1, result.size());
        assertEquals("c-1", result.getFirst().id());
        assertEquals("child text", result.getFirst().text());
    }
}

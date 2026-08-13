package com.llmstudy.rag.module.rag.retrieval;

import com.llmstudy.rag.auth.model.AccessContext;
import com.llmstudy.rag.auth.model.UserRole;
import com.llmstudy.rag.config.RetrievalProperties;
import com.llmstudy.rag.mapper.KnowledgeDocumentMapper;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RetrievalQueryPlan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridRetrieverTest {

    @Test
    void singleLaneFailureContinuesOtherLanes() throws Exception {
        Bm25Retriever bm25 = mock(Bm25Retriever.class);
        KnnRetriever knn = mock(KnnRetriever.class);
        when(bm25.retrieve(eq("zh"), isNull(), anyInt())).thenThrow(new IOException("down"));
        when(bm25.retrieve(eq("en"), isNull(), anyInt())).thenReturn(List.of(candidate("en-bm25")));
        when(knn.embedAll(List.of("zh", "en")))
                .thenReturn(List.of(new float[]{0.1f}, new float[]{0.2f}));
        when(knn.search(any(), isNull(), anyInt()))
                .thenReturn(List.of(candidate("zh-knn")))
                .thenReturn(List.of());

        HybridRetriever.RetrievalResult result = retriever(bm25, knn)
                .retrieve(new RetrievalQueryPlan("q", "zh", "en"));

        assertTrue(result.zhBm25().failed());
        assertFalse(result.bm25Degraded());
        assertEquals(List.of("en-bm25"), result.enBm25().hits().stream()
                .map(RetrievalCandidate::id).toList());
        assertEquals(3, result.successful().size());
    }

    @Test
    void allLanesFailRaiseRetrievalFailure() throws Exception {
        Bm25Retriever bm25 = mock(Bm25Retriever.class);
        KnnRetriever knn = mock(KnnRetriever.class);
        when(bm25.retrieve(eq("zh"), isNull(), anyInt())).thenThrow(new IOException("bm25"));
        when(bm25.retrieve(eq("en"), isNull(), anyInt())).thenThrow(new IOException("bm25-en"));
        when(knn.embedAll(anyList())).thenThrow(new IllegalStateException("embed"));

        assertThrows(IllegalStateException.class, () -> retriever(bm25, knn)
                .retrieve(new RetrievalQueryPlan("q", "zh", "en")));
    }

    @Test
    void duplicateLanguageSkipsEnglishLanesAndEmbedsOnce() throws Exception {
        Bm25Retriever bm25 = mock(Bm25Retriever.class);
        KnnRetriever knn = mock(KnnRetriever.class);
        when(bm25.retrieve(eq("same"), isNull(), anyInt())).thenReturn(List.of(candidate("b")));
        when(knn.embedAll(List.of("same"))).thenReturn(List.of(new float[]{0.3f}));
        when(knn.search(any(), isNull(), anyInt()))
                .thenReturn(List.of(candidate("k")));

        HybridRetriever.RetrievalResult result = retriever(bm25, knn)
                .retrieve(new RetrievalQueryPlan("same", "same", "same"));

        assertTrue(result.enBm25().skipped());
        assertTrue(result.enKnn().skipped());
        assertEquals(2, result.successful().size());
        verify(knn).embedAll(List.of("same"));
    }

    @Test
    void authenticatedAccessContextFiltersAllLanesWithSameVersionSnapshot()
            throws Exception {
        Bm25Retriever bm25 = mock(Bm25Retriever.class);
        KnnRetriever knn = mock(KnnRetriever.class);
        KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
        AccessContext actor = new AccessContext("alice", "org-a", UserRole.USER);
        List<String> versions = List.of("v-private", "v-org", "v-public");
        when(documents.findAccessibleCurrentVersionIds("alice", "org-a", false))
                .thenReturn(versions);
        when(bm25.retrieve(eq("zh"), eq(versions), anyInt())).thenReturn(List.of());
        when(bm25.retrieve(eq("en"), eq(versions), anyInt())).thenReturn(List.of());
        when(knn.embedAll(List.of("zh", "en")))
                .thenReturn(List.of(new float[]{0.1f}, new float[]{0.2f}));
        when(knn.search(any(), eq(versions), anyInt())).thenReturn(List.of());

        new HybridRetriever(bm25, knn, documents, new RetrievalProperties())
                .retrieve(new RetrievalQueryPlan("q", "zh", "en"), actor);

        verify(bm25).retrieve("zh", versions, 10);
        verify(bm25).retrieve("en", versions, 10);
        verify(knn).embedAll(List.of("zh", "en"));
    }

    private static HybridRetriever retriever(Bm25Retriever bm25, KnnRetriever knn) {
        return new HybridRetriever(bm25, knn, new RetrievalProperties());
    }

    private static RetrievalCandidate candidate(String id) {
        return new RetrievalCandidate(id, "text", Map.of(), 1.0, null);
    }
}

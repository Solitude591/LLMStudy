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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridRetrieverTest {

    private static final RetrievalQueryPlan PLAN =
            new RetrievalQueryPlan("q", "zh", "en");

    @Test
    void singleLaneFailureContinuesOtherLane() throws Exception {
        Bm25Retriever bm25 = mock(Bm25Retriever.class);
        KnnRetriever knn = mock(KnnRetriever.class);
        when(bm25.retrieve(eq(PLAN), isNull(), anyInt()))
                .thenThrow(new IOException("down"));
        when(knn.retrieve(eq(PLAN), isNull(), anyInt()))
                .thenReturn(List.of(candidate("knn")));

        HybridRetriever.RetrievalResult result = retriever(bm25, knn).retrieve(PLAN);

        assertEquals(List.of("bm25", "knn"), result.lanes().stream()
                .map(HybridRetriever.Lane::channel).toList());
        assertTrue(result.bm25().failed());
        assertFalse(result.knn().failed());
        assertFalse(result.knnDegraded());
        assertEquals(List.of("knn"), result.knn().hits().stream()
                .map(RetrievalCandidate::id).toList());
        assertEquals(1, result.successful().size());
    }

    @Test
    void bothLanesFailRaiseRetrievalFailure() throws Exception {
        Bm25Retriever bm25 = mock(Bm25Retriever.class);
        KnnRetriever knn = mock(KnnRetriever.class);
        when(bm25.retrieve(eq(PLAN), isNull(), anyInt()))
                .thenThrow(new IOException("bm25"));
        when(knn.retrieve(eq(PLAN), isNull(), anyInt()))
                .thenThrow(new IOException("knn"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> retriever(bm25, knn).retrieve(PLAN));
        assertEquals(2, failure.getSuppressed().length);
    }

    @Test
    void authenticatedAccessContextFiltersBothLanesWithSameVersionSnapshot()
            throws Exception {
        Bm25Retriever bm25 = mock(Bm25Retriever.class);
        KnnRetriever knn = mock(KnnRetriever.class);
        KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
        AccessContext actor = new AccessContext("alice", "org-a", UserRole.USER);
        List<String> versions = List.of("v-private", "v-org", "v-public");
        when(documents.findAccessibleCurrentVersionIds("alice", "org-a", false))
                .thenReturn(versions);
        when(bm25.retrieve(PLAN, versions, 10)).thenReturn(List.of());
        when(knn.retrieve(PLAN, versions, 10)).thenReturn(List.of());

        new HybridRetriever(bm25, knn, documents, new RetrievalProperties(), Runnable::run)
                .retrieve(PLAN, actor);

        verify(bm25).retrieve(PLAN, versions, 10);
        verify(knn).retrieve(PLAN, versions, 10);
    }

    @Test
    void bm25AndKnnRunConcurrently() throws Exception {
        Bm25Retriever bm25 = mock(Bm25Retriever.class);
        KnnRetriever knn = mock(KnnRetriever.class);
        CountDownLatch bothStarted = new CountDownLatch(2);
        when(bm25.retrieve(eq(PLAN), isNull(), anyInt())).thenAnswer(invocation -> {
            bothStarted.countDown();
            assertTrue(bothStarted.await(2, TimeUnit.SECONDS));
            return List.of(candidate("b"));
        });
        when(knn.retrieve(eq(PLAN), isNull(), anyInt())).thenAnswer(invocation -> {
            bothStarted.countDown();
            assertTrue(bothStarted.await(2, TimeUnit.SECONDS));
            return List.of(candidate("k"));
        });

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            HybridRetriever.RetrievalResult result = new HybridRetriever(
                    bm25, knn, null, new RetrievalProperties(), executor).retrieve(PLAN);
            assertEquals(2, result.successful().size());
        }
    }

    private static HybridRetriever retriever(Bm25Retriever bm25, KnnRetriever knn) {
        return new HybridRetriever(bm25, knn, new RetrievalProperties());
    }

    private static RetrievalCandidate candidate(String id) {
        return new RetrievalCandidate(id, "text", Map.of(), 1.0, null);
    }
}

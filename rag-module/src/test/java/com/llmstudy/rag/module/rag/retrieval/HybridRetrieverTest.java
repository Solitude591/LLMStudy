package com.llmstudy.rag.module.rag.retrieval;

import com.llmstudy.rag.auth.model.AccessContext;
import com.llmstudy.rag.auth.model.UserRole;
import com.llmstudy.rag.config.RetrievalProperties;
import com.llmstudy.rag.mapper.KnowledgeDocumentMapper;
import com.llmstudy.rag.entity.KnowledgeDocument;
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

        // 主两路恒在最前；原问题的扩展路排在其后。
        assertEquals(List.of("bm25", "knn"), result.lanes().stream()
                .map(HybridRetriever.Lane::channel).limit(2).toList());
        assertTrue(result.bm25().failed());
        assertFalse(result.knn().failed());
        assertFalse(result.knnDegraded());
        assertEquals(List.of("knn"), result.knn().hits().stream()
                .map(RetrievalCandidate::id).toList());
        // 词面主路失败后，剩下 KNN 主路加上原问题的扩展路。
        assertEquals(List.of("knn", "bm25-expansion"), result.lanes().stream()
                .filter(lane -> !lane.failed() && !lane.skipped())
                .map(HybridRetriever.Lane::channel).toList());
        assertEquals(List.of(1.0, 0.5), result.laneWeights(0.5));
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
    void comprehensiveQuestionExpandsBothLaneBudgets() throws Exception {
        RetrievalQueryPlan plan = new RetrievalQueryPlan(
                "比较三篇论文的显存策略", "比较三篇论文的显存策略",
                "Compare the memory strategies in three papers");
        Bm25Retriever bm25 = mock(Bm25Retriever.class);
        KnnRetriever knn = mock(KnnRetriever.class);
        RetrievalProperties properties = new RetrievalProperties();
        properties.setPerQueryTopK(10);
        properties.setComprehensivePerQueryTopK(20);
        when(bm25.retrieve(plan, null, 20)).thenReturn(List.of());
        when(knn.retrieve(plan, null, 20)).thenReturn(List.of());

        new HybridRetriever(bm25, knn, properties).retrieve(plan);

        verify(bm25).retrieve(plan, null, 20);
        verify(knn).retrieve(plan, null, 20);
    }

    @Test
    void pageHintExpandsBothLaneBudgets() throws Exception {
        RetrievalQueryPlan plan = new RetrievalQueryPlan(
                "《U-Net》第 2 页的跳连如何描述？", "U-Net 第 2 页", "U-Net page 2");
        Bm25Retriever bm25 = mock(Bm25Retriever.class);
        KnnRetriever knn = mock(KnnRetriever.class);
        when(bm25.retrieve(plan, null, 40)).thenReturn(List.of());
        when(knn.retrieve(plan, null, 40)).thenReturn(List.of());

        new HybridRetriever(bm25, knn, new RetrievalProperties()).retrieve(plan);

        verify(bm25).retrieve(plan, null, 40);
        verify(knn).retrieve(plan, null, 40);
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
            // 主两路 + 原问题的词面扩展路；向量扩展路因 mock 未产出向量而跳过。
            assertEquals(List.of("bm25", "knn", "bm25-expansion"),
                    result.lanes().stream().filter(lane -> !lane.failed())
                            .map(HybridRetriever.Lane::channel).toList());
        }
    }

    private static HybridRetriever retriever(Bm25Retriever bm25, KnnRetriever knn) {
        return new HybridRetriever(bm25, knn, new RetrievalProperties());
    }

    @Test
    void naturalComparisonResolvesAccessibleEntitiesBeforeScopeAndSupplementation() throws Exception {
        Bm25Retriever bm25 = mock(Bm25Retriever.class);
        KnnRetriever knn = mock(KnnRetriever.class);
        KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
        KnowledgeDocument rag = document("2020_RAG", "v-rag");
        KnowledgeDocument dpr = document("2020_DPR", "v-dpr");
        KnowledgeDocument stale = document("2023_RAGAS", "v-stale");
        AccessContext actor = new AccessContext("alice", "org-a", UserRole.USER);
        List<String> versions = List.of("v-rag", "v-dpr");
        when(documents.findAccessibleCurrentVersionIds("alice", "org-a", false)).thenReturn(versions);
        when(documents.findAccessible("alice", "org-a", false)).thenReturn(List.of(rag, dpr, stale));
        RetrievalQueryPlan plan = new RetrievalQueryPlan("RAG和DPR怎么训练？", "RAG和DPR怎么训练？", "RAG and DPR training");
        var result = new HybridRetriever(bm25, knn, documents, new RetrievalProperties(), Runnable::run)
                .retrieve(plan, actor);
        assertTrue(result.scope(plan).crossDocument());
        assertEquals(List.of("v-rag", "v-dpr"), result.mentionedVersionIds());
        verify(bm25).retrieve(plan, versions, 20);
        verify(knn).retrieve(plan, versions, 20);
        var focused = new RetrievalQueryPlan(plan.originalQuestion(), "和 怎么训练？", "and training");
        verify(bm25).retrieve(focused, List.of("v-rag"), 3);
        verify(bm25).retrieve(focused, List.of("v-dpr"), 3);
        org.mockito.Mockito.verify(documents, org.mockito.Mockito.never()).findAll();
        var single = new RetrievalQueryPlan("RAG和RAGAS怎么训练？", "RAG和RAGAS怎么训练？", "training");
        assertFalse(new HybridRetriever(bm25, knn, documents, new RetrievalProperties(), Runnable::run)
                .retrieve(single, actor).scope(single).crossDocument());
    }

    private static KnowledgeDocument document(String title, String version) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocTitle(title);
        document.setCurrentVersionId(version);
        return document;
    }

    private static RetrievalCandidate candidate(String id) {
        return new RetrievalCandidate(id, "text", Map.of(), 1.0, null);
    }
}

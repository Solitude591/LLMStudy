package com.llmstudy.rag.module.rag.aggregation;

import com.llmstudy.rag.config.RetrievalProperties;
import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RetrievalQueryPlan;
import com.llmstudy.rag.module.rag.rerank.CandidateReranker;
import com.llmstudy.rag.module.rag.rerank.RerankResult;
import com.llmstudy.rag.module.rag.retrieval.HybridRetriever;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RrfRerankAggregatorTest {

    @Test
    void groupsSameParentToHighestRrfChildBeforeBge() {
        CandidateReranker reranker = mock(CandidateReranker.class);
        when(reranker.rerank(any(), anyList())).thenAnswer(invocation ->
                RerankResult.fallback("too-few-candidates", 0, invocation.getArgument(1)));
        RetrievalCandidate first = child("c1", "p1", 0.9);
        RetrievalCandidate second = child("c2", "p1", 0.8);
        RetrievalCandidate other = child("c3", "p2", 0.7);
        HybridRetriever.RetrievalResult retrieval = new HybridRetriever.RetrievalResult(
                HybridRetriever.Lane.ok("zh_bm25", "zh", List.of(first, second, other), 1),
                HybridRetriever.Lane.skipped("en_bm25", "en"),
                HybridRetriever.Lane.ok("zh_knn", "zh", List.of(), 1),
                HybridRetriever.Lane.skipped("en_knn", "en"));

        RrfRerankAggregator.RankedEvidence result = aggregator(reranker).aggregate(
                new RetrievalQueryPlan("q", "zh", "en"), retrieval);

        assertEquals(List.of("c1", "c3"), result.grouped().stream()
                .map(RetrievalCandidate::id).toList());
        assertFalse(result.bgeUsed());
        assertEquals("too-few-candidates", result.bgeReason());
        assertTrue(result.bgeQuery().contains("strategy=document-language"));
        assertTrue(result.bgeQuery().contains("中文查询: zh"));
    }

    @Test
    void blendsBgeAndRetrievalRanksWhenBgeSucceeds() {
        CandidateReranker reranker = mock(CandidateReranker.class);
        RetrievalCandidate first = child("c1", "p1", 0.4).withRrfScore(0.4);
        RetrievalCandidate second = child("c2", "p2", 0.3).withRrfScore(0.3);
        when(reranker.rerank(any(), anyList())).thenReturn(RerankResult.success(List.of(
                second.withBgeScore(0.9), first.withBgeScore(0.1)), 12));
        HybridRetriever.RetrievalResult retrieval = new HybridRetriever.RetrievalResult(
                HybridRetriever.Lane.ok("zh_bm25", "zh", List.of(first, second), 1),
                HybridRetriever.Lane.skipped("en_bm25", "en"),
                HybridRetriever.Lane.ok("zh_knn", "zh", List.of(), 1),
                HybridRetriever.Lane.skipped("en_knn", "en"));

        RrfRerankAggregator.RankedEvidence result = aggregator(reranker).aggregate(
                new RetrievalQueryPlan("q", "zh", "en"), retrieval);

        assertTrue(result.bgeUsed());
        assertEquals(12, result.bgeElapsedMs());
        assertEquals("c2", result.ranked().getFirst().id());
        assertEquals(0.85 / 61 + 0.15 / 62, result.ranked().getFirst().finalScore(), 1e-9);
    }

    private static RrfRerankAggregator aggregator(CandidateReranker reranker) {
        return new RrfRerankAggregator(new RrfFusion(), reranker, new RetrievalProperties());
    }

    private static RetrievalCandidate child(String id, String parentId, double raw) {
        return new RetrievalCandidate(id, "text-" + id,
                Map.of(SegmentMetadataKeys.PARENT_CHUNK_ID, parentId), raw, null);
    }
}

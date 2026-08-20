package com.llmstudy.rag.module.rag;

import com.llmstudy.rag.config.RetrievalProperties;
import com.llmstudy.rag.enums.RagProgressStage;
import com.llmstudy.rag.module.llm.LlmFileLoggingAdvisor;
import com.llmstudy.rag.module.llm.LlmTraceContext;
import com.llmstudy.rag.module.llm.model.LlmPrompt;
import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import com.llmstudy.rag.module.rag.aggregation.RrfRerankAggregator;
import com.llmstudy.rag.module.rag.model.RagReference;
import com.llmstudy.rag.module.rag.model.RagRequest;
import com.llmstudy.rag.module.rag.model.RagResult;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RetrievalQueryPlan;
import com.llmstudy.rag.module.rag.prompt.RagPromptInjector;
import com.llmstudy.rag.module.rag.query.QueryRewriter;
import com.llmstudy.rag.module.rag.retrieval.HybridRetriever;
import com.llmstudy.rag.module.rag.retrieval.ParentChunkExpander;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagPipelineTest {

    @Test
    void executesRewriteRetrieveAggregateExpandThenInject() {
        QueryRewriter rewriter = mock(QueryRewriter.class);
        HybridRetriever retriever = mock(HybridRetriever.class);
        RrfRerankAggregator aggregator = mock(RrfRerankAggregator.class);
        ParentChunkExpander expander = mock(ParentChunkExpander.class);
        RagPromptInjector injector = mock(RagPromptInjector.class);
        RagRequest request = new RagRequest("original", "history");
        RetrievalQueryPlan plan = new RetrievalQueryPlan("original", "zh", "en");
        HybridRetriever.RetrievalResult retrieval = HybridRetriever.RetrievalResult.empty();
        RetrievalCandidate child = new RetrievalCandidate(
                "c1", "evidence", Map.of(), 0.5, null);
        RetrievalCandidate parent = new RetrievalCandidate(
                "p1", "parent text", Map.of(), 0.5, null);
        RagReference reference = new RagReference(
                1, "doc", "p1", null, null, null, null, 0.5, null);
        when(rewriter.rewrite(request)).thenReturn(plan);
        when(retriever.retrieve(plan, null)).thenReturn(retrieval);
        when(aggregator.aggregate(plan, retrieval)).thenReturn(ranked(List.of(child)));
        when(expander.expandOne(eq(child), anyMap())).thenReturn(parent);
        when(injector.inject(request, plan, List.of(parent)))
                .thenReturn(new RagPromptInjector.Injection(
                        new LlmPrompt("system", "user"), List.of(reference)));

        RagResult result = pipeline(rewriter, retriever, aggregator, expander, injector, 8)
                .execute(request);

        assertEquals("system", result.prompt().systemMessage());
        assertEquals("zh", result.queryPlan().standaloneZh());
        assertEquals(List.of("parent text"), result.chunks());
        var ordered = inOrder(rewriter, retriever, aggregator, expander, injector);
        ordered.verify(rewriter).rewrite(request);
        ordered.verify(retriever).retrieve(plan, null);
        ordered.verify(aggregator).aggregate(plan, retrieval);
        ordered.verify(expander).expandOne(eq(child), anyMap());
        ordered.verify(injector).inject(request, plan, List.of(parent));
    }

    @Test
    void backfillsAfterDuplicateParentUntilTopN() {
        QueryRewriter rewriter = mock(QueryRewriter.class);
        HybridRetriever retriever = mock(HybridRetriever.class);
        RrfRerankAggregator aggregator = mock(RrfRerankAggregator.class);
        ParentChunkExpander expander = mock(ParentChunkExpander.class);
        RagPromptInjector injector = mock(RagPromptInjector.class);
        RagRequest request = new RagRequest("original", "history");
        RetrievalQueryPlan plan = new RetrievalQueryPlan("original", "zh", "en");
        HybridRetriever.RetrievalResult retrieval = HybridRetriever.RetrievalResult.empty();
        RetrievalCandidate first = new RetrievalCandidate("c1", "a", Map.of(), 1.0, null);
        RetrievalCandidate second = new RetrievalCandidate("c2", "b", Map.of(), 0.9, null);
        RetrievalCandidate third = new RetrievalCandidate("c3", "c", Map.of(), 0.8, null);
        RetrievalCandidate parentA = new RetrievalCandidate("p1", "parent-a", Map.of(), 1.0, null);
        RetrievalCandidate parentB = new RetrievalCandidate("p2", "parent-b", Map.of(), 0.8, null);
        when(rewriter.rewrite(request)).thenReturn(plan);
        when(retriever.retrieve(plan, null)).thenReturn(retrieval);
        when(aggregator.aggregate(plan, retrieval))
                .thenReturn(ranked(List.of(first, second, third)));
        when(expander.expandOne(eq(first), anyMap())).thenReturn(parentA);
        when(expander.expandOne(eq(second), anyMap())).thenReturn(parentA);
        when(expander.expandOne(eq(third), anyMap())).thenReturn(parentB);
        when(injector.inject(eq(request), eq(plan), any()))
                .thenReturn(new RagPromptInjector.Injection(null, List.of()));

        RagResult result = pipeline(rewriter, retriever, aggregator, expander, injector, 2)
                .execute(request);

        assertEquals(List.of("parent-a", "parent-b"), result.chunks());
    }

    @Test
    void crossPaperQuestionUsesExpandedBudgetAndDiversifiesDocuments() {
        QueryRewriter rewriter = mock(QueryRewriter.class);
        HybridRetriever retriever = mock(HybridRetriever.class);
        RrfRerankAggregator aggregator = mock(RrfRerankAggregator.class);
        ParentChunkExpander expander = mock(ParentChunkExpander.class);
        RagPromptInjector injector = mock(RagPromptInjector.class);
        RagRequest request = new RagRequest("比较三篇论文", "无");
        RetrievalQueryPlan plan = new RetrievalQueryPlan(
                "比较三篇论文", "比较三篇论文", "Compare three papers");
        HybridRetriever.RetrievalResult retrieval = HybridRetriever.RetrievalResult.empty();
        RetrievalCandidate d1a = documentCandidate("d1a", "doc-1");
        RetrievalCandidate d1b = documentCandidate("d1b", "doc-1");
        RetrievalCandidate d1c = documentCandidate("d1c", "doc-1");
        RetrievalCandidate d2a = new RetrievalCandidate("d2a", "d2a text", Map.of(
                SegmentMetadataKeys.DOC_ID, "doc-2",
                SegmentMetadataKeys.FOCUSED_DOCUMENT_RANK, 1), 1.0, null);
        when(rewriter.rewrite(request)).thenReturn(plan);
        when(retriever.retrieve(plan, null)).thenReturn(retrieval);
        when(aggregator.aggregate(plan, retrieval))
                .thenReturn(ranked(List.of(d1a, d1b, d1c, d2a)));
        when(expander.expandOne(any(), anyMap()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(injector.inject(eq(request), eq(plan), any()))
                .thenReturn(new RagPromptInjector.Injection(null, List.of()));
        RetrievalProperties properties = new RetrievalProperties();
        properties.setTopN(2);
        properties.setComprehensiveTopN(4);
        properties.setCrossDocumentMaxChunks(2);

        RagResult result = new RagPipeline(
                rewriter, retriever, aggregator, expander, injector, properties)
                .execute(request);

        assertEquals(List.of("d2a text", "d1a text", "d1b text", "d1c text"),
                result.chunks());
    }

    @Test
    void emptyCandidatesYieldEmptyChunks() {
        QueryRewriter rewriter = mock(QueryRewriter.class);
        HybridRetriever retriever = mock(HybridRetriever.class);
        RrfRerankAggregator aggregator = mock(RrfRerankAggregator.class);
        ParentChunkExpander expander = mock(ParentChunkExpander.class);
        RagPromptInjector injector = mock(RagPromptInjector.class);
        RagRequest request = new RagRequest("original", "history");
        RetrievalQueryPlan plan = new RetrievalQueryPlan("original", "zh", "en");
        HybridRetriever.RetrievalResult retrieval = HybridRetriever.RetrievalResult.empty();
        when(rewriter.rewrite(request)).thenReturn(plan);
        when(retriever.retrieve(plan, null)).thenReturn(retrieval);
        when(aggregator.aggregate(plan, retrieval)).thenReturn(ranked(List.of()));
        when(injector.inject(request, plan, List.of()))
                .thenReturn(new RagPromptInjector.Injection(null, List.of()));

        RagResult result = pipeline(rewriter, retriever, aggregator, expander, injector, 8)
                .execute(request);

        assertTrue(result.empty());
        assertEquals(List.of(), result.chunks());
    }

    @Test
    void diagnoseClipsEvidenceTextUnlessIncludeText() {
        QueryRewriter rewriter = mock(QueryRewriter.class);
        HybridRetriever retriever = mock(HybridRetriever.class);
        RrfRerankAggregator aggregator = mock(RrfRerankAggregator.class);
        ParentChunkExpander expander = mock(ParentChunkExpander.class);
        RagPromptInjector injector = mock(RagPromptInjector.class);
        RagRequest request = new RagRequest("original", "history");
        RetrievalQueryPlan plan = new RetrievalQueryPlan("original", "zh", "en");
        HybridRetriever.RetrievalResult retrieval = HybridRetriever.RetrievalResult.empty();
        String longText = "字".repeat(400);
        RetrievalCandidate child = new RetrievalCandidate("c1", longText, Map.of(), 1.0, null);
        when(rewriter.rewrite(request)).thenReturn(plan);
        when(retriever.retrieve(plan, null)).thenReturn(retrieval);
        when(aggregator.aggregate(plan, retrieval)).thenReturn(ranked(List.of(child)));
        when(expander.expandOne(eq(child), anyMap())).thenReturn(child);

        RagPipeline pipeline = pipeline(
                rewriter, retriever, aggregator, expander, injector, 8);

        var preview = pipeline.diagnose(request, false);
        assertEquals(300, preview.finalCandidates().getFirst().text().length());
        assertFalse(preview.bgeUsed());
        assertEquals("too-few-candidates", preview.bgeReason());
        assertTrue(preview.failures().contains("bge: too-few-candidates"));
        assertEquals(400, pipeline.diagnose(request, true).finalCandidates()
                .getFirst().text().length());
        verifyNoInteractions(injector);
    }

    @Test
    void diagnoseBindsTraceIdBeforeRewrite() {
        QueryRewriter rewriter = mock(QueryRewriter.class);
        HybridRetriever retriever = mock(HybridRetriever.class);
        RrfRerankAggregator aggregator = mock(RrfRerankAggregator.class);
        ParentChunkExpander expander = mock(ParentChunkExpander.class);
        RagPromptInjector injector = mock(RagPromptInjector.class);
        RagRequest request = new RagRequest("original", "history");
        RetrievalQueryPlan plan = new RetrievalQueryPlan("original", "zh", "en");
        HybridRetriever.RetrievalResult retrieval = HybridRetriever.RetrievalResult.empty();
        AtomicReference<String> seenTraceId = new AtomicReference<>();
        when(rewriter.rewrite(request)).thenAnswer(invocation -> {
            seenTraceId.set(String.valueOf(
                    LlmTraceContext.params("query-rewrite")
                            .get(LlmFileLoggingAdvisor.TRACE_ID_KEY)));
            return plan;
        });
        when(retriever.retrieve(plan, null)).thenReturn(retrieval);
        when(aggregator.aggregate(plan, retrieval)).thenReturn(ranked(List.of()));

        var response = pipeline(rewriter, retriever, aggregator, expander, injector, 8)
                .diagnose(request, false);

        assertEquals(response.traceId(), seenTraceId.get());
        assertNull(LlmTraceContext.params("after").get(LlmFileLoggingAdvisor.TRACE_ID_KEY));
    }

    @Test
    void progressCallbacksMatchRewriteRetrieveAggregateOrder() {
        QueryRewriter rewriter = mock(QueryRewriter.class);
        HybridRetriever retriever = mock(HybridRetriever.class);
        RrfRerankAggregator aggregator = mock(RrfRerankAggregator.class);
        ParentChunkExpander expander = mock(ParentChunkExpander.class);
        RagPromptInjector injector = mock(RagPromptInjector.class);
        RagRequest request = new RagRequest("original", "history");
        RetrievalQueryPlan plan = new RetrievalQueryPlan("original", "zh", "en");
        HybridRetriever.RetrievalResult retrieval = HybridRetriever.RetrievalResult.empty();
        Queue<RagProgressStage> observed = new ArrayDeque<>();
        when(rewriter.rewrite(request)).thenAnswer(invocation -> {
            assertEquals(RagProgressStage.QUESTION_ANALYSIS, observed.peek());
            return plan;
        });
        when(retriever.retrieve(plan, null)).thenAnswer(invocation -> {
            assertEquals(List.of(
                    RagProgressStage.QUESTION_ANALYSIS,
                    RagProgressStage.KNOWLEDGE_RETRIEVAL), List.copyOf(observed));
            return retrieval;
        });
        when(aggregator.aggregate(plan, retrieval)).thenAnswer(invocation -> {
            assertEquals(List.of(
                    RagProgressStage.QUESTION_ANALYSIS,
                    RagProgressStage.KNOWLEDGE_RETRIEVAL,
                    RagProgressStage.EVIDENCE_ORGANIZATION), List.copyOf(observed));
            return ranked(List.of());
        });
        when(injector.inject(request, plan, List.of()))
                .thenReturn(new RagPromptInjector.Injection(null, List.of()));

        pipeline(rewriter, retriever, aggregator, expander, injector, 8)
                .execute(request, observed::add);

        assertEquals(List.of(
                RagProgressStage.QUESTION_ANALYSIS,
                RagProgressStage.KNOWLEDGE_RETRIEVAL,
                RagProgressStage.EVIDENCE_ORGANIZATION), List.copyOf(observed));
    }

    private static RagPipeline pipeline(QueryRewriter rewriter,
                                        HybridRetriever retriever,
                                        RrfRerankAggregator aggregator,
                                        ParentChunkExpander expander,
                                        RagPromptInjector injector,
                                        int topN) {
        RetrievalProperties properties = new RetrievalProperties();
        properties.setTopN(topN);
        return new RagPipeline(
                rewriter, retriever, aggregator, expander, injector, properties);
    }

    private static RrfRerankAggregator.RankedEvidence ranked(
            List<RetrievalCandidate> candidates) {
        return new RrfRerankAggregator.RankedEvidence(
                candidates, candidates, candidates, candidates,
                false, "too-few-candidates", 0, "query");
    }

    private static RetrievalCandidate documentCandidate(String id, String docId) {
        return new RetrievalCandidate(id, id + " text",
                Map.of(SegmentMetadataKeys.DOC_ID, docId), 1.0, null);
    }
}

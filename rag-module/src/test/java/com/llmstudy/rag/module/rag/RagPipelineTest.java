package com.llmstudy.rag.module.rag;

import com.llmstudy.rag.config.RerankerProperties;
import com.llmstudy.rag.module.llm.model.LlmPrompt;
import com.llmstudy.rag.module.rag.aggregation.RetrievalAggregator;
import com.llmstudy.rag.module.rag.model.RagReference;
import com.llmstudy.rag.module.rag.model.RagRequest;
import com.llmstudy.rag.module.rag.model.RagResult;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RewrittenQuery;
import com.llmstudy.rag.module.rag.prompt.RagPromptInjector;
import com.llmstudy.rag.module.rag.query.QueryRewriter;
import com.llmstudy.rag.module.rag.retrieval.HybridRetriever;
import com.llmstudy.rag.module.rag.retrieval.ParentChunkExpander;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagPipelineTest {

    @Test
    void executesRewriteRetrieveAggregateExpandThenTopNInject() {
        QueryRewriter rewriter = mock(QueryRewriter.class);
        HybridRetriever retriever = mock(HybridRetriever.class);
        RetrievalAggregator aggregator = mock(RetrievalAggregator.class);
        RagPromptInjector injector = mock(RagPromptInjector.class);
        RagRequest request = new RagRequest("original", "history");
        RewrittenQuery rewritten = new RewrittenQuery("original", "rewritten");
        HybridRetriever.RetrievalResult retrieval =
                new HybridRetriever.RetrievalResult(List.of(), List.of(), false);
        RetrievalCandidate candidate = new RetrievalCandidate(
                "1", "evidence", Map.of(), 0.5, null);
        RetrievalCandidate second = new RetrievalCandidate(
                "2", "more evidence", Map.of("docId", "doc"), 0.4, 0.9);
        RagReference reference = new RagReference(
                1, "doc", "1", null, null, null, null, 0.5, null);
        List<RetrievalCandidate> candidates = List.of(candidate, second);
        ParentChunkExpander expander = mock(ParentChunkExpander.class);
        RerankerProperties properties = new RerankerProperties();
        properties.setTopN(8);
        when(rewriter.rewrite(request)).thenReturn(rewritten);
        when(retriever.retrieve(rewritten, null)).thenReturn(retrieval);
        when(aggregator.aggregate(rewritten, retrieval)).thenReturn(candidates);
        when(expander.expand(candidates)).thenReturn(candidates);
        when(injector.inject(request, rewritten, candidates))
                .thenReturn(new RagPromptInjector.Injection(
                        new LlmPrompt("system", "user"), List.of(reference)));

        RagResult result = new RagPipeline(
                rewriter, retriever, aggregator, expander, injector, properties)
                .execute(request);

        assertEquals("system", result.prompt().systemMessage());
        assertEquals("user", result.prompt().userMessage());
        assertEquals("rewritten", result.rewrittenQuery().rewrittenQuestion());
        assertEquals(List.of("evidence", "more evidence"), result.chunks());
        var ordered = inOrder(rewriter, retriever, aggregator, expander, injector);
        ordered.verify(rewriter).rewrite(request);
        ordered.verify(retriever).retrieve(rewritten, null);
        ordered.verify(aggregator).aggregate(rewritten, retrieval);
        ordered.verify(expander).expand(candidates);
        ordered.verify(injector).inject(request, rewritten, candidates);
    }

    @Test
    void appliesTopNAfterParentExpand() {
        QueryRewriter rewriter = mock(QueryRewriter.class);
        HybridRetriever retriever = mock(HybridRetriever.class);
        RetrievalAggregator aggregator = mock(RetrievalAggregator.class);
        ParentChunkExpander expander = mock(ParentChunkExpander.class);
        RagPromptInjector injector = mock(RagPromptInjector.class);
        RagRequest request = new RagRequest("original", "history");
        RewrittenQuery rewritten = new RewrittenQuery("original", "rewritten");
        HybridRetriever.RetrievalResult retrieval =
                new HybridRetriever.RetrievalResult(List.of(), List.of(), false);
        List<RetrievalCandidate> fused = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            fused.add(new RetrievalCandidate(
                    "c" + i, "child-" + i, Map.of(), 1.0 - i * 0.1, null));
        }
        List<RetrievalCandidate> expanded = List.of(
                new RetrievalCandidate("p1", "parent-a", Map.of(), 1.0, null),
                new RetrievalCandidate("p2", "parent-b", Map.of(), 0.9, null),
                new RetrievalCandidate("p3", "parent-c", Map.of(), 0.8, null));
        RerankerProperties properties = new RerankerProperties();
        properties.setTopN(2);
        when(rewriter.rewrite(request)).thenReturn(rewritten);
        when(retriever.retrieve(rewritten, null)).thenReturn(retrieval);
        when(aggregator.aggregate(rewritten, retrieval)).thenReturn(fused);
        when(expander.expand(fused)).thenReturn(expanded);
        when(injector.inject(request, rewritten, List.of(expanded.get(0), expanded.get(1))))
                .thenReturn(new RagPromptInjector.Injection(null, List.of()));

        RagResult result = new RagPipeline(
                rewriter, retriever, aggregator, expander, injector, properties)
                .execute(request);

        assertEquals(List.of("parent-a", "parent-b"), result.chunks());
    }

    @Test
    void emptyCandidatesYieldEmptyChunks() {
        QueryRewriter rewriter = mock(QueryRewriter.class);
        HybridRetriever retriever = mock(HybridRetriever.class);
        RetrievalAggregator aggregator = mock(RetrievalAggregator.class);
        ParentChunkExpander expander = mock(ParentChunkExpander.class);
        RagPromptInjector injector = mock(RagPromptInjector.class);
        RerankerProperties properties = new RerankerProperties();
        RagRequest request = new RagRequest("original", "history");
        RewrittenQuery rewritten = new RewrittenQuery("original", "rewritten");
        HybridRetriever.RetrievalResult retrieval =
                new HybridRetriever.RetrievalResult(List.of(), List.of(), false);
        when(rewriter.rewrite(request)).thenReturn(rewritten);
        when(retriever.retrieve(rewritten, null)).thenReturn(retrieval);
        when(aggregator.aggregate(rewritten, retrieval)).thenReturn(List.of());
        when(expander.expand(List.of())).thenReturn(List.of());
        when(injector.inject(request, rewritten, List.of()))
                .thenReturn(new RagPromptInjector.Injection(null, List.of()));

        RagResult result = new RagPipeline(
                rewriter, retriever, aggregator, expander, injector, properties)
                .execute(request);

        assertTrue(result.empty());
        assertEquals(List.of(), result.chunks());
    }
}

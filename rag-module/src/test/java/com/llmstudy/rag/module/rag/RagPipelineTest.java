package com.llmstudy.rag.module.rag;

import com.llmstudy.rag.module.rag.aggregation.RetrievalAggregator;
import com.llmstudy.rag.module.rag.model.RagReference;
import com.llmstudy.rag.module.rag.model.RagRequest;
import com.llmstudy.rag.module.rag.model.RagResult;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RewrittenQuery;
import com.llmstudy.rag.module.rag.prompt.RagPromptInjector;
import com.llmstudy.rag.module.rag.query.QueryRewriter;
import com.llmstudy.rag.module.rag.retrieval.HybridRetriever;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagPipelineTest {

    @Test
    void executesRewriteRetrieveAggregateAndInjectInOrder() {
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
        RagReference reference = new RagReference(
                1, "doc", "1", null, null, 0.5, null);
        when(rewriter.rewrite(request)).thenReturn(rewritten);
        when(retriever.retrieve(rewritten)).thenReturn(retrieval);
        when(aggregator.aggregate(rewritten, retrieval)).thenReturn(List.of(candidate));
        when(injector.inject(request, rewritten, List.of(candidate)))
                .thenReturn(new RagPromptInjector.Injection("prompt", List.of(reference)));

        RagResult result = new RagPipeline(rewriter, retriever, aggregator, injector)
                .execute(request);

        assertEquals("prompt", result.prompt());
        assertEquals("rewritten", result.rewrittenQuery().rewrittenQuestion());
        var ordered = inOrder(rewriter, retriever, aggregator, injector);
        ordered.verify(rewriter).rewrite(request);
        ordered.verify(retriever).retrieve(rewritten);
        ordered.verify(aggregator).aggregate(rewritten, retrieval);
        ordered.verify(injector).inject(request, rewritten, List.of(candidate));
    }
}

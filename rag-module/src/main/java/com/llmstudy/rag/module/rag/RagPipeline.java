package com.llmstudy.rag.module.rag;

import com.llmstudy.rag.config.RerankerProperties;
import com.llmstudy.rag.module.rag.aggregation.RetrievalAggregator;
import com.llmstudy.rag.module.rag.model.RagRequest;
import com.llmstudy.rag.module.rag.model.RagResult;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RewrittenQuery;
import com.llmstudy.rag.module.rag.prompt.RagPromptInjector;
import com.llmstudy.rag.module.rag.query.QueryRewriter;
import com.llmstudy.rag.module.rag.retrieval.HybridRetriever;
import com.llmstudy.rag.module.rag.retrieval.ParentChunkExpander;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 在线 RAG 唯一编排入口。
 *
 * <p>固定按“查询改写 → 混合检索 → 聚合/重排 → 父片展开去重 → Top-N → Prompt 注入”执行。</p>
 */
@Service
public class RagPipeline {

    private final QueryRewriter queryRewriter;
    private final HybridRetriever hybridRetriever;
    private final RetrievalAggregator aggregator;
    private final ParentChunkExpander parentChunkExpander;
    private final RagPromptInjector promptInjector;
    private final RerankerProperties rerankerProperties;

    public RagPipeline(QueryRewriter queryRewriter,
                       HybridRetriever hybridRetriever,
                       RetrievalAggregator aggregator,
                       ParentChunkExpander parentChunkExpander,
                       RagPromptInjector promptInjector,
                       RerankerProperties rerankerProperties) {
        this.queryRewriter = queryRewriter;
        this.hybridRetriever = hybridRetriever;
        this.aggregator = aggregator;
        this.parentChunkExpander = parentChunkExpander;
        this.promptInjector = promptInjector;
        this.rerankerProperties = rerankerProperties;
    }

    /**
     * 执行一次完整 RAG：改写 → 双路检索 → 融合重排 → parent 展开 → Top-N → Prompt。
     *
     * @return 空候选时 Prompt 为 null，但仍带改写结果
     */
    public RagResult execute(RagRequest request) {
        RewrittenQuery rewritten = queryRewriter.rewrite(request);
        HybridRetriever.RetrievalResult retrieval = hybridRetriever.retrieve(
                rewritten, request.accessContext());
        // 保留 candidateCount 量级，供 parent 去重后仍有足够不同章节可补位。
        List<RetrievalCandidate> fused = aggregator.aggregate(rewritten, retrieval);
        List<RetrievalCandidate> expanded = parentChunkExpander.expand(fused);
        // 最终 Top-N 必须在 parent 去重之后，否则同 parent 的多个 child 会挤掉其他证据。
        List<RetrievalCandidate> candidates = expanded.stream()
                .limit(Math.max(1, rerankerProperties.getTopN()))
                .toList();
        RagPromptInjector.Injection injection = promptInjector.inject(
                request, rewritten, candidates);
        List<String> chunks = candidates.stream()
                .map(RetrievalCandidate::text)
                .toList();
        return new RagResult(injection.prompt(), rewritten, injection.references(), chunks);
    }
}

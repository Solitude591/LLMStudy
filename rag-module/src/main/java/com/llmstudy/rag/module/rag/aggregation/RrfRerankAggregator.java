package com.llmstudy.rag.module.rag.aggregation;

import com.llmstudy.rag.config.RerankerProperties;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RewrittenQuery;
import com.llmstudy.rag.module.rag.rerank.CandidateReranker;
import com.llmstudy.rag.module.rag.retrieval.HybridRetriever;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/** 先执行 RRF，再可选 ReRanker；最终 Top-N 由 Pipeline 在 parent 展开去重之后截断。 */
@Component
public class RrfRerankAggregator implements RetrievalAggregator {

    private final RrfFusion fusion;
    private final CandidateReranker reranker;
    private final RerankerProperties properties;

    public RrfRerankAggregator(RrfFusion fusion,
                               CandidateReranker reranker,
                               RerankerProperties properties) {
        this.fusion = fusion;
        this.reranker = reranker;
        this.properties = properties;
    }

    /**
     * 聚合双路候选并重排，保留最多 {@code candidateCount} 条供后续 parent 展开。
     *
     * <p>此处故意不做最终 Top-N：多个 child 可能折叠为同一 parent，
     * 若先截断再去重会把证据条数压得过少。</p>
     */
    @Override
    public List<RetrievalCandidate> aggregate(
            RewrittenQuery query, HybridRetriever.RetrievalResult result) {
        List<RetrievalCandidate> candidates;
        if (result.degraded()) {
            // 单通道降级时 score 仍是该检索器原始分数，不伪造 RRF 分数。
            List<RetrievalCandidate> available = result.bm25().isEmpty()
                    ? result.knn() : result.bm25();
            candidates = available.stream()
                    .sorted(Comparator.comparingDouble(
                            RetrievalCandidate::score).reversed())
                    .limit(properties.getCandidateCount())
                    .toList();
        } else {
            candidates = fusion.fuse(result.bm25(), result.knn(),
                    properties.getCandidateCount());
        }
        // ReRanker 内部会在禁用或失败时返回原顺序。
        return reranker.rerank(query.originalQuestion(), candidates);
    }
}

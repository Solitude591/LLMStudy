package com.llmstudy.rag.module.rag.aggregation;

import com.llmstudy.rag.config.RerankerProperties;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RewrittenQuery;
import com.llmstudy.rag.module.rag.rerank.CandidateReranker;
import com.llmstudy.rag.module.rag.retrieval.HybridRetriever;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/** 先执行 RRF，再可选执行 ReRanker，最后统一截断 Top N。 */
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
     * 聚合双路候选。单路故障时不再对仅存通道做 RRF，直接保留其原始排序。
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
        // ReRanker 内部会在禁用或失败时返回原顺序，Top N 截断始终执行。
        return reranker.rerank(query.originalQuestion(), candidates).stream()
                .limit(properties.getTopN())
                .toList();
    }
}

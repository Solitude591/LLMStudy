package com.llmstudy.rag.module.rag.aggregation;

import com.llmstudy.rag.config.RetrievalProperties;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RetrievalQueryPlan;
import com.llmstudy.rag.module.rag.rerank.BgeCandidateReranker;
import com.llmstudy.rag.module.rag.rerank.CandidateReranker;
import com.llmstudy.rag.module.rag.rerank.RerankResult;
import com.llmstudy.rag.module.rag.retrieval.HybridRetriever;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 四路 RRF → parent 分组 → BGE → 名次融合。
 *
 * <p>此阶段不展开完整 parent，避免长章节截断真正命中的 child 内容。
 * 最终 Top-N 和 parent 展开由 Pipeline 在本类返回的排序列表上补位完成。</p>
 */
@Component
public class RrfRerankAggregator {

    private final RrfFusion fusion;
    private final CandidateReranker reranker;
    private final RetrievalProperties properties;

    public RrfRerankAggregator(RrfFusion fusion,
                               CandidateReranker reranker,
                               RetrievalProperties properties) {
        this.fusion = fusion;
        this.reranker = reranker;
        this.properties = properties;
    }

    /**
     * 对四路原始命中做排序，返回各阶段列表供在线注入和诊断复用。
     *
     * @param plan   中英文独立查询，BGE 按候选文档语言选择其中一路
     * @param result 四路召回结果；失败路不会进入 RRF
     */
    public RankedEvidence aggregate(RetrievalQueryPlan plan,
                                    HybridRetriever.RetrievalResult result) {
        List<RetrievalCandidate> rrf = fusion.fuse(
                result.successful(),
                properties.getFusionCandidateCount(),
                properties.getRrfK());
        List<RetrievalCandidate> grouped = group(rrf, properties.getRerankCandidateCount());
        String bgeQuery = BgeCandidateReranker.diagnose(plan);
        RerankResult reranked = reranker.rerank(plan, grouped);
        List<RetrievalCandidate> afterBge = reranked.candidates();
        List<RetrievalCandidate> ranked;
        if (reranked.used()) {
            ranked = blend(grouped, afterBge);
        } else {
            // 完整回退分组后的 RRF 顺序；finalScore 直接用 rrfScore。
            ranked = afterBge.stream()
                    .map(candidate -> candidate.withFinalScore(
                            candidate.rrfScore() == null ? 0.0 : candidate.rrfScore()))
                    .toList();
        }
        return new RankedEvidence(rrf, grouped, afterBge, ranked,
                reranked.used(), reranked.reason(), reranked.elapsedMs(), bgeQuery);
    }

    /**
     * 以 parent ID（standalone 用自身 ID）分组，每组只留 RRF 最高的 child。
     *
     * <p>被合并的 sibling 不累加 RRF。诊断可通过对比 {@code rrf} 与 {@code grouped}
     * 看出哪些 child 被丢掉。</p>
     */
    private static List<RetrievalCandidate> group(List<RetrievalCandidate> rrf, int limit) {
        Map<String, RetrievalCandidate> groups = new LinkedHashMap<>();
        for (RetrievalCandidate candidate : rrf) {
            groups.putIfAbsent(candidate.groupingKey(), candidate);
        }
        return groups.values().stream().limit(Math.max(1, limit)).toList();
    }

    /**
     * {@code finalScore = w_bge/(k+bgeRank) + w_rrf/(k+retrievalRank)}。
     *
     * <p>{@code retrievalRank} 取 parent 分组后的顺序；{@code bgeRank} 取 BGE 排序后的顺序。
     * 同分再比 BGE 原始分、RRF 分、分组键，保证输出稳定可测。</p>
     */
    private List<RetrievalCandidate> blend(List<RetrievalCandidate> grouped,
                                           List<RetrievalCandidate> bgeSorted) {
        int k = properties.getRrfK();
        Map<String, Integer> retrievalRank = ranks(grouped);
        Map<String, Integer> bgeRank = ranks(bgeSorted);
        return bgeSorted.stream()
                .map(candidate -> candidate.withFinalScore(
                        properties.getBgeRankWeight() / (k + bgeRank.get(candidate.id()))
                                + properties.getRetrievalRerankWeight()
                                / (k + retrievalRank.get(candidate.id()))))
                .sorted(Comparator.comparingDouble(RetrievalCandidate::finalScore).reversed()
                        .thenComparing(Comparator.comparingDouble(
                                RetrievalCandidate::bgeScore).reversed())
                        .thenComparing(Comparator.comparingDouble(
                                RetrievalCandidate::rrfScore).reversed())
                        .thenComparing(RetrievalCandidate::groupingKey))
                .toList();
    }

    private static Map<String, Integer> ranks(List<RetrievalCandidate> list) {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (int index = 0; index < list.size(); index++) {
            ranks.put(list.get(index).id(), index + 1);
        }
        return ranks;
    }

    /**
     * 排序各阶段快照。
     *
     * @param rrf          四路 RRF 后的 chunk 列表
     * @param grouped      parent 分组后的代表 child
     * @param afterBge     BGE 打分后的列表；失败时与 grouped 同序且无 bgeScore
     * @param ranked       最终名次融合后的列表，供 Pipeline 按序展开
     * @param bgeUsed      false 表示完整回退分组后的 RRF 顺序
     * @param bgeReason    回退原因；成功时为 null
     * @param bgeElapsedMs BGE 阶段耗时
     * @param bgeQuery     语言选择策略及三种实际 BGE 查询
     */
    public record RankedEvidence(List<RetrievalCandidate> rrf,
                                 List<RetrievalCandidate> grouped,
                                 List<RetrievalCandidate> afterBge,
                                 List<RetrievalCandidate> ranked,
                                 boolean bgeUsed,
                                 String bgeReason,
                                 long bgeElapsedMs,
                                 String bgeQuery) {
        public RankedEvidence {
            rrf = List.copyOf(rrf);
            grouped = List.copyOf(grouped);
            afterBge = List.copyOf(afterBge);
            ranked = List.copyOf(ranked);
        }
    }
}

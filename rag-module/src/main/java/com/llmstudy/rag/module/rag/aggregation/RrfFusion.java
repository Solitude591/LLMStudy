package com.llmstudy.rag.module.rag.aggregation;

import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多路 Reciprocal Rank Fusion。
 *
 * <p>只使用名次，不混合不可比的 BM25 与向量原始分数。
 * 同一 chunk 多路命中时累加 {@code 1/(k+rank)}，rank 从 1 开始。</p>
 */
@Component
public class RrfFusion {

    /**
     * 按两路（或更少成功路）名次等权融合。
     *
     * @param lanes 每路已按该路原始分排序的命中
     * @param limit 融合后最多保留条数
     * @param k     RRF 平滑项，配置默认 60
     * @return 按 rrfScore、命中路数、最佳名次、chunk ID 排序的候选
     */
    public List<RetrievalCandidate> fuse(List<List<RetrievalCandidate>> lanes,
                                         int limit, int k) {
        return fuse(lanes, Collections.nCopies(lanes.size(), 1.0), limit, k);
    }

    /**
     * 按名次加权融合。
     *
     * <p>扩展查询路数可能远多于主路（一条主查询对两路，三条扩展对六路）。等权融合会让
     * 改写出来的查询把用户真正问的那条票数淹掉，所以扩展路统一给一个小于 1 的权重：
     * 它们只能把被多条查询共同命中的片段往前推，不足以单独顶掉主路的高名次结果。</p>
     *
     * @param lanes   每路已按该路原始分排序的命中
     * @param weights 与 {@code lanes} 等长的权重
     * @param limit   融合后最多保留条数
     * @param k       RRF 平滑项，配置默认 60
     */
    public List<RetrievalCandidate> fuse(List<List<RetrievalCandidate>> lanes,
                                         List<Double> weights, int limit, int k) {
        if (weights == null || weights.size() != lanes.size()) {
            throw new IllegalArgumentException("RRF 权重数量必须与通道数一致");
        }
        Map<String, Acc> merged = new LinkedHashMap<>();
        for (int lane = 0; lane < lanes.size(); lane++) {
            double weight = weights.get(lane) == null ? 1.0 : weights.get(lane);
            List<RetrievalCandidate> hits = lanes.get(lane);
            for (int index = 0; index < hits.size(); index++) {
                RetrievalCandidate hit = hits.get(index);
                int rank = index + 1;
                double contribution = weight / (k + rank);
                Acc acc = merged.get(hit.id());
                if (acc == null) {
                    // 首次命中保留该路的原文和 rawScore，后续只加 RRF 贡献。
                    merged.put(hit.id(), new Acc(hit, contribution, 1, rank));
                } else {
                    merged.put(hit.id(), new Acc(acc.first,
                            acc.rrf + contribution,
                            acc.hitCount + 1,
                            Math.min(acc.bestRank, rank)));
                }
            }
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(Acc::rrf).reversed()
                        .thenComparing(Comparator.comparingInt(Acc::hitCount).reversed())
                        .thenComparingInt(Acc::bestRank)
                        .thenComparing(acc -> acc.first.id()))
                .limit(Math.max(1, limit))
                .map(acc -> acc.first.withRrfScore(acc.rrf))
                .toList();
    }

    /** 融合过程中的累加器；不泄漏到候选模型上。 */
    private record Acc(RetrievalCandidate first, double rrf, int hitCount, int bestRank) {
    }
}

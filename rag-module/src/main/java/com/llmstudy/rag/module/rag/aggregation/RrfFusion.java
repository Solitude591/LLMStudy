package com.llmstudy.rag.module.rag.aggregation;

import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import org.springframework.stereotype.Component;

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
     * 按两路（或更少成功路）名次融合。
     *
     * @param lanes 每路已按该路原始分排序的命中
     * @param limit 融合后最多保留条数
     * @param k     RRF 平滑项，配置默认 60
     * @return 按 rrfScore、命中路数、最佳名次、chunk ID 排序的候选
     */
    public List<RetrievalCandidate> fuse(List<List<RetrievalCandidate>> lanes,
                                         int limit, int k) {
        Map<String, Acc> merged = new LinkedHashMap<>();
        for (List<RetrievalCandidate> lane : lanes) {
            for (int index = 0; index < lane.size(); index++) {
                RetrievalCandidate hit = lane.get(index);
                int rank = index + 1;
                Acc acc = merged.get(hit.id());
                if (acc == null) {
                    // 首次命中保留该路的原文和 rawScore，后续只加 RRF 贡献。
                    merged.put(hit.id(), new Acc(hit, 1.0 / (k + rank), 1, rank));
                } else {
                    merged.put(hit.id(), new Acc(acc.first,
                            acc.rrf + 1.0 / (k + rank),
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

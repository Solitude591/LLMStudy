package com.llmstudy.rag.module.rag.aggregation;

import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 确定性的 Reciprocal Rank Fusion（RRF）实现。 */
@Component
public class RrfFusion {

    private static final int K = 60;

    /**
     * 按候选在每条通道中的名次计算 RRF 分数，同 ID 候选自动合并得分。
     *
     * @param first  第一条通道的有序候选
     * @param second 第二条通道的有序候选
     * @param limit  融合后候选数上限
     * @return 按 RRF 分数降序排列的候选
     */
    public List<RetrievalCandidate> fuse(List<RetrievalCandidate> first,
                                         List<RetrievalCandidate> second,
                                         int limit) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, RetrievalCandidate> candidates = new LinkedHashMap<>();
        // LinkedHashMap 保留首次命中顺序，使同分时的输出稳定可测。
        contribute(first, scores, candidates);
        contribute(second, scores, candidates);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> {
                    RetrievalCandidate candidate = candidates.get(entry.getKey());
                    return new RetrievalCandidate(candidate.id(), candidate.text(),
                            candidate.metadata(), entry.getValue(), null);
                }).toList();
    }

    /** 将单通道的名次贡献累加到候选总分。 */
    private void contribute(List<RetrievalCandidate> source,
                            Map<String, Double> scores,
                            Map<String, RetrievalCandidate> candidates) {
        for (int index = 0; index < source.size(); index++) {
            RetrievalCandidate candidate = source.get(index);
            scores.merge(candidate.id(), 1.0 / (K + index + 1), Double::sum);
            candidates.putIfAbsent(candidate.id(), candidate);
        }
    }
}

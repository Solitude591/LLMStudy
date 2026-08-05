package com.llmstudy.rag.module.rag.rerank;

import com.llmstudy.rag.config.RerankerProperties;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** 可选 BGE 重排适配器；禁用、评分异常或执行失败均保留 RRF 顺序。 */
@Component
public class BgeCandidateReranker implements CandidateReranker {

    private static final Logger log = LoggerFactory.getLogger(BgeCandidateReranker.class);
    private final RerankerProperties properties;
    private final BgeScoringModel scoringModel;

    public BgeCandidateReranker(RerankerProperties properties,
                                BgeScoringModel scoringModel) {
        this.properties = properties;
        this.scoringModel = scoringModel;
    }

    /** {@inheritDoc} */
    @Override
    public List<RetrievalCandidate> rerank(String question,
                                           List<RetrievalCandidate> candidates) {
        // 0/1 个候选不存在排序收益，避免无效加载本地模型。
        if (!properties.isEnabled() || candidates.size() <= 1) {
            return candidates;
        }
        try {
            List<TextSegment> segments = candidates.stream()
                    .map(candidate -> TextSegment.from(candidate.text()))
                    .toList();
            List<Double> scores = scoringModel.scoreAll(segments, question).content();
            if (scores == null || scores.size() != candidates.size()) {
                log.warn("BGE 评分数量不一致，保持 RRF 排序");
                return candidates;
            }
            // 通过原下标关联评分，重建候选时同时保留 RRF score 供审计。
            return IntStream.range(0, candidates.size()).boxed()
                    .sorted(Comparator.comparingDouble(
                            (Integer index) -> scores.get(index)).reversed())
                    .map(index -> candidates.get(index)
                            .withRerankedScore(scores.get(index)))
                    .toList();
        } catch (Exception e) {
            log.warn("BGE ReRanker 执行失败，保持 RRF 排序", e);
            return candidates;
        }
    }
}

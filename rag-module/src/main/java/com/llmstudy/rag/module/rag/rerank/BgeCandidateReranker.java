package com.llmstudy.rag.module.rag.rerank;

import com.llmstudy.rag.config.RerankerProperties;
import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * 可选 BGE 重排适配器。
 *
 * <p>禁用、候选不足、评分异常或执行失败时返回 {@link RerankResult#fallback}，
 * 候选保持输入顺序且不写 {@code bgeScore}。成功时按 BGE 分排序并写入 bgeScore。</p>
 */
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

    /**
     * 用双语查询对代表 child 打分。
     *
     * <p>每个候选的 BGE 文档为 {@code header_path + 换行 + child 正文}。
     * 失败不抛给问答主流程，但 reason 会进入诊断 failures。</p>
     */
    @Override
    public RerankResult rerank(String question, List<RetrievalCandidate> candidates) {
        long started = System.nanoTime();
        List<RetrievalCandidate> safe = candidates == null ? List.of() : candidates;
        if (!properties.isEnabled()) {
            return RerankResult.fallback("disabled", elapsedMs(started), safe);
        }
        // 0/1 个候选不存在排序收益，避免无效加载本地模型。
        if (safe.size() <= 1) {
            return RerankResult.fallback("too-few-candidates", elapsedMs(started), safe);
        }
        if (question == null || question.isBlank()) {
            return RerankResult.fallback("blank-query", elapsedMs(started), safe);
        }
        try {
            List<TextSegment> segments = safe.stream()
                    .map(BgeCandidateReranker::document)
                    .toList();
            List<Double> scores = scoringModel.scoreAll(segments, question).content();
            if (scores == null || scores.size() != safe.size()) {
                log.warn("BGE 评分数量不一致，保持 RRF 排序");
                return RerankResult.fallback("score-count-mismatch", elapsedMs(started), safe);
            }
            if (scores.stream().anyMatch(score -> score == null || !Double.isFinite(score))) {
                log.warn("BGE 评分包含非法值，保持 RRF 排序");
                return RerankResult.fallback("invalid-score", elapsedMs(started), safe);
            }
            List<RetrievalCandidate> ranked = IntStream.range(0, safe.size()).boxed()
                    .sorted(Comparator.comparingDouble(
                            (Integer index) -> scores.get(index)).reversed())
                    .map(index -> safe.get(index).withBgeScore(scores.get(index)))
                    .toList();
            return RerankResult.success(ranked, elapsedMs(started));
        } catch (Exception e) {
            log.warn("BGE ReRanker 执行失败，保持 RRF 排序", e);
            // 对外只返回稳定短码；异常类型、消息和堆栈仅保留在带 traceId 的服务端日志。
            return RerankResult.fallback("inference-error", elapsedMs(started), safe);
        }
    }

    /** 章节路径为空时只送 child 正文，避免出现前导空行。 */
    private static TextSegment document(RetrievalCandidate candidate) {
        Object header = candidate.metadata().get(SegmentMetadataKeys.HEADER_PATH);
        String path = header == null ? "" : header.toString().trim();
        String body = path.isEmpty() ? candidate.text() : path + "\n" + candidate.text();
        return TextSegment.from(body);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}

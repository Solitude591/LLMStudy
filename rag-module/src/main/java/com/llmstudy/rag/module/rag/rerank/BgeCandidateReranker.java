package com.llmstudy.rag.module.rag.rerank;

import com.llmstudy.rag.config.RerankerProperties;
import com.llmstudy.rag.enums.DocumentLanguage;
import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RetrievalQueryPlan;
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
 * 候选保持输入顺序且不写 {@code bgeScore}。成功时先过滤低于
 * {@link RerankerProperties#getMinScore()} 的噪声，再按 BGE 分排序并写入 bgeScore。</p>
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
     * 按候选文档语言选择查询，对代表 child 打分。
     *
     * <p>每个候选的 BGE 文档为 {@code 末级标题 + 换行 + child 正文}。
     * 失败不抛给问答主流程，但 reason 会进入诊断 failures。</p>
     */
    @Override
    public RerankResult rerank(RetrievalQueryPlan plan, List<RetrievalCandidate> candidates) {
        long started = System.nanoTime();
        List<RetrievalCandidate> safe = candidates == null ? List.of() : candidates;
        if (!properties.isEnabled()) {
            return RerankResult.fallback("disabled", elapsedMs(started), safe);
        }
        if (safe.isEmpty()) {
            return RerankResult.fallback("too-few-candidates", elapsedMs(started), safe);
        }
        if (plan == null) {
            return RerankResult.fallback("blank-query", elapsedMs(started), safe);
        }
        try {
            List<String> queries = safe.stream()
                    .map(candidate -> queryFor(plan, candidate))
                    .toList();
            List<TextSegment> segments = safe.stream()
                    .map(BgeCandidateReranker::document)
                    .toList();
            List<Double> scores = scoringModel.scorePairs(queries, segments).content();
            if (scores == null || scores.size() != safe.size()) {
                log.warn("BGE 评分数量不一致，保持 RRF 排序");
                return RerankResult.fallback("score-count-mismatch", elapsedMs(started), safe);
            }
            if (scores.stream().anyMatch(score -> score == null || !Double.isFinite(score))) {
                log.warn("BGE 评分包含非法值，保持 RRF 排序");
                return RerankResult.fallback("invalid-score", elapsedMs(started), safe);
            }
            double minScore = properties.getMinScore();
            if (!Double.isFinite(minScore) || minScore < 0.0 || minScore > 1.0) {
                log.warn("BGE minScore 非法，保持 RRF 排序: {}", minScore);
                return RerankResult.fallback("invalid-min-score", elapsedMs(started), safe);
            }
            List<RetrievalCandidate> ranked = IntStream.range(0, safe.size()).boxed()
                    .filter(index -> scores.get(index) >= minScore)
                    .sorted(Comparator.comparingDouble(
                            (Integer index) -> scores.get(index)).reversed())
                    .map(index -> safe.get(index).withBgeScore(scores.get(index)))
                    .toList();
            return RerankResult.success(ranked, elapsedMs(started));
        } catch (Exception e) {
            log.warn("BGE ReRanker 执行失败，保持 RRF 排序", e);
            return RerankResult.fallback("inference-error", elapsedMs(started), safe);
        }
    }

    static String queryFor(RetrievalQueryPlan plan, RetrievalCandidate candidate) {
        Object raw = candidate.metadata().get(SegmentMetadataKeys.LANGUAGE);
        return switch (DocumentLanguage.fromValue(raw == null ? null : raw.toString())) {
            case ZH -> override(candidate, SegmentMetadataKeys.RERANK_QUERY_ZH,
                    plan.standaloneZh());
            case EN -> override(candidate, SegmentMetadataKeys.RERANK_QUERY_EN,
                    plan.standaloneEn());
            case UNKNOWN -> bilingual(
                    override(candidate, SegmentMetadataKeys.RERANK_QUERY_ZH,
                            plan.standaloneZh()),
                    override(candidate, SegmentMetadataKeys.RERANK_QUERY_EN,
                            plan.standaloneEn()));
        };
    }

    static String bilingual(RetrievalQueryPlan plan) {
        return bilingual(plan.standaloneZh(), plan.standaloneEn());
    }

    private static String bilingual(String zh, String en) {
        return "中文查询: " + zh + "\nEnglish query: " + en;
    }

    private static String override(RetrievalCandidate candidate,
                                   String key,
                                   String fallback) {
        Object value = candidate.metadata().get(key);
        return value == null || value.toString().isBlank()
                ? fallback : value.toString();
    }

    /** 诊断保留 String，输出语言选择策略及三种实际查询。 */
    public static String diagnose(RetrievalQueryPlan plan) {
        return "strategy=document-language\n"
                + "ZH: " + plan.standaloneZh() + "\n"
                + "EN: " + plan.standaloneEn() + "\n"
                + "UNKNOWN: " + bilingual(plan);
    }

    /**
     * 章节路径为空时只送 child 正文；否则拼完整 header_path。
     *
     * <p>只送末级标题会丢掉论文名和上级章节，跨论文问题里两篇文章的「实验设置」
     * 在 ReRanker 眼中完全一样。完整路径同时提供论文身份和章节层级，
     * 长度只有百字量级，不会挤掉正文。</p>
     */
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

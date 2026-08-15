package com.llmstudy.rag.module.rag.rerank;

import com.llmstudy.rag.module.rag.model.RetrievalCandidate;

import java.util.List;

/**
 * BGE 重排结果。
 *
 * <p>{@link #used()} 为 true 时候选已按 BGE 分排序并写入 {@code bgeScore}；
 * 否则候选保持输入顺序且无 bgeScore，{@link #reason()} 说明回退原因。</p>
 */
public record RerankResult(boolean used, String reason, long elapsedMs,
                           List<RetrievalCandidate> candidates) {

    public RerankResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /** BGE 成功打分并排序。 */
    public static RerankResult success(List<RetrievalCandidate> candidates, long elapsedMs) {
        return new RerankResult(true, null, elapsedMs, candidates);
    }

    /**
     * 回退 RRF 顺序。
     *
     * @param reason 稳定短码，如 {@code disabled}、{@code too-few-candidates}、
     *               {@code score-count-mismatch}、{@code invalid-score}、
     *               {@code invalid-min-score}、{@code inference-error}
     */
    public static RerankResult fallback(String reason, long elapsedMs,
                                        List<RetrievalCandidate> candidates) {
        return new RerankResult(false, reason, elapsedMs, candidates);
    }
}

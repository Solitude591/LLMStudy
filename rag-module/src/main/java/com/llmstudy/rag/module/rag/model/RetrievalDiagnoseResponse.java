package com.llmstudy.rag.module.rag.model;

import java.util.List;

/**
 * 检索诊断快照。放在 rag.model 而不是 dto，避免 Pipeline 反向依赖 HTTP 层。
 *
 * <p>各阶段列表的 {@code rank} 就是该阶段排序下标 + 1：
 * {@code grouped.rank} 为 retrievalRank，{@code bge.rank} 为 bgeRank。
 * {@code traceId} 在链路开始前生成，并写入 MDC / LLM 日志上下文。</p>
 */
public record RetrievalDiagnoseResponse(
        String traceId,
        QueryPlan queryPlan,
        List<Lane> lanes,
        List<Hit> rrf,
        List<Hit> grouped,
        List<Hit> bge,
        List<Hit> finalRanked,
        List<Expand> parentExpand,
        List<Hit> finalCandidates,
        boolean bm25Degraded,
        boolean knnDegraded,
        boolean bgeUsed,
        String bgeReason,
        long bgeElapsedMs,
        String bgeQuery,
        StageTimings timings,
        List<String> failures) {

    public RetrievalDiagnoseResponse {
        lanes = lanes == null ? List.of() : List.copyOf(lanes);
        rrf = rrf == null ? List.of() : List.copyOf(rrf);
        grouped = grouped == null ? List.of() : List.copyOf(grouped);
        bge = bge == null ? List.of() : List.copyOf(bge);
        finalRanked = finalRanked == null ? List.of() : List.copyOf(finalRanked);
        parentExpand = parentExpand == null ? List.of() : List.copyOf(parentExpand);
        finalCandidates = finalCandidates == null ? List.of() : List.copyOf(finalCandidates);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    public record QueryPlan(String originalQuestion, String standaloneZh, String standaloneEn) {
    }

    public record Lane(String channel, String query, boolean skipped, String error,
                       long elapsedMs, List<Hit> hits) {
        public Lane {
            hits = hits == null ? List.of() : List.copyOf(hits);
        }
    }

    /**
     * 单条候选在某一阶段的分数快照。
     *
     * <p>raw / RRF / BGE / final 使用独立字段；某阶段尚未计算的分数为 null。</p>
     */
    public record Hit(String id, String groupId, String docId, String versionId,
                      String chunkId, String headerPath, String sourceUrl,
                      Integer pageStart, Integer pageEnd,
                      Double rawScore, Double rrfScore,
                      Double bgeScore, Double finalScore, int rank, String text) {
    }

    /** 基于 {@code System.nanoTime()} 采集的检索阶段墙钟耗时。 */
    public record StageTimings(long queryRewriteMs,
                               long parallelRecallMs,
                               long rrfParentGroupingMs,
                               long bgeRerankMs,
                               long parentExpandSelectionMs,
                               long totalRetrievalMs) {
    }

    /** parent 展开或补位时的 child → 输出关系。 */
    public record Expand(String childId, String outputId, String action) {
    }
}

package com.llmstudy.rag.module.rag.model;

import com.llmstudy.rag.module.rag.query.QueryRewriteStrategy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 一次查询改写得到的检索计划。
 *
 * <p>中英文独立查询是所有策略共有的主查询，BM25/KNN 都使用它们；
 * 中英文规范化后完全相同时后续召回会去重，避免同一文本贡献两次 RRF。</p>
 *
 * <p>{@code expansions} 是策略额外产出的检索文本：MULTI_QUERY 的同义改写、
 * DECOMPOSE 的子问题、HYDE 的假设答案。它们各自作为独立通道参与 RRF，
 * 而不是拼进主查询——拼接会稀释主查询的词面，独立通道则只能加分不能减分。</p>
 *
 * @param originalQuestion 用户原文，始终作为一路参与融合，兜住改写跑偏的情况
 * @param expansions       去重后的额外检索文本，可能为空
 */
public record RetrievalQueryPlan(
        String originalQuestion,
        String standaloneZh,
        String standaloneEn,
        QueryRewriteStrategy strategy,
        List<String> expansions) {

    /** 单次请求最多参与融合的额外通道数，用于兜住延迟。 */
    public static final int MAX_EXPANSIONS = 3;

    public RetrievalQueryPlan {
        if (originalQuestion == null || originalQuestion.isBlank()
                || standaloneZh == null || standaloneZh.isBlank()
                || standaloneEn == null || standaloneEn.isBlank()) {
            throw new IllegalArgumentException("原问题和中英文独立查询不能为空");
        }
        originalQuestion = originalQuestion.trim();
        standaloneZh = standaloneZh.trim();
        standaloneEn = standaloneEn.trim();
        strategy = strategy == null ? QueryRewriteStrategy.DIRECT : strategy;
        expansions = normalize(expansions, originalQuestion, standaloneZh, standaloneEn);
    }

    /** 兼容入口：无策略路由的直接改写。 */
    public RetrievalQueryPlan(String originalQuestion, String standaloneZh, String standaloneEn) {
        this(originalQuestion, standaloneZh, standaloneEn, QueryRewriteStrategy.DIRECT, List.of());
    }

    /**
     * 去空白、去重、剔除与主查询重复的项，并截断到 {@link #MAX_EXPANSIONS}。
     *
     * <p>与主查询重复的扩展会让同一文本贡献两次 RRF，等于凭空加权，必须去掉。</p>
     */
    private static List<String> normalize(List<String> expansions, String... primary) {
        if (expansions == null || expansions.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>(List.of(primary));
        List<String> kept = new ArrayList<>(MAX_EXPANSIONS);
        for (String expansion : expansions) {
            if (expansion == null || expansion.isBlank()) {
                continue;
            }
            String trimmed = expansion.trim();
            if (seen.add(trimmed)) {
                kept.add(trimmed);
            }
            if (kept.size() == MAX_EXPANSIONS) {
                break;
            }
        }
        return List.copyOf(kept);
    }

    /** 中英文独立查询去掉首尾空白后是否完全相同。 */
    public boolean duplicateLanguage() {
        return standaloneZh.equals(standaloneEn);
    }

    /**
     * 去重后的主检索文本，保序。
     *
     * <p>embedding 批量编码和 BM25/KNN 去重都复用本方法，避免两处规则漂移。</p>
     */
    public List<String> uniqueQueries() {
        return duplicateLanguage() ? List.of(standaloneZh) : List.of(standaloneZh, standaloneEn);
    }

    /**
     * 主查询之外、需要各自成为一路召回的检索文本。
     *
     * <p>原问题排在最前：它没有经过模型改写，是唯一不会引入幻觉实体的通道。</p>
     */
    public List<String> fusionQueries() {
        List<String> queries = new ArrayList<>(MAX_EXPANSIONS + 1);
        if (!uniqueQueries().contains(originalQuestion)) {
            queries.add(originalQuestion);
        }
        queries.addAll(expansions);
        return List.copyOf(queries);
    }
}

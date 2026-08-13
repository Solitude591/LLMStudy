package com.llmstudy.rag.module.rag.model;

import java.util.List;

/**
 * 一次查询改写得到的中英文独立检索查询。
 *
 * <p>不再保留单值改写字符串或子查询。BM25/KNN 都使用这两个独立查询；
 * 中英文规范化后完全相同时后续召回会去重，避免同一文本贡献两次 RRF。</p>
 */
public record RetrievalQueryPlan(
        String originalQuestion, String standaloneZh, String standaloneEn) {

    public RetrievalQueryPlan {
        if (originalQuestion == null || originalQuestion.isBlank()
                || standaloneZh == null || standaloneZh.isBlank()
                || standaloneEn == null || standaloneEn.isBlank()) {
            throw new IllegalArgumentException("原问题和中英文独立查询不能为空");
        }
        originalQuestion = originalQuestion.trim();
        standaloneZh = standaloneZh.trim();
        standaloneEn = standaloneEn.trim();
    }

    /** 中英文独立查询去掉首尾空白后是否完全相同。 */
    public boolean duplicateLanguage() {
        return standaloneZh.equals(standaloneEn);
    }

    /**
     * 去重后的检索文本，保序。
     *
     * <p>embedding 批量编码和 BM25/KNN 去重都复用本方法，避免两处规则漂移。</p>
     */
    public List<String> uniqueQueries() {
        return duplicateLanguage() ? List.of(standaloneZh) : List.of(standaloneZh, standaloneEn);
    }
}

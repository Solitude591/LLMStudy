package com.llmstudy.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 两路召回与排序阈值。
 *
 * <p>只提供参数，不提供新旧检索链路开关。top-n 从 {@code rag.reranker} 迁到此处，
 * 避免排序截断和 BGE 模型路径混在同一前缀下。</p>
 */
@ConfigurationProperties(prefix = "rag.retrieval")
public class RetrievalProperties {

    /** 每一路 BM25/KNN 的召回条数。 */
    private int perQueryTopK = 10;

    /** 两路 RRF 融合后进入 parent 分组的上限。 */
    private int fusionCandidateCount = 40;

    /** parent 分组后进入 BGE 的代表 child 上限。 */
    private int rerankCandidateCount = 20;

    /** 展开 parent 并补位后的最终证据条数。 */
    private int topN = 5;

    /** RRF 与最终名次融合共用的 k。 */
    private int rrfK = 60;

    /** 最终名次中 BGE 名次项的权重。 */
    private double bgeRankWeight = 0.85;

    /** 最终名次中检索（分组后 RRF）名次项的权重。 */
    private double retrievalRerankWeight = 0.15;

    public int getPerQueryTopK() {
        return perQueryTopK;
    }

    public void setPerQueryTopK(int perQueryTopK) {
        this.perQueryTopK = perQueryTopK;
    }

    public int getFusionCandidateCount() {
        return fusionCandidateCount;
    }

    public void setFusionCandidateCount(int fusionCandidateCount) {
        this.fusionCandidateCount = fusionCandidateCount;
    }

    public int getRerankCandidateCount() {
        return rerankCandidateCount;
    }

    public void setRerankCandidateCount(int rerankCandidateCount) {
        this.rerankCandidateCount = rerankCandidateCount;
    }

    public int getTopN() {
        return topN;
    }

    public void setTopN(int topN) {
        this.topN = topN;
    }

    public int getRrfK() {
        return rrfK;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    public double getBgeRankWeight() {
        return bgeRankWeight;
    }

    public void setBgeRankWeight(double bgeRankWeight) {
        this.bgeRankWeight = bgeRankWeight;
    }

    public double getRetrievalRerankWeight() {
        return retrievalRerankWeight;
    }

    public void setRetrievalRerankWeight(double retrievalRerankWeight) {
        this.retrievalRerankWeight = retrievalRerankWeight;
    }
}

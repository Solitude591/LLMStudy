package com.llmstudy.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地 BGE ReRanker 配置。
 *
 * <p>模型文件放在配置指定的外部目录，不打进 JAR；下载模型前保持 enabled=false，
 * 检索器在模型禁用时跳过重排直接返回原排序。</p>
 */
@ConfigurationProperties(prefix = "rag.reranker")
public class RerankerProperties {

    /** 是否启用本地 BGE ReRanker；模型未下载时保持 false。 */
    private boolean enabled = false;

    /** ONNX 模型文件路径。 */
    private String modelPath = "./models/bge-reranker-v2-m3/model_int8.onnx";

    /** DJL Hugging Face tokenizer 所在目录。 */
    private String tokenizerPath = "./models/bge-reranker-v2-m3";

    /** pair 分词最大长度，超过部分截断。 */
    private int maxLength = 512;

    /** 推理批次大小，批内按最长序列动态 padding。 */
    private int batchSize = 4;

    /** RRF 融合后进入 ReRanker 的候选条数上限。 */
    private int candidateCount = 10;

    /** ReRanker 之后最终返回给上层的条数。 */
    private int topN = 8;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModelPath() {
        return modelPath;
    }

    public void setModelPath(String modelPath) {
        this.modelPath = modelPath;
    }

    public String getTokenizerPath() {
        return tokenizerPath;
    }

    public void setTokenizerPath(String tokenizerPath) {
        this.tokenizerPath = tokenizerPath;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getCandidateCount() {
        return candidateCount;
    }

    public void setCandidateCount(int candidateCount) {
        this.candidateCount = candidateCount;
    }

    public int getTopN() {
        return topN;
    }

    public void setTopN(int topN) {
        this.topN = topN;
    }
}

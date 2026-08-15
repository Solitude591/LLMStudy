package com.llmstudy.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地 BGE ReRanker 配置。
 *
 * <p>模型路径同时支持 {@code classpath:} 资源与外部文件，默认使用
 * JAR 同级的 {@code models/bge-reranker} 目录；禁用时检索器
 * 跳过重排，直接保留原排序。</p>
 */
@ConfigurationProperties(prefix = "rag.reranker")
public class RerankerProperties {

    /** 是否启用本地 BGE ReRanker；可在低资源环境显式关闭。 */
    private boolean enabled = true;

    /** ONNX 模型文件路径。 */
    private String modelPath =
            "./models/bge-reranker/model_quantized.onnx";

    /** DJL Hugging Face tokenizer.json 路径，也可指向包含该文件的外部目录。 */
    private String tokenizerPath =
            "./models/bge-reranker/tokenizer.json";

    /** pair 分词最大长度，超过部分截断。 */
    private int maxLength = 512;

    /** 推理批次大小，批内按最长序列动态 padding。 */
    private int batchSize = 4;

    /** 低于该 sigmoid 分数的候选不进入 LLM 上下文。 */
    private double minScore = 0.6;

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

    public double getMinScore() {
        return minScore;
    }

    public void setMinScore(double minScore) {
        this.minScore = minScore;
    }
}

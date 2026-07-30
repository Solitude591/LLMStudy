package com.llmstudy.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinerU Markdown 父子分片参数。
 *
 * <p>实际值由 application.yml 中的 rag.splitter.markdown 配置绑定，
 * 默认值用于配置缺失时保证应用仍可启动。</p>
 */
@ConfigurationProperties(prefix = "rag.splitter.markdown")
public class MarkdownSplitterProperties {

    /** 单个子分片允许的最大字符数。 */
    private int chunkSize = 1000;

    /** 相邻子分片之间重复保留的字符数。 */
    private int chunkOverlap = 100;

    /** 每次调用 Embedding API 批量发送的文本数上限，避免请求体过大或触发限流。 */
    private int embeddingBatchSize = 10;

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    public int getEmbeddingBatchSize() {
        return embeddingBatchSize;
    }

    public void setEmbeddingBatchSize(int embeddingBatchSize) {
        this.embeddingBatchSize = embeddingBatchSize;
    }
}

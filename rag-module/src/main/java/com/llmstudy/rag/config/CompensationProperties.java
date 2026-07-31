package com.llmstudy.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 文档处理失败补偿任务参数。
 *
 * <p>实际值由 application.yml 中的 rag.compensation 配置绑定，
 * 默认值用于配置缺失时保证应用仍可启动。</p>
 */
@ConfigurationProperties(prefix = "rag.compensation")
public class CompensationProperties {

    /** 补偿任务执行 cron 表达式，默认每 5 分钟扫描一次。 */
    private String cron = "0 */5 * * * ?";

    /** 单个文档最大自动重试次数，达到上限后停止自动补偿，等待人工处理。 */
    private int maxRetryCount = 3;

    /** 单轮扫描最多处理的文档数，防止一次性拉起大量失败文档打爆外部服务。 */
    private int batchSize = 10;

    /** 失败后的冷却时间，失败未满该时长不参与补偿，避免与原处理流程竞争。 */
    private Duration retryDelay = Duration.ofMinutes(5);

    /** 中间态（converting/splitting/vectoring）卡死判定阈值，超过则回退重试。 */
    private Duration staleTimeout = Duration.ofMinutes(30);

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public int getMaxRetryCount() {
        return maxRetryCount;
    }

    public void setMaxRetryCount(int maxRetryCount) {
        this.maxRetryCount = maxRetryCount;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getRetryDelay() {
        return retryDelay;
    }

    public void setRetryDelay(Duration retryDelay) {
        this.retryDelay = retryDelay;
    }

    public Duration getStaleTimeout() {
        return staleTimeout;
    }

    public void setStaleTimeout(Duration staleTimeout) {
        this.staleTimeout = staleTimeout;
    }
}

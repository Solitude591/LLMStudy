package com.llmstudy.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 视觉模型配置（OpenAI-compatible 多模态接口）。
 */
@ConfigurationProperties(prefix = "vision")
public class VisionProperties {

    /** chat/completions 接口地址 */
    private String apiUrl;

    /** API Key */
    private String apiKey;

    /** 模型名，需支持 image_url 多模态输入 */
    private String model;

    /** 单次请求超时（秒） */
    private int timeoutSeconds = 60;

    /** 描述最大输出 token 数 */
    private int maxTokens = 300;

    /** 采样温度，描述任务取低值保证稳定 */
    private double temperature = 0.2;

    /** 并发调用数；图片多时串行会拖垮整体耗时 */
    private int concurrency = 4;

    /** 单张图片失败重试次数 */
    private int maxRetries = 2;

    /**
     * 是否启用视觉描述。未配置 apiUrl/apiKey/model 时自动视为关闭，
     * 解析流程照常进行，仅退化为使用 PDF 原文图注。
     */
    public boolean isEnabled() {
        return isNotBlank(apiUrl) && isNotBlank(apiKey) && isNotBlank(model);
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    // ========== Getters & Setters ==========

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
}

package com.llmstudy.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mineru")
public class MineruProperties {

    /** MinerU API 地址 */
    private String apiUrl = "https://mineru.net/api/v4/extract/task";

    /** API Token */
    private String token;

    /** 模型版本：vlm / fast */
    private String modelVersion = "vlm";

    /** 单次轮询间隔（秒） */
    private int pollIntervalSeconds = 3;

    /** 最大等待时间（秒） */
    private int maxWaitSeconds = 120;

    /** 单张图片最大字节数，默认 10MB；超限跳过该图，不影响整篇解析 */
    private long maxImageBytes = 10L * 1024 * 1024;

    /** 单文档最多提取图片数，默认 200；超出部分跳过 */
    private int maxImageCount = 200;

    /** ZIP 内图片总字节数上限，默认 200MB；超限停止提取剩余图片 */
    private long maxTotalImageBytes = 200L * 1024 * 1024;

    /** Markdown 最大字节数，防止异常 ZIP 条目耗尽堆内存 */
    private long maxMarkdownBytes = 20L * 1024 * 1024;

    /** content_list.json 最大字节数 */
    private long maxContentListBytes = 50L * 1024 * 1024;

    public long getMaxImageBytes() {
        return maxImageBytes;
    }

    public void setMaxImageBytes(long maxImageBytes) {
        this.maxImageBytes = maxImageBytes;
    }

    public int getMaxImageCount() {
        return maxImageCount;
    }

    public void setMaxImageCount(int maxImageCount) {
        this.maxImageCount = maxImageCount;
    }

    public long getMaxTotalImageBytes() {
        return maxTotalImageBytes;
    }

    public void setMaxTotalImageBytes(long maxTotalImageBytes) {
        this.maxTotalImageBytes = maxTotalImageBytes;
    }

    public long getMaxMarkdownBytes() {
        return maxMarkdownBytes;
    }

    public void setMaxMarkdownBytes(long maxMarkdownBytes) {
        this.maxMarkdownBytes = maxMarkdownBytes;
    }

    public long getMaxContentListBytes() {
        return maxContentListBytes;
    }

    public void setMaxContentListBytes(long maxContentListBytes) {
        this.maxContentListBytes = maxContentListBytes;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public int getPollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    public void setPollIntervalSeconds(int pollIntervalSeconds) {
        this.pollIntervalSeconds = pollIntervalSeconds;
    }

    public int getMaxWaitSeconds() {
        return maxWaitSeconds;
    }

    public void setMaxWaitSeconds(int maxWaitSeconds) {
        this.maxWaitSeconds = maxWaitSeconds;
    }
}

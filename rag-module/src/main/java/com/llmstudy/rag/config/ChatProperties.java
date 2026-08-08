package com.llmstudy.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 聊天模块的通用业务参数。
 *
 * <p>这类参数会随产品策略或部署环境调整，因此统一通过 {@code rag.chat}
 * 从配置文件绑定；数据库字段长度等固定技术约束不放在这里。</p>
 */
@ConfigurationProperties(prefix = "rag.chat")
public class ChatProperties {

    /** 每次请求最多加载的历史消息数量，同时作为 Redis 滑动窗口大小。 */
    private int historyLimit = 10;

    /** AI 标题生成完成前，使用首次问题生成的临时标题最大字符数。 */
    private int initialTitleMaxLength = 10;

    /** Redis 历史窗口缓存 TTL（秒），会话闲置超过该时长后缓存自然淘汰。 */
    private int historyCacheTtlSeconds = 86400;

    public int getHistoryLimit() {
        return historyLimit;
    }

    public void setHistoryLimit(int historyLimit) {
        if (historyLimit <= 0) {
            throw new IllegalArgumentException("rag.chat.history-limit 必须大于 0");
        }
        this.historyLimit = historyLimit;
    }

    public int getInitialTitleMaxLength() {
        return initialTitleMaxLength;
    }

    public void setInitialTitleMaxLength(int initialTitleMaxLength) {
        if (initialTitleMaxLength <= 0) {
            throw new IllegalArgumentException(
                    "rag.chat.initial-title-max-length 必须大于 0");
        }
        this.initialTitleMaxLength = initialTitleMaxLength;
    }

    public int getHistoryCacheTtlSeconds() {
        return historyCacheTtlSeconds;
    }

    public void setHistoryCacheTtlSeconds(int historyCacheTtlSeconds) {
        if (historyCacheTtlSeconds <= 0) {
            throw new IllegalArgumentException(
                    "rag.chat.history-cache-ttl-seconds 必须大于 0");
        }
        this.historyCacheTtlSeconds = historyCacheTtlSeconds;
    }
}

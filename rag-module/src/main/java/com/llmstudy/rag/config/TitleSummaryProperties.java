package com.llmstudy.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话标题生成参数。
 *
 * <p>实际值由 application.yml 中的 rag.chat.title-summary 配置绑定，
 * 模型调用复用 spring.ai.openai 的 api-key/base-url，这里只指定轻量模型。</p>
 */
@ConfigurationProperties(prefix = "rag.chat.title-summary")
public class TitleSummaryProperties {

    /** 标题生成使用的轻量模型名；留空则沿用主对话模型。 */
    private String model = "";

    /** 生成标题的最大字符数，超过则截断。 */
    private int maxTitleLength = 20;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxTitleLength() {
        return maxTitleLength;
    }

    public void setMaxTitleLength(int maxTitleLength) {
        if (maxTitleLength <= 0) {
            throw new IllegalArgumentException(
                    "rag.chat.title-summary.max-title-length 必须大于 0");
        }
        this.maxTitleLength = maxTitleLength;
    }
}

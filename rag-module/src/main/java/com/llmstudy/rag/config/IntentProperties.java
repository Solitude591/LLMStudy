package com.llmstudy.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 意图识别模型参数。 */
@ConfigurationProperties(prefix = "rag.chat.intent")
public class IntentProperties {

    /** 可选的轻量模型名；留空时复用主聊天模型。 */
    private String model = "";

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null ? "" : model.trim();
    }
}

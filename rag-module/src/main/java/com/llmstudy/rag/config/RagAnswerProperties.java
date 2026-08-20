package com.llmstudy.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** RAG 最终回答的独立生成参数。 */
@ConfigurationProperties(prefix = "rag.answer")
public class RagAnswerProperties {

    /** 科研事实问答默认确定性生成，降低同一证据下的措辞和正确性抖动。 */
    private double temperature = 0.0;

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }
}

package com.llmstudy.rag.module.rag.rerank;

import com.llmstudy.rag.config.RerankerProperties;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用源码目录中真实 ONNX 模型的可选冒烟测试。 */
class BgeScoringModelIntegrationTest {

    @Test
    @EnabledIfSystemProperty(named = "bge.integration-test", matches = "true")
    void scoresRelevantTextHigherWithExternalModelFiles() {
        RerankerProperties properties = new RerankerProperties();
        properties.setEnabled(true);
        properties.setBatchSize(2);
        properties.setModelPath("./models/bge-reranker/model_quantized.onnx");
        properties.setTokenizerPath("./models/bge-reranker/tokenizer.json");

        try (BgeScoringModel model = new BgeScoringModel(
                properties, new DefaultResourceLoader())) {
            List<Double> scores = model.scoreAll(List.of(
                    TextSegment.from("成熟的苹果通常是红色或绿色的。"),
                    TextSegment.from("海洋占地球表面的大部分面积。")),
                    "苹果是什么颜色？").content();

            assertEquals(2, scores.size());
            assertTrue(scores.stream().allMatch(
                    score -> Double.isFinite(score) && score >= 0 && score <= 1));
            assertTrue(scores.get(0) > scores.get(1));
        }
    }
}

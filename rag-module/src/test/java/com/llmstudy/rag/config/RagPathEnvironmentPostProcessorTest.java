package com.llmstudy.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagPathEnvironmentPostProcessorTest {

    @Test
    void moduleRootIsRagModule() {
        Path root = RagPathEnvironmentPostProcessor.moduleRoot();
        assertEquals("rag-module", root.getFileName().toString());
    }

    @Test
    void rebasesRelativeLogsAndModels() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("rag.reranker.model-path", "./models/bge-reranker/model_quantized.onnx");
        new RagPathEnvironmentPostProcessor().postProcessEnvironment(env, null);
        Path root = RagPathEnvironmentPostProcessor.moduleRoot();
        assertEquals(root.resolve("logs").toString(), env.getProperty("rag.llm-log.path"));
        assertEquals(
                root.resolve("./models/bge-reranker/model_quantized.onnx").normalize().toString(),
                env.getProperty("rag.reranker.model-path"));
    }

    @Test
    void keepsExplicitAbsoluteLogPath() {
        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                RagPathEnvironmentPostProcessor.LLM_LOG_PATH, "/tmp/custom-logs")));
        new RagPathEnvironmentPostProcessor().postProcessEnvironment(env, null);
        assertEquals("/tmp/custom-logs", env.getProperty(RagPathEnvironmentPostProcessor.LLM_LOG_PATH));
    }
}

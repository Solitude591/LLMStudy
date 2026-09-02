package com.llmstudy.rag.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagTuningDefaultsTest {

    @Test
    void defaultsLimitFinalEvidenceAndRejectLowRerankerScores() {
        assertEquals(5, new RetrievalProperties().getTopN());
        assertEquals(0.6, new RerankerProperties().getMinScore());
    }

    @Test
    void exampleConfigurationUsesSameTuningDefaults() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/application.example.yml")) {
            assertNotNull(input);
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(yaml.contains("top-n: ${RAG_RETRIEVAL_TOP_N:5}"));
            assertTrue(yaml.contains("BGE_RERANKER_MIN_SCORE:0.6"));
        }
    }
}

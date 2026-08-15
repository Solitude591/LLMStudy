package com.llmstudy.rag.config;

import com.llmstudy.rag.module.llm.LlmTraceContext;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetrievalExecutionConfigTest {

    @Test
    void retrievalWorkerInheritsDiagnosticMdc() {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor)
                new RetrievalExecutionConfig().retrievalExecutor();
        MDC.put(LlmTraceContext.MDC_TRACE_ID, "trace-1");
        try {
            String observed = CompletableFuture.supplyAsync(
                    () -> MDC.get(LlmTraceContext.MDC_TRACE_ID), executor).join();
            assertEquals("trace-1", observed);
        } finally {
            MDC.remove(LlmTraceContext.MDC_TRACE_ID);
            executor.shutdown();
        }
    }
}

package com.llmstudy.rag.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/** RAG 两路检索专用执行器，避免阻塞全局异步任务。 */
@Configuration
public class RetrievalExecutionConfig {

    @Bean("retrievalExecutor")
    public Executor retrievalExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("rag-retrieval-");
        // 一次检索现在最多发起 2 主路 + 8 扩展路，全是 ES/embedding 的 IO 等待。
        // 核心线程数低于并发路数时，多余的路会排队，把并行召回退化成串行。
        executor.setCorePoolSize(12);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(100);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        // 诊断 traceId 需要跨越 BM25/KNN 工作线程，才能继续串联 ES 日志。
        executor.setTaskDecorator(task -> {
            Map<String, String> callerMdc = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> workerMdc = MDC.getCopyOfContextMap();
                if (callerMdc == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(callerMdc);
                }
                try {
                    task.run();
                } finally {
                    if (workerMdc == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(workerMdc);
                    }
                }
            };
        });
        executor.initialize();
        return executor;
    }
}

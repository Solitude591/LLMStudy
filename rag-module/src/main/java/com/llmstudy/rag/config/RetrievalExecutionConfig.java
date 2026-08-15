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
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
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

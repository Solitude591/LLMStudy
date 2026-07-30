package com.llmstudy.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步任务配置。
 *
 * <p>为事件驱动的文档处理流水线提供独立的线程池，
 * 避免异步解析/分片/向量化占用 Tomcat 请求线程。</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 文档处理专用线程池。
     *
     * <p>核心线程数 2、最大 4，队列容量 100：同一时刻最多 4 个文档在执行处理，
     * 超出排队等待。文档处理是低频高耗时任务，不需要大量线程。</p>
     */
    @Bean("documentProcessingExecutor")
    public Executor documentProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数——日常空闲时保持的最低线程数
        executor.setCorePoolSize(2);
        // 最大线程数——队列满时才扩容到该值
        executor.setMaxPoolSize(4);
        // 队列容量——核心线程忙时，新任务先排队
        executor.setQueueCapacity(100);
        // 线程名前缀，方便日志排查
        executor.setThreadNamePrefix("doc-processing-");
        // 线程空闲超过 60 秒则回收，回到 corePoolSize
        executor.setKeepAliveSeconds(60);
        // 线程池关闭时等待队列中的任务执行完
        executor.setWaitForTasksToCompleteOnShutdown(true);
        // 最多等待 60 秒
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}

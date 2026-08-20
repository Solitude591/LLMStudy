package com.llmstudy.rag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 聊天模块配置。
 *
 * <p>统一注册聊天与标题生成参数，并提供由 Spring 管理生命周期的标题生成执行器。</p>
 */
@Configuration
@EnableConfigurationProperties({
        ChatProperties.class,
        TitleSummaryProperties.class,
        IntentProperties.class,
        RagAnswerProperties.class
})
public class ChatConfig {

    /**
     * 创建标题生成专用的虚拟线程执行器。
     *
     * <p>模型调用属于阻塞型网络 I/O，虚拟线程可以避免占用请求线程；执行器作为 Bean
     * 交给 Spring 管理，应用关闭时会调用 {@link ExecutorService#close()} 等待任务收尾。</p>
     */
    @Bean(name = "titleSummaryExecutor", destroyMethod = "close")
    public ExecutorService titleSummaryExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

package com.llmstudy.rag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时调度与补偿任务配置。
 *
 * <p>开启 Spring 定时调度能力，并注册补偿任务参数属性。</p>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(CompensationProperties.class)
public class CompensationConfig {
}

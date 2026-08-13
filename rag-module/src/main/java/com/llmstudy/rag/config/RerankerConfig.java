package com.llmstudy.rag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册检索排序阈值与 BGE ReRanker 模型路径。
 */
@Configuration
@EnableConfigurationProperties({RerankerProperties.class, RetrievalProperties.class})
public class RerankerConfig {
}

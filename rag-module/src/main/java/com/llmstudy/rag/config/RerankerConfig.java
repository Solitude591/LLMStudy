package com.llmstudy.rag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 rag.reranker 配置属性，供检索器与 BGE ReRanker 模型读取。
 */
@Configuration
@EnableConfigurationProperties(RerankerProperties.class)
public class RerankerConfig {
}

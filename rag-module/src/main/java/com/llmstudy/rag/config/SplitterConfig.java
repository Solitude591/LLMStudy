package com.llmstudy.rag.config;

import com.llmstudy.rag.module.knowledge.ingestion.chunk.MarkdownHeaderChunker;
import com.llmstudy.rag.util.SnowflakeIdGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文档分片器配置。
 */
@Configuration
@EnableConfigurationProperties(MarkdownSplitterProperties.class)
public class SplitterConfig {

    /**
     * 使用外部配置创建 Markdown 分片器，业务 Service 不再决定分片大小。
     */
    @Bean
    public MarkdownHeaderChunker markdownHeaderChunker(
            MarkdownSplitterProperties properties,
            SnowflakeIdGenerator idGenerator) {
        return new MarkdownHeaderChunker(
                idGenerator,
                properties.getChunkSize(),
                properties.getChunkOverlap());
    }
}

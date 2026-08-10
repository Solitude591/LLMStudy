package com.llmstudy.rag.config;

import com.llmstudy.rag.module.knowledge.ingestion.chunk.ContentListPaperChunker;
import com.llmstudy.rag.module.knowledge.ingestion.chunk.MarkdownAstPaperChunker;
import com.llmstudy.rag.module.knowledge.ingestion.image.MarkdownImageProcessor;
import com.llmstudy.rag.util.SnowflakeIdGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 文档分片器配置：尺寸来自配置，业务 Service 不硬编码 chunkSize。 */
@Configuration
@EnableConfigurationProperties(MarkdownSplitterProperties.class)
public class SplitterConfig {

    @Bean
    public ContentListPaperChunker contentListPaperChunker(
            MarkdownSplitterProperties properties,
            SnowflakeIdGenerator idGenerator,
            MarkdownImageProcessor imageProcessor) {
        return new ContentListPaperChunker(
                idGenerator,
                imageProcessor,
                properties.getChunkSize(),
                properties.getChunkOverlap());
    }

    @Bean
    public MarkdownAstPaperChunker markdownAstPaperChunker(
            MarkdownSplitterProperties properties,
            SnowflakeIdGenerator idGenerator) {
        return new MarkdownAstPaperChunker(
                idGenerator,
                properties.getChunkSize(),
                properties.getChunkOverlap());
    }
}

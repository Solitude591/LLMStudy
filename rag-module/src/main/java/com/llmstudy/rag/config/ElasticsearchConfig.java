package com.llmstudy.rag.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchConfigurationKnn;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.io.IOException;

@Configuration
@EnableConfigurationProperties(ElasticsearchProperties.class)
public class ElasticsearchConfig {

    @Autowired
    private ElasticsearchProperties elasticsearchProperties;

    @Bean
    public OpenAiEmbeddingModel openAiEmbeddingModel() {
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(elasticsearchProperties.getModelName())
                .dimensions(elasticsearchProperties.getDimensions())
                .baseUrl(elasticsearchProperties.getBaseUrl())
                .apiKey(elasticsearchProperties.getApiKey())
                .maxRetries(5)
                .build();
        return OpenAiEmbeddingModel.builder()
                .options(options)
                .build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public RestClient restClient() {
        return RestClient
                .builder(HttpHost.create(elasticsearchProperties.getHost()))
                .build();
    }

    /**
     * 暴露基于现有 RestClient 的 Elasticsearch Java 客户端。
     *
     * <p>LangChain4j 向量存储内部自建客户端，不对外暴露；混合检索的 BM25
     * 通道需要直接发起 match 查询，因此这里复用同一个 RestClient 构造客户端，
     * 与向量存储共用底层连接。销毁交由 RestClient 的 close 方法负责。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public ElasticsearchClient elasticsearchClient(RestClient restClient) {
        return new ElasticsearchClient(
                new RestClientTransport(restClient, new JacksonJsonpMapper()));
    }


    @Bean
    @ConditionalOnMissingBean
    @Primary
    public ElasticsearchEmbeddingStore elasticsearchEmbeddingStore(RestClient restClient) {
        return ElasticsearchEmbeddingStore.builder()
                .restClient(restClient)
                .indexName(elasticsearchProperties.getIndexName())
                .build();
    }
}

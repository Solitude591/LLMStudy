package com.llmstudy.rag.module;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ShardStatistics;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.TotalHits;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import co.elastic.clients.util.ObjectBuilder;
import co.elastic.clients.util.ObjectBuilder;
import com.llmstudy.rag.config.ElasticsearchProperties;
import com.llmstudy.rag.config.RerankerProperties;
import com.llmstudy.rag.entity.KnowledgeSegment;
import com.llmstudy.rag.enums.SegmentStatus;
import com.llmstudy.rag.mapper.KnowledgeSegmentMapper;
import com.llmstudy.rag.service.splitter.MarkdownHeaderParentTextSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.elasticsearch.Document;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RAGElasticsearchContentRetriever 的单元测试，覆盖混合检索的融合、去重、
 * 降级与异常行为。所有 ES 与 Embedding 依赖均使用 Mockito 模拟，不依赖真实环境。
 */
class RAGElasticsearchContentRetrieverTest {

    private static final String INDEX_NAME = "know-engine";

    private OpenAiEmbeddingModel embeddingModel;
    private ElasticsearchEmbeddingStore embeddingStore;
    private ElasticsearchClient elasticsearchClient;
    private KnowledgeSegmentMapper segmentMapper;
    private StringRedisTemplate stringRedisTemplate;
    private JsonMapper jsonMapper;
    private RerankerProperties rerankerProperties;
    private ScoringModel scoringModel;
    private RAGElasticsearchContentRetriever retriever;

    @BeforeEach
    void setUp() {
        embeddingModel = mock(OpenAiEmbeddingModel.class);
        embeddingStore = mock(ElasticsearchEmbeddingStore.class);
        elasticsearchClient = mock(ElasticsearchClient.class);
        segmentMapper = mock(KnowledgeSegmentMapper.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        // 使用真实 JsonMapper，让父分片缓存的序列化/反序列化走真实逻辑。
        jsonMapper = new JsonMapper();
        // ReRanker 默认关闭，相关测试再单独开启。
        rerankerProperties = new RerankerProperties();
        scoringModel = mock(ScoringModel.class);
        ElasticsearchProperties properties = new ElasticsearchProperties();
        properties.setIndexName(INDEX_NAME);
        retriever = new RAGElasticsearchContentRetriever(
                embeddingModel, embeddingStore, elasticsearchClient, properties,
                segmentMapper, stringRedisTemplate, jsonMapper,
                rerankerProperties, scoringModel);
    }

    @Test
    void 两路都成功时_同文档去重并按RRF分数降序返回() throws Exception {
        // BM25 命中顺序即排名：A(1)、B(2)、C(3)
        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenReturn(bm25Response(
                        docHit("A", "文档A", 2.0, Map.of()),
                        docHit("B", "文档B", 1.5, Map.of()),
                        docHit("C", "文档C", 1.0, Map.of())));
        // KNN 命中顺序：B(1)、D(2)、A(3)，B 和 A 与 BM25 重合
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(knnMatches(
                        knnDoc("B", "文档B", 0.9),
                        knnDoc("D", "文档D", 0.8),
                        knnDoc("A", "文档A", 0.7))));

        List<Content> contents = retriever.retrieve(queryWithOriginal("改写问题", "原始问题"));

        // 详细输出融合后排名，便于在控制台核对去重和 RRF 分数。
        printRetrievedContents("两路成功：RRF 融合与去重",
                "原始问题", "改写问题", contents);

        // 20 路候选去重后只有 4 个不同文档，A/B 虽被两路命中但只返回一次
        assertEquals(4, contents.size());
        // RRF：B = 1/62 + 1/61 > A = 1/61 + 1/63，因此 B 第一、A 第二
        assertEquals("B", contents.get(0).metadata().get(ContentMetadata.EMBEDDING_ID));
        assertEquals("A", contents.get(1).metadata().get(ContentMetadata.EMBEDDING_ID));
        assertEquals("D", contents.get(2).metadata().get(ContentMetadata.EMBEDDING_ID));
        assertEquals("C", contents.get(3).metadata().get(ContentMetadata.EMBEDDING_ID));
        // B 的 SCORE 是两路 RRF 贡献之和
        double expectedScore = 1.0 / 62 + 1.0 / 61;
        assertEquals(expectedScore,
                (Double) contents.get(0).metadata().get(ContentMetadata.SCORE), 1e-9);
        // 文档 A 只保留一次，文本来自命中的 TextSegment
        assertEquals("文档A", contents.get(1).textSegment().text());
    }

    @Test
    void 检索使用BM25原问题和KNN改写问题() throws Exception {
        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenReturn(bm25Response(docHit("A", "文档A", 1.0, Map.of())));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(
                        knnMatches(knnDoc("A", "文档A", 0.9))));

        List<Content> contents = retriever.retrieve(
                queryWithOriginal("改写后的问题", "用户原始问题"));

        printRetrievedContents("问题分工：原问题 BM25 + 改写问题 KNN",
                "用户原始问题", "改写后的问题", contents);

        // KNN 使用改写问题生成向量
        verify(embeddingModel).embed("改写后的问题");
        // BM25 的 match 查询使用原始问题
        assertEquals("用户原始问题", capturedBm25QueryText());
    }

    @Test
    void 无metadata时_两路都回退使用当前Query() throws Exception {
        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenReturn(bm25Response(docHit("A", "文档A", 1.0, Map.of())));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));

        List<Content> contents = retriever.retrieve(Query.from("当前问题"));

        printRetrievedContents("无 metadata：BM25 回退使用当前 Query",
                "当前问题", "当前问题", contents);

        verify(embeddingModel).embed("当前问题");
        assertEquals("当前问题", capturedBm25QueryText());
    }

    @Test
    void BM25失败时_降级返回KNN结果() throws Exception {
        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenThrow(new IOException("BM25 服务不可用"));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(knnMatches(
                        knnDoc("X", "文档X", 0.85),
                        knnDoc("Y", "文档Y", 0.75))));

        List<Content> contents = retriever.retrieve(queryWithOriginal("改写", "原"));

        printRetrievedContents("BM25 失败：降级使用 KNN 命中",
                "原", "改写", contents);

        assertEquals(2, contents.size());
        assertEquals("X", contents.get(0).metadata().get(ContentMetadata.EMBEDDING_ID));
        // 单路降级时 SCORE 使用 KNN 原始相似度
        assertEquals(0.85,
                (Double) contents.get(0).metadata().get(ContentMetadata.SCORE), 1e-9);
    }

    @Test
    void KNN失败时_降级返回BM25结果() throws Exception {
        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenReturn(bm25Response(
                        docHit("P", "文档P", 3.0, Map.of()),
                        docHit("Q", "文档Q", 2.0, Map.of())));
        when(embeddingModel.embed(anyString()))
                .thenThrow(new RuntimeException("Embedding 服务不可用"));

        List<Content> contents = retriever.retrieve(queryWithOriginal("改写", "原"));

        printRetrievedContents("KNN 失败：降级使用 BM25 命中",
                "原", "改写", contents);

        assertEquals(2, contents.size());
        assertEquals("P", contents.get(0).metadata().get(ContentMetadata.EMBEDDING_ID));
        assertEquals(3.0,
                (Double) contents.get(0).metadata().get(ContentMetadata.SCORE), 1e-9);
    }

    @Test
    void 两路都失败时_抛出包含两路异常信息的检索异常() throws Exception {
        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenThrow(new IOException("BM25 服务不可用"));
        when(embeddingModel.embed(anyString()))
                .thenThrow(new RuntimeException("Embedding 服务不可用"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> retriever.retrieve(queryWithOriginal("改写", "原")));

        assertTrue(exception.getMessage().contains("BM25"));
        assertTrue(exception.getMessage().contains("KNN"));
    }

    @Test
    void 候选超过8条时_只返回前8条且按RRF分数降序() throws Exception {
        List<Hit<Document>> bm25Hits = new ArrayList<>();
        List<EmbeddingMatch<TextSegment>> knnMatches = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            bm25Hits.add(docHit("bm-" + i, "BM25文档" + i, 10 - i, Map.of()));
            knnMatches.add(new EmbeddingMatch<>(
                    (double) (10 - i), "kn-" + i, null, TextSegment.from("KNN文档" + i)));
        }
        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenReturn(bm25Response(bm25Hits.toArray(new Hit[0])));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(knnMatches));

        List<Content> contents = retriever.retrieve(queryWithOriginal("改写", "原"));

        printRetrievedContents("候选超限：RRF 融合后截取 Top 8",
                "原", "改写", contents);

        assertEquals(8, contents.size());
        for (int i = 1; i < contents.size(); i++) {
            double prev = (Double) contents.get(i - 1).metadata()
                    .get(ContentMetadata.SCORE);
            double current = (Double) contents.get(i).metadata()
                    .get(ContentMetadata.SCORE);
            assertTrue(prev >= current,
                    "第 " + (i - 1) + " 条分数 " + prev + " 应不小于第 " + i + " 条 " + current);
        }
    }

    @Test
    void 命中缺少source时_记录警告并跳过() throws Exception {
        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenReturn(bm25Response(
                        docHit("good-1", "正常文档", 1.0, Map.of()),
                        new Hit.Builder<Document>()
                                .index(INDEX_NAME).id("bad-1").score(2.0).build(),
                        docHit("good-2", "另一个文档", 0.8, Map.of())));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));

        List<Content> contents = retriever.retrieve(queryWithOriginal("改写", "原"));

        printRetrievedContents("无效命中跳过：仅展示有效文档",
                "原", "改写", contents);

        // bad-1 缺少 _source 被跳过，只保留两条有效命中
        assertEquals(2, contents.size());
        assertEquals("good-1", contents.get(0).metadata().get(ContentMetadata.EMBEDDING_ID));
        assertEquals("good-2", contents.get(1).metadata().get(ContentMetadata.EMBEDDING_ID));
    }

    @Test
    void 无parent_chunk_id时_不访问Redis和MySQL且原命中保持不变() throws Exception {
        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenReturn(bm25Response());
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(knnMatches(
                        knnDoc("child-1", "子分片一", 0.9),
                        knnDoc("child-2", "子分片二", 0.8))));

        List<Content> contents = retriever.retrieve(queryWithOriginal("改写", "原"));

        printRetrievedContents("无父分片：原 KNN 命中保持不变",
                "原", "改写", contents);

        // 独立分片直接进入融合结果，且完全不访问 Redis/MySQL。
        assertEquals(2, contents.size());
        assertEquals("child-1", contents.get(0).metadata().get(ContentMetadata.EMBEDDING_ID));
        assertEquals("child-2", contents.get(1).metadata().get(ContentMetadata.EMBEDDING_ID));
        verifyNoInteractions(segmentMapper, stringRedisTemplate);
    }

    @Test
    void Redis命中时_使用完整父分片且不访问数据库() throws Exception {
        KnowledgeSegment parent = parentSegment("parent-1", "完整父分片内容");
        ValueOperations<String, String> valueOps = stubRedisGet(
                "rag:parent-chunk:v1:parent-1", jsonMapper.writeValueAsString(parent));

        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenThrow(new IOException("BM25 服务不可用"));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(knnMatches(
                        knnDocWithParent("child-1", "子分片", 0.85, "parent-1"))));

        List<Content> contents = retriever.retrieve(queryWithOriginal("改写", "原"));

        printRetrievedContents("Redis 命中：子分片替换为完整父分片",
                "原", "改写", contents);

        // EMBEDDING_ID 使用父分片 chunk_id，文本使用父分片内容，分数继承子分片。
        assertEquals(1, contents.size());
        assertEquals("parent-1", contents.get(0).metadata().get(ContentMetadata.EMBEDDING_ID));
        assertEquals("完整父分片内容", contents.get(0).textSegment().text());
        assertEquals(0.85,
                (Double) contents.get(0).metadata().get(ContentMetadata.SCORE), 1e-9);
        // Redis 命中时不回查数据库。
        verifyNoInteractions(segmentMapper);
    }

    @Test
    void Redis未命中时_查询数据库并以1小时TTL写入缓存() throws Exception {
        KnowledgeSegment parent = parentSegment("parent-1", "完整父分片内容");
        ValueOperations<String, String> valueOps = stubRedisGet(
                "rag:parent-chunk:v1:parent-1", null);
        when(segmentMapper.findByChunkId("parent-1")).thenReturn(parent);

        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenThrow(new IOException("BM25 服务不可用"));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(knnMatches(
                        knnDocWithParent("child-1", "子分片", 0.85, "parent-1"))));

        List<Content> contents = retriever.retrieve(queryWithOriginal("改写", "原"));

        printRetrievedContents("Redis 未命中：回查数据库并写缓存",
                "原", "改写", contents);

        assertEquals(1, contents.size());
        assertEquals("parent-1", contents.get(0).metadata().get(ContentMetadata.EMBEDDING_ID));
        // 数据库被查询，且以 1 小时 TTL 写入 Redis。
        verify(segmentMapper).findByChunkId("parent-1");
        verify(valueOps).set("rag:parent-chunk:v1:parent-1",
                jsonMapper.writeValueAsString(parent), Duration.ofHours(1));
    }

    @Test
    void 同一父分片对应多个子分片_数据库最多查询一次且只保留最高排名() throws Exception {
        KnowledgeSegment parent = parentSegment("parent-1", "完整父分片内容");
        ValueOperations<String, String> valueOps = stubRedisGet(
                "rag:parent-chunk:v1:parent-1", null);
        when(segmentMapper.findByChunkId("parent-1")).thenReturn(parent);

        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenThrow(new IOException("BM25 服务不可用"));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(knnMatches(
                        knnDocWithParent("child-1", "子分片一", 0.9, "parent-1"),
                        knnDocWithParent("child-2", "子分片二", 0.8, "parent-1"))));

        List<Content> contents = retriever.retrieve(queryWithOriginal("改写", "原"));

        printRetrievedContents("多子分片指向同一父分片：去重并保留最高排名",
                "原", "改写", contents);

        // 数据库最多查询一次，最终父分片只返回一次并保留最高排名子分片的分数。
        verify(segmentMapper, times(1)).findByChunkId("parent-1");
        assertEquals(1, contents.size());
        assertEquals("parent-1", contents.get(0).metadata().get(ContentMetadata.EMBEDDING_ID));
        assertEquals(0.9,
                (Double) contents.get(0).metadata().get(ContentMetadata.SCORE), 1e-9);
    }

    @Test
    void Redis不可用时_回退数据库且检索继续() throws Exception {
        KnowledgeSegment parent = parentSegment("parent-1", "完整父分片内容");
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Redis 连接失败"));
        when(segmentMapper.findByChunkId("parent-1")).thenReturn(parent);

        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenThrow(new IOException("BM25 服务不可用"));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(knnMatches(
                        knnDocWithParent("child-1", "子分片", 0.85, "parent-1"))));

        List<Content> contents = retriever.retrieve(queryWithOriginal("改写", "原"));

        printRetrievedContents("Redis 不可用：回退数据库并正常返回",
                "原", "改写", contents);

        assertEquals(1, contents.size());
        assertEquals("parent-1", contents.get(0).metadata().get(ContentMetadata.EMBEDDING_ID));
    }

    @Test
    void 缓存JSON损坏时_回退数据库并用有效数据覆盖() throws Exception {
        KnowledgeSegment parent = parentSegment("parent-1", "完整父分片内容");
        ValueOperations<String, String> valueOps = stubRedisGet(
                "rag:parent-chunk:v1:parent-1", "{broken-json");
        when(segmentMapper.findByChunkId("parent-1")).thenReturn(parent);

        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenThrow(new IOException("BM25 服务不可用"));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(knnMatches(
                        knnDocWithParent("child-1", "子分片", 0.85, "parent-1"))));

        List<Content> contents = retriever.retrieve(queryWithOriginal("改写", "原"));

        printRetrievedContents("缓存损坏：回退数据库并覆盖损坏缓存",
                "原", "改写", contents);

        assertEquals(1, contents.size());
        assertEquals("parent-1", contents.get(0).metadata().get(ContentMetadata.EMBEDDING_ID));
        // 有效数据覆盖损坏缓存。
        verify(valueOps).set("rag:parent-chunk:v1:parent-1",
                jsonMapper.writeValueAsString(parent), Duration.ofHours(1));
    }

    @Test
    void 父分片不存在时_保留原子分片() throws Exception {
        ValueOperations<String, String> valueOps = stubRedisGet(
                "rag:parent-chunk:v1:missing-1", null);
        when(segmentMapper.findByChunkId("missing-1")).thenReturn(null);

        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenThrow(new IOException("BM25 服务不可用"));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(knnMatches(
                        knnDocWithParent("child-1", "子分片内容", 0.85, "missing-1"))));

        List<Content> contents = retriever.retrieve(queryWithOriginal("改写", "原"));

        printRetrievedContents("父分片不存在：保留原子分片",
                "原", "改写", contents);

        assertEquals(1, contents.size());
        assertEquals("child-1", contents.get(0).metadata().get(ContentMetadata.EMBEDDING_ID));
        assertEquals("子分片内容", contents.get(0).textSegment().text());
        // 父分片不可用时不会写入 Redis 缓存。
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void 父分片文本为空时_保留原子分片() throws Exception {
        KnowledgeSegment emptyParent = parentSegment("parent-1", "");
        ValueOperations<String, String> valueOps = stubRedisGet(
                "rag:parent-chunk:v1:parent-1", null);
        when(segmentMapper.findByChunkId("parent-1")).thenReturn(emptyParent);

        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenThrow(new IOException("BM25 服务不可用"));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(knnMatches(
                        knnDocWithParent("child-1", "子分片内容", 0.85, "parent-1"))));

        List<Content> contents = retriever.retrieve(queryWithOriginal("改写", "原"));

        printRetrievedContents("父分片文本为空：保留原子分片",
                "原", "改写", contents);

        assertEquals(1, contents.size());
        assertEquals("child-1", contents.get(0).metadata().get(ContentMetadata.EMBEDDING_ID));
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void 数据库异常时_保留原子分片() throws Exception {
        ValueOperations<String, String> valueOps = stubRedisGet(
                "rag:parent-chunk:v1:parent-1", null);
        when(segmentMapper.findByChunkId("parent-1"))
                .thenThrow(new RuntimeException("数据库不可用"));

        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenThrow(new IOException("BM25 服务不可用"));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(knnMatches(
                        knnDocWithParent("child-1", "子分片内容", 0.85, "parent-1"))));

        List<Content> contents = retriever.retrieve(queryWithOriginal("改写", "原"));

        printRetrievedContents("数据库异常：保留原子分片",
                "原", "改写", contents);

        assertEquals(1, contents.size());
        assertEquals("child-1", contents.get(0).metadata().get(ContentMetadata.EMBEDDING_ID));
    }

    @Test
    void 父分片替换后_与BM25参与RRF融合去重() throws Exception {
        KnowledgeSegment parent = parentSegment("parent-1", "完整父分片内容");
        ValueOperations<String, String> valueOps = stubRedisGet(
                "rag:parent-chunk:v1:parent-1", null);
        when(segmentMapper.findByChunkId("parent-1")).thenReturn(parent);

        // BM25 命中子分片 child-1 的 ES 文档；KNN 命中同一子分片后被替换为父分片 parent-1。
        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenReturn(bm25Response(docHit("child-1", "子分片BM25文本", 2.0, Map.of())));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(knnMatches(
                        knnDocWithParent("child-1", "子分片KNN文本", 0.9, "parent-1"))));

        List<Content> contents = retriever.retrieve(queryWithOriginal("改写", "原"));

        printRetrievedContents("父分片替换后与 BM25 参与 RRF 融合",
                "原", "改写", contents);

        // 替换后 KNN 命中 id 变为 parent-1，与 BM25 的 child-1 分属不同去重空间。
        Set<String> ids = contents.stream()
                .map(content -> (String) content.metadata()
                        .get(ContentMetadata.EMBEDDING_ID))
                .collect(Collectors.toSet());
        assertEquals(Set.of("child-1", "parent-1"), ids);
    }

    @Test
    void BM25和KNN请求参数均为最多5条() throws Exception {
        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenReturn(bm25Response(docHit("A", "文本A", 1.0, Map.of())));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(knnMatches(knnDoc("B", "文本B", 0.9))));

        retriever.retrieve(queryWithOriginal("改写", "原"));

        // BM25 请求 size 与 KNN 请求 maxResults 均为 5。
        assertEquals(5, capturedBm25Request().size());
        ArgumentCaptor<EmbeddingSearchRequest> requestCaptor =
                ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(embeddingStore).search(requestCaptor.capture());
        assertEquals(5, requestCaptor.getValue().maxResults());
    }

    @SuppressWarnings("unchecked")
    @Test
    void 双路无重复时_RRF向ReRanker传递10条候选并使用原问题() throws Exception {
        rerankerProperties.setEnabled(true);
        // 5 条 BM25 + 5 条 KNN，ID 全部不重复，RRF 可产生 10 条候选。
        List<Hit<Document>> bm25Hits = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            bm25Hits.add(docHit("bm-" + i, "BM25文本" + i, 5 - i, Map.of()));
        }
        List<EmbeddingMatch<TextSegment>> knnMatchesList = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            knnMatchesList.add(knnDoc("kn-" + i, "KNN文本" + i, 0.9 - i * 0.1));
        }
        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenReturn(bm25Response(bm25Hits.toArray(new Hit[0])));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(knnMatchesList));
        // 返回与候选数量一致的全 1 分数，保证重排正常执行。
        stubRerankScores();

        retriever.retrieve(queryWithOriginal("改写问题", "用户原始问题"));

        // ReRanker 收到 10 条候选，且评分 query 使用用户原问题而非改写问题。
        ArgumentCaptor<List<TextSegment>> segmentsCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(scoringModel).scoreAll(segmentsCaptor.capture(), queryCaptor.capture());
        assertEquals(10, segmentsCaptor.getValue().size());
        assertEquals("用户原始问题", queryCaptor.getValue());
    }

    @SuppressWarnings("unchecked")
    @Test
    void 存在重复时_RRF向ReRanker传递去重后的实际数量() throws Exception {
        rerankerProperties.setEnabled(true);
        // BM25 3 条 + KNN 3 条，其中 b、c 被两路同时命中 → 去重后实际 4 条。
        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenReturn(bm25Response(
                        docHit("a", "文本A", 3.0, Map.of()),
                        docHit("b", "文本B", 2.0, Map.of()),
                        docHit("c", "文本C", 1.0, Map.of())));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(knnMatches(
                        knnDoc("b", "文本B", 0.9),
                        knnDoc("c", "文本C", 0.8),
                        knnDoc("d", "文本D", 0.7))));
        stubRerankScores();

        retriever.retrieve(queryWithOriginal("改写", "原"));

        // 去重后候选为 a、b、c、d 共 4 条。
        ArgumentCaptor<List<TextSegment>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(scoringModel).scoreAll(captor.capture(), anyString());
        assertEquals(4, captor.getValue().size());
    }

    @Test
    void Mock倒序分数_最终按BGE分数重排且只返回前8条() throws Exception {
        rerankerProperties.setEnabled(true);
        // 5 条 BM25 + 5 条 KNN 不重复，RRF 产生 10 条候选。
        List<Hit<Document>> bm25Hits = new ArrayList<>();
        List<EmbeddingMatch<TextSegment>> knnMatchesList = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            bm25Hits.add(docHit("bm-" + i, "BM25文本" + i, 5 - i, Map.of()));
            knnMatchesList.add(knnDoc("kn-" + i, "KNN文本" + i, 0.9 - i * 0.1));
        }
        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenReturn(bm25Response(bm25Hits.toArray(new Hit[0])));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(knnMatchesList));
        // 分数 = 位置 + 1：排在后边的候选分数更高，重排后顺序整体翻转。
        when(scoringModel.scoreAll(any(), anyString())).thenAnswer(invocation -> {
            List<?> segments = invocation.getArgument(0);
            List<Double> scores = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                scores.add((double) (i + 1));
            }
            return Response.from(scores);
        });

        List<Content> contents = retriever.retrieve(queryWithOriginal("改写", "原"));

        printRetrievedContents("BGE 重排：按分数降序重排并取 Top 8",
                "原", "改写", contents);

        // 10 条候选只返回前 8 条；RRF 顺序最后一位 kn-4 分数最高，重排后排第一。
        assertEquals(8, contents.size());
        assertEquals("kn-4", contents.get(0).metadata().get(ContentMetadata.EMBEDDING_ID));
        assertEquals(10.0,
                (Double) contents.get(0).metadata().get(ContentMetadata.RERANKED_SCORE), 1e-9);
        assertEquals("bm-4", contents.get(1).metadata().get(ContentMetadata.EMBEDDING_ID));
        // 原 RRF 分数与 EMBEDDING_ID 被保留。
        assertEquals(1.0 / 65,
                (Double) contents.get(0).metadata().get(ContentMetadata.SCORE), 1e-9);
    }

    @SuppressWarnings("unchecked")
    @Test
    void 单路失败时_剩余最多5条仍进入ReRanker() throws Exception {
        rerankerProperties.setEnabled(true);
        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenThrow(new IOException("BM25 服务不可用"));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(knnMatches(
                        knnDoc("k1", "K1", 0.9),
                        knnDoc("k2", "K2", 0.8),
                        knnDoc("k3", "K3", 0.7),
                        knnDoc("k4", "K4", 0.6),
                        knnDoc("k5", "K5", 0.5))));
        stubRerankScores();

        retriever.retrieve(queryWithOriginal("改写", "原"));

        // 单路降级：最多 5 条仍进入 ReRanker。
        ArgumentCaptor<List<TextSegment>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(scoringModel).scoreAll(captor.capture(), anyString());
        assertEquals(5, captor.getValue().size());
    }

    @Test
    void ReRanker关闭时_回退原排序且不调用模型() throws Exception {
        // rerankerProperties 默认 enabled=false。
        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenReturn(bm25Response(
                        docHit("A", "文本A", 3.0, Map.of()),
                        docHit("B", "文本B", 2.0, Map.of())));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));

        List<Content> contents = retriever.retrieve(queryWithOriginal("改写", "原"));

        // 保持 RRF 原序，且不调用评分模型。
        assertEquals("A", contents.get(0).metadata().get(ContentMetadata.EMBEDDING_ID));
        assertEquals("B", contents.get(1).metadata().get(ContentMetadata.EMBEDDING_ID));
        verifyNoInteractions(scoringModel);
    }

    @Test
    void 推理异常时_回退原排序() throws Exception {
        rerankerProperties.setEnabled(true);
        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenReturn(bm25Response(
                        docHit("A", "文本A", 3.0, Map.of()),
                        docHit("B", "文本B", 2.0, Map.of())));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));
        when(scoringModel.scoreAll(any(), anyString()))
                .thenThrow(new RuntimeException("推理失败"));

        List<Content> contents = retriever.retrieve(queryWithOriginal("改写", "原"));

        // 推理异常回退原 RRF 排序。
        assertEquals("A", contents.get(0).metadata().get(ContentMetadata.EMBEDDING_ID));
        assertEquals(2, contents.size());
    }

    @Test
    void 评分数量不一致时_回退原排序() throws Exception {
        rerankerProperties.setEnabled(true);
        when(elasticsearchClient.search(anySearchFunction(), eq(Document.class)))
                .thenReturn(bm25Response(
                        docHit("A", "文本A", 3.0, Map.of()),
                        docHit("B", "文本B", 2.0, Map.of()),
                        docHit("C", "文本C", 1.0, Map.of())));
        when(embeddingModel.embed(anyString())).thenReturn(new float[]{0.1f});
        when(embeddingStore.search(any()))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));
        // 只返回 1 个分数，与 3 个候选不一致 → 回退原排序。
        when(scoringModel.scoreAll(any(), anyString()))
                .thenReturn(Response.from(List.of(0.5)));

        List<Content> contents = retriever.retrieve(queryWithOriginal("改写", "原"));

        // 数量不一致时保持 RRF 原序，且不写入 RERANKED_SCORE。
        assertEquals("A", contents.get(0).metadata().get(ContentMetadata.EMBEDDING_ID));
        assertEquals(3, contents.size());
        assertTrue(contents.get(0).metadata().get(ContentMetadata.RERANKED_SCORE) == null);
    }

    @Test
    void 模型级集成测试_有模型文件时执行真实推理否则跳过() throws Exception {
        RerankerProperties props = new RerankerProperties();
        props.setEnabled(true);
        props.setModelPath("./models/bge-reranker-v2-m3/model_int8.onnx");
        props.setTokenizerPath("./models/bge-reranker-v2-m3");
        // 当前没有模型文件时自动跳过，不影响 Maven 测试。
        assumeTrue(Files.exists(Path.of(props.getModelPath())),
                "未检测到 BGE 模型文件，跳过模型级集成测试");

        BgeRerankerScoringModel model = new BgeRerankerScoringModel(props);
        Response<List<Double>> response = model.scoreAll(
                List.of(TextSegment.from("候选一"), TextSegment.from("候选二")), "查询问题");
        assertEquals(2, response.content().size());
        response.content().forEach(score ->
                assertTrue(score >= 0.0 && score <= 1.0));
        model.close();
    }

    /**
     * 将一次混合检索的最终命中以易读格式输出到测试控制台。
     *
     * <p>输出包含测试场景、原问题、改写问题、命中总数，以及每条结果的
     * 最终排名、Elasticsearch 文档 ID、融合/降级分数、完整文本、分片 metadata
     * 和 Content metadata。当前类是 Mockito 单元测试，因此这些命中来自测试构造的
     * BM25/KNN 数据，但排名、去重和 RRF 分数由真实业务代码计算。</p>
     *
     * @param scenario       当前测试场景说明
     * @param originalQuery  BM25 通道使用的原始问题
     * @param rewrittenQuery KNN 通道使用的改写问题
     * @param contents       检索器返回的最终命中
     */
    private static void printRetrievedContents(String scenario,
                                               String originalQuery,
                                               String rewrittenQuery,
                                               List<Content> contents) {
        System.out.println();
        System.out.println("============================================================");
        System.out.println("混合检索测试场景: " + scenario);
        System.out.println("BM25 原始问题 : " + originalQuery);
        System.out.println("KNN 改写问题  : " + rewrittenQuery);
        System.out.println("最终命中数     : " + contents.size());
        System.out.println("------------------------------------------------------------");

        if (contents.isEmpty()) {
            System.out.println("本次检索没有返回有效命中。");
        }

        for (int index = 0; index < contents.size(); index++) {
            Content content = contents.get(index);
            Object embeddingId = content.metadata().get(ContentMetadata.EMBEDDING_ID);
            Object scoreValue = content.metadata().get(ContentMetadata.SCORE);
            double score = scoreValue instanceof Number number
                    ? number.doubleValue()
                    : Double.NaN;

            System.out.printf(Locale.ROOT, "[%02d] ID    : %s%n", index + 1, embeddingId);
            System.out.printf(Locale.ROOT, "     SCORE : %.12f%n", score);
            System.out.println("     TEXT  : " + content.textSegment().text());
            System.out.println("     SEGMENT_METADATA : "
                    + content.textSegment().metadata().toMap());
            System.out.println("     CONTENT_METADATA : " + content.metadata());
            System.out.println("------------------------------------------------------------");
        }
        System.out.println("============================================================");
    }

    /** 构造携带原始问题的 Query，模拟 KnowEngineQueryTransformer 的输出。 */
    private static Query queryWithOriginal(String rewrittenQuery, String originalQuery) {
        // Query.Metadata 需要非空的 InvocationContext，测试中同样使用占位上下文。
        InvocationContext context = InvocationContext.builder()
                .invocationId(UUID.randomUUID())
                .interfaceName("test")
                .methodName("retrieve")
                .methodArguments(List.of())
                .invocationParameters(new InvocationParameters())
                .timestampNow()
                .build();
        return Query.from(rewrittenQuery, Metadata.builder()
                .chatMessage(UserMessage.from(originalQuery))
                .invocationContext(context)
                .build());
    }

    /**
     * 返回类型明确的 search 函数参数匹配器。
     *
     * <p>ElasticsearchClient 存在多个 search 重载，Mockito 的无参 any() 无法约束
     * 第一个参数类型，这里通过返回类型固定为 Function 重载，避免编译期重载歧义。</p>
     */
    @SuppressWarnings("unchecked")
    private static Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>> anySearchFunction() {
        return any();
    }

    /** 捕获 ES 客户端收到的 match 查询文本，用于验证 BM25 使用的问题。 */
    private String capturedBm25QueryText() throws IOException {
        return capturedBm25Request().query().match().query().stringValue();
    }

    /** 捕获 ES 客户端实际收到的搜索请求，用于验证 BM25 请求参数。 */
    @SuppressWarnings("unchecked")
    private SearchRequest capturedBm25Request() throws IOException {
        ArgumentCaptor<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>> captor =
                ArgumentCaptor.forClass((Class) Function.class);
        verify(elasticsearchClient).search(captor.capture(), eq(Document.class));
        return captor.getValue().apply(new SearchRequest.Builder()).build();
    }

    /** 构造一条带 _source 的 BM25 命中。 */
    private static Hit<Document> docHit(String id, String text, double score,
                                        Map<String, Object> metadata) {
        return new Hit.Builder<Document>()
                .index(INDEX_NAME)
                .id(id)
                .score(score)
                .source(newDocument(text, metadata))
                .build();
    }

    /** 构造 KNN 通道的 EmbeddingMatch 列表，score 即模拟的余弦相似度。 */
    private static List<EmbeddingMatch<TextSegment>> knnMatches(
            EmbeddingMatch<TextSegment>... matches) {
        return List.of(matches);
    }

    /** 便捷构造 KNN 命中，文本与 metadata 由 TextSegment 携带。 */
    private static EmbeddingMatch<TextSegment> knnDoc(String id, String text,
                                                      double score) {
        TextSegment segment = TextSegment.from(
                text, dev.langchain4j.data.document.Metadata.from(Map.of()));
        return new EmbeddingMatch<>(score, id, null, segment);
    }

    /** 便捷构造带 parent_chunk_id 的 KNN 子分片命中，用于触发父分片替换。 */
    private static EmbeddingMatch<TextSegment> knnDocWithParent(String id, String text,
                                                                double score,
                                                                String parentChunkId) {
        TextSegment segment = TextSegment.from(text,
                dev.langchain4j.data.document.Metadata.from(Map.of(
                        MarkdownHeaderParentTextSplitter.PARENT_CHUNK_ID, parentChunkId)));
        return new EmbeddingMatch<>(score, id, null, segment);
    }

    /** 构造一条父分片实体，metadata 保留 splitter 写入的 JSON 结构。 */
    private static KnowledgeSegment parentSegment(String chunkId, String text) {
        KnowledgeSegment segment = new KnowledgeSegment();
        segment.setChunkId(chunkId);
        segment.setDocId("doc-1");
        segment.setText(text);
        // status 不能为 null，否则 Jackson 序列化 getSegmentStatus() 时会抛异常。
        segment.setStatus(SegmentStatus.VECTOR_STORED.value());
        segment.setMetadata("{\"chunk_id\":\"" + chunkId + "\",\"chunk_type\":\"PARENT\"}");
        return segment;
    }

    /**
     * 将 StringRedisTemplate 的 opsForValue 指向返回固定值的 ValueOperations mock。
     *
     * <p>value 为 null 时表示 Redis 未命中，走数据库兜底路径。</p>
     */
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> stubRedisGet(String key, String value) {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(eq(key))).thenReturn(value);
        return valueOps;
    }

    /**
     * 让评分模型返回与候选数量一致的全 1 分数。
     *
     * <p>重排测试中候选数量由检索数据决定，这里按实际入参数量生成分数，
     * 避免触发"评分数量不一致"回退分支。</p>
     */
    @SuppressWarnings("unchecked")
    private void stubRerankScores() {
        when(scoringModel.scoreAll(any(), anyString())).thenAnswer(invocation -> {
            List<?> segments = invocation.getArgument(0);
            List<Double> scores = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                scores.add(1.0);
            }
            return Response.from(scores);
        });
    }

    /** 构造一个只包含 hits 元数据的 BM25 搜索响应。 */
    @SafeVarargs
    private static SearchResponse<Document> bm25Response(Hit<Document>... hits) {
        return new SearchResponse.Builder<Document>()
                .took(1)
                .timedOut(false)
                .shards(new ShardStatistics.Builder()
                        .total(1).successful(1).skipped(0).failed(0).build())
                .hits(new HitsMetadata.Builder<Document>()
                        .total(new TotalHits.Builder()
                                .value(hits.length)
                                .relation(TotalHitsRelation.Eq)
                                .build())
                        .hits(List.of(hits))
                        .build())
                .build();
    }

    /**
     * 构造 langchain4j 的 Document。其构造函数是包私有的，测试包无法直接调用，
     * 这里通过反射绕过访问限制，仅用于构造 BM25 命中的 _source。
     */
    private static Document newDocument(String text, Map<String, Object> metadata) {
        try {
            Constructor<Document> constructor =
                    Document.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            Document document = constructor.newInstance();
            document.setText(text);
            if (metadata != null) {
                document.setMetadata(metadata);
            }
            return document;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法构造测试 Document", e);
        }
    }
}

package com.llmstudy.rag.module;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.llmstudy.rag.config.ElasticsearchProperties;
import com.llmstudy.rag.config.RerankerProperties;
import com.llmstudy.rag.entity.KnowledgeSegment;
import com.llmstudy.rag.mapper.KnowledgeSegmentMapper;
import com.llmstudy.rag.service.splitter.MarkdownHeaderParentTextSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.elasticsearch.Document;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * 应用层混合检索器：在不依赖 Elasticsearch 付费 RRF 的前提下，对同一个索引
 * 执行 BM25 关键词检索和 KNN 向量检索，并在 Java 中使用 RRF（Reciprocal Rank
 * Fusion）融合两路结果。
 *
 * <p>两路检索使用不同的问题来源，互补两者的检索能力：</p>
 * <ul>
 *     <li>BM25 使用用户<b>原始问题</b>对 text 字段执行 match 查询，保留用户提问中的
 *     原词信息；</li>
 *     <li>KNN 使用<b>改写后的问题</b>生成查询向量，借助嵌入语义召回与问题意图一致的内容。</li>
 * </ul>
 *
 * <p>融合规则：两路各召回固定条数，按 RRF 公式 {@code 1 / (k + rank)} 累加同一文档
 * （ES _id / embeddingId）的贡献分数，去重后按分数降序返回前 N 条。任意单路失败时
 * 记录错误日志并降级使用另一路结果，两路都失败时抛出异常。</p>
 *
 * <p>KNN 阶段会做<b>父分片替换</b>：带有 parent_chunk_id 的子分片是用于向量化的小片段，
 * 完整父分片因 skip_embedding 未入库，因此检索命中子分片后通过“请求内缓存 + Redis +
 * MySQL”三级回查父分片，用完整章节内容替换原子分片。Redis 只用于加速，任何缓存异常
 * 都会回退数据库或保留原子分片，保证检索可用。</p>
 *
 * <p>整体检索链路为：BM25（原问题）+ KNN（改写问题）各召回少量候选 → RRF 融合
 * （最多 candidate-count 条）→ 可选本地 BGE ReRanker 全量重排 → 返回 Top N。
 * ReRanker 关闭、模型缺失或推理异常时回退原排序，只做 Top N 截断。</p>
 *
 * <p>检索与融合逻辑集中在 {@link #retrieve(Query)} 主流程中，辅助方法只负责单路检索、
 * 父分片回查、重排和结果转换，避免过度拆分。</p>
 */
@Component
public class RAGElasticsearchContentRetriever implements ContentRetriever {

    private static final Logger log =
            LoggerFactory.getLogger(RAGElasticsearchContentRetriever.class);

    /** 每路检索召回的候选条数；ReRanker 前的粗排只需要少量高相关候选。 */
    private static final int RESULTS_PER_CHANNEL = 5;

    /** RRF 常数 k，用于平滑排名对分数的贡献，避免排名靠前的文档分数过高。 */
    private static final int RRF_K = 60;

    /** BM25 检索的 text 字段，与 LangChain4j 默认索引结构保持一致。 */
    private static final String TEXT_FIELD = "text";

    /** Redis 父分片缓存的 key 前缀，v1 用于后续版本升级时整体失效旧缓存。 */
    private static final String PARENT_CHUNK_REDIS_KEY_PREFIX = "rag:parent-chunk:v1:";

    /** 父分片内容较稳定，采用 1 小时自然过期；暂不接入文档更新/删除时的主动失效。 */
    private static final Duration PARENT_CACHE_TTL = Duration.ofHours(1);

    /** 提供向量生成的 Spring AI 嵌入模型，供 KNN 通道生成查询向量。 */
    private final OpenAiEmbeddingModel embeddingModel;

    /** 现有 LangChain4j 向量存储，KNN 通道直接复用其 search() 能力。 */
    private final ElasticsearchEmbeddingStore embeddingStore;

    /** Elasticsearch Java 客户端，BM25 通道用它执行 match 查询。 */
    private final ElasticsearchClient elasticsearchClient;

    /** 提供当前使用的索引名等 Elasticsearch 配置。 */
    private final ElasticsearchProperties elasticsearchProperties;

    /** 父分片三级缓存中的 MySQL 兜底数据源，按 chunk_id 查询完整父分片。 */
    private final KnowledgeSegmentMapper segmentMapper;

    /** 父分片二级缓存，Redis 只用于加速，不可用时不阻塞检索。 */
    private final StringRedisTemplate stringRedisTemplate;

    /** 用于父分片 JSON 序列化/反序列化的 Jackson Mapper。 */
    private final JsonMapper jsonMapper;

    /** BGE ReRanker 配置，提供启用开关、RRF 候选上限与最终返回条数。 */
    private final RerankerProperties rerankerProperties;

    /** 本地 BGE ReRanker 评分模型；模型禁用或缺失时回退原排序。 */
    private final ScoringModel scoringModel;

    /**
     * 构造注入全部依赖，所有依赖均为 Spring 容器中已存在的 Bean。
     *
     * @param embeddingModel         Spring AI 嵌入模型
     * @param embeddingStore         现有 LangChain4j Elasticsearch 向量存储
     * @param elasticsearchClient    Elasticsearch Java 客户端
     * @param elasticsearchProperties Elasticsearch 配置
     * @param segmentMapper          knowledge_segment 表 Mapper，父分片兜底数据源
     * @param stringRedisTemplate    父分片 Redis 缓存
     * @param jsonMapper             父分片 JSON 序列化工具
     * @param rerankerProperties     BGE ReRanker 配置
     * @param scoringModel           BGE ReRanker 评分模型
     */
    public RAGElasticsearchContentRetriever(OpenAiEmbeddingModel embeddingModel,
                                            ElasticsearchEmbeddingStore embeddingStore,
                                            ElasticsearchClient elasticsearchClient,
                                            ElasticsearchProperties elasticsearchProperties,
                                            KnowledgeSegmentMapper segmentMapper,
                                            StringRedisTemplate stringRedisTemplate,
                                            JsonMapper jsonMapper,
                                            RerankerProperties rerankerProperties,
                                            ScoringModel scoringModel) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.elasticsearchClient = elasticsearchClient;
        this.elasticsearchProperties = elasticsearchProperties;
        this.segmentMapper = segmentMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.jsonMapper = jsonMapper;
        this.rerankerProperties = rerankerProperties;
        this.scoringModel = scoringModel;
    }

    /**
     * 执行一次混合检索并返回融合后的内容列表。
     *
     * <p>主流程：先校验 Query 并从 metadata 中解析两路问题，然后分别执行 BM25 和
     * KNN 检索，根据两路成败情况选择 RRF 融合或单路降级，最后统一经过可选
     * BGE ReRanker 重排并截断到 Top N。</p>
     *
     * @param query LangChain4j RAG 流程传入的 Query，text() 为改写问题，
     *              metadata 中携带原始问题
     * @return 融合后的内容列表，最多 {@link #MAX_RESULTS} 条
     */
    @Override
    public List<Content> retrieve(Query query) {
        // 空 Query 会在发起 ES 或 Embedding 请求前被拒绝，避免无意义的远端调用。
        if (query == null || query.text() == null || query.text().isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        String rewrittenQuery = query.text();
        String originalQuery = extractOriginalQuery(query);
        log.info("混合检索开始, 原始问题: {}, 改写问题: {}",
                originalQuery, rewrittenQuery);

        // 两路检索相互独立，各自失败也不影响另一路，因此分开 try/catch 收集异常。
        List<ScoredHit> bm25Hits = List.of();
        Exception bm25Error = null;
        try {
            bm25Hits = bm25Search(originalQuery);
            log.info("BM25 检索完成, 原始问题: {}, 召回: {} 条",
                    originalQuery, bm25Hits.size());
        } catch (Exception e) {
            bm25Error = e;
            log.error("BM25 检索失败, 原始问题: {}", originalQuery, e);
        }

        List<ScoredHit> knnHits = List.of();
        Exception knnError = null;
        try {
            knnHits = knnSearch(rewrittenQuery);
            log.info("KNN 检索完成, 改写问题: {}, 召回: {} 条",
                    rewrittenQuery, knnHits.size());
        } catch (Exception e) {
            knnError = e;
            log.error("KNN 检索失败, 改写问题: {}", rewrittenQuery, e);
        }

        List<Content> candidates;
        // 两路都失败时抛出包含两路异常信息的检索异常，交由上层感知检索不可用。
        if (bm25Error != null && knnError != null) {
            throw new IllegalStateException(
                    "BM25 与 KNN 检索均失败, BM25: " + bm25Error.getMessage()
                            + ", KNN: " + knnError.getMessage(),
                    bm25Error);
        }
        // BM25 失败但 KNN 成功：降级使用 KNN 结果（单路最多 5 条）。
        if (bm25Error != null) {
            log.warn("BM25 检索失败, 降级使用 KNN 检索结果");
            candidates = toContent(knnHits);
        } else if (knnError != null) {
            // KNN 失败但 BM25 成功：降级使用 BM25 结果（单路最多 5 条）。
            log.warn("KNN 检索失败, 降级使用 BM25 检索结果");
            candidates = toContent(bm25Hits);
        } else {
            // 两路都成功：RRF 融合、去重，保留最多 candidate-count 条候选。
            candidates = fuse(bm25Hits, knnHits);
        }
        // 双路融合与单路降级统一经过"可选 BGE 重排 → Top N"阶段。
        return rerankAndTrim(candidates, originalQuery);
    }

    /**
     * 从 Query metadata 中解析原始问题，供 BM25 通道使用。
     *
     * <p>原问题由 KnowEngineQueryTransformer 以 UserMessage 形式写入
     * {@code query.metadata().chatMessage()}；metadata 不存在或未携带原问题时，
     * 回退使用当前 Query（改写问题），保证检索流程不会因为缺失元数据而中断。</p>
     *
     * @param query 当前检索 Query
     * @return 原始问题文本
     */
    private String extractOriginalQuery(Query query) {
        ChatMessage chatMessage = query.metadata() == null
                ? null : query.metadata().chatMessage();
        // 只有单文本 UserMessage 能确定对应原始问题，其他情况统一回退。
        if (chatMessage instanceof UserMessage userMessage
                && userMessage.hasSingleText()) {
            return userMessage.singleText();
        }
        return query.text();
    }

    /**
     * 使用原始问题对当前索引的 text 字段执行 BM25 match 查询。
     *
     * <p>从 ES 命中的 _source 中重建 TextSegment 并保留原始 metadata，单条命中
     * 缺少 _id、_source 或文本时记录警告并跳过，不影响其余命中。</p>
     *
     * @param originalQuery 用户原始问题
     * @return 命中列表，按 BM25 相关度从高到低排列
     * @throws Exception 整个 BM25 查询失败时抛出，由主流程捕获处理
     */
    private List<ScoredHit> bm25Search(String originalQuery) throws Exception {
        // match 查询保留用户原词，正文是中文场景下默认分词也可匹配关键短语。
        SearchResponse<Document> response = elasticsearchClient.search(
                request -> request
                        .index(elasticsearchProperties.getIndexName())
                        .query(query -> query.match(
                                match -> match.field(TEXT_FIELD).query(originalQuery)))
                        .size(RESULTS_PER_CHANNEL),
                Document.class);

        List<ScoredHit> hits = new ArrayList<>();
        for (Hit<Document> hit : response.hits().hits()) {
            Document source = hit.source();
            String id = hit.id();
            if (id == null || id.isBlank()
                    || source == null
                    || source.getText() == null || source.getText().isBlank()) {
                log.warn("BM25 命中缺少 _id、_source 或文本, 跳过: {}", id);
                continue;
            }
            Metadata metadata = source.getMetadata() == null
                    ? new Metadata()
                    : Metadata.from(source.getMetadata());
            hits.add(new ScoredHit(
                    id,
                    TextSegment.from(source.getText(), metadata),
                    hit.score() == null ? 0.0 : hit.score()));
        }
        return hits;
    }

    /**
     * 使用改写后的问题生成查询向量，并复用现有向量存储执行 KNN 检索。
     *
     * <p>minScore 设为 0 只按召回条数截断，把相关性判断完全交给 RRF 融合阶段；
     * 单条命中缺少 embeddingId 或文本时记录警告并跳过。命中子分片时按原始排名
     * 依次尝试替换为完整父分片：同一父分片只保留排名最高的一次，避免重复占用
     * RRF 及未来 ReRanker 的候选位。</p>
     *
     * @param rewrittenQuery 改写后的问题
     * @return 命中列表，按向量相似度从高到低排列（子分片可能已替换为父分片）
     */
    private List<ScoredHit> knnSearch(String rewrittenQuery) {
        float[] vector = embeddingModel.embed(rewrittenQuery);
        Embedding queryEmbedding = Embedding.from(vector);
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(RESULTS_PER_CHANNEL)
                        .minScore(0.0)
                        .build());

        List<ScoredHit> hits = new ArrayList<>();
        // 请求内父分片缓存：同一请求内多个子分片指向同一父分片时，Redis/MySQL 只查一次。
        Map<String, KnowledgeSegment> requestParentCache = new HashMap<>();
        // 已进入结果的最终 ID：父分片替换后使用父分片 chunk_id，避免同一父分片重复出现。
        Set<String> emittedIds = new HashSet<>();

        for (EmbeddingMatch<TextSegment> match : result.matches()) {
            TextSegment segment = match.embedded();
            String childId = match.embeddingId();
            if (childId == null || childId.isBlank()
                    || segment == null
                    || segment.text() == null || segment.text().isBlank()) {
                log.warn("KNN 命中缺少 embeddingId 或文本, 跳过: {}", childId);
                continue;
            }
            double score = match.score() == null ? 0.0 : match.score();

            // 读取子分片 metadata 中的父分片 ID；值为空说明是独立分片，直接保留原子分片。
            Object parentValue = segment.metadata().toMap()
                    .get(MarkdownHeaderParentTextSplitter.PARENT_CHUNK_ID);
            String parentChunkId = parentValue == null
                    ? null : String.valueOf(parentValue).trim();
            if (parentChunkId == null || parentChunkId.isBlank()) {
                if (emittedIds.add(childId)) {
                    hits.add(new ScoredHit(childId, segment, score));
                }
                continue;
            }

            // 有父分片：三级回查父分片，失败时保留原子分片保证本次检索仍可用。
            KnowledgeSegment parent =
                    resolveParentChunk(parentChunkId, requestParentCache);
            if (parent == null
                    || parent.getText() == null || parent.getText().isBlank()) {
                log.warn("父分片不可用(不存在或文本为空), 保留子分片: childId={}, parentChunkId={}",
                        childId, parentChunkId);
                if (emittedIds.add(childId)) {
                    hits.add(new ScoredHit(childId, segment, score));
                }
                continue;
            }

            // 替换成功：继承子分片的 KNN 排名与分数，ID 换成父分片 chunk_id。
            if (emittedIds.add(parent.getChunkId())) {
                hits.add(new ScoredHit(
                        parent.getChunkId(), toParentSegment(parent), score));
            } else {
                // 同一父分片已被排名更靠前的子分片处理，跳过避免重复占用候选位。
                log.debug("父分片重复命中, 已由更高排名子分片代表, 跳过: parentChunkId={}",
                        parent.getChunkId());
            }
        }
        return hits;
    }

    /**
     * 按“请求内缓存 → Redis → MySQL”三级顺序回查父分片。
     *
     * <p>Redis 读取、反序列化失败或缓存损坏时记录警告并继续查询数据库；数据库
     * 查询失败时返回 null 由调用方保留原子分片。成功后写入 Redis 覆盖可能损坏的
     * 缓存值，并放入请求内缓存供同一请求复用。</p>
     *
     * @param parentChunkId    父分片 chunk_id
     * @param requestParentCache 当前请求内的父分片缓存
     * @return 父分片实体；不存在或查询失败时为 null
     */
    private KnowledgeSegment resolveParentChunk(String parentChunkId,
                                                Map<String, KnowledgeSegment> requestParentCache) {
        // 第一级：请求内缓存，避免同一请求重复回查同一父分片。
        KnowledgeSegment cached = requestParentCache.get(parentChunkId);
        if (cached != null) {
            return cached;
        }

        // 第二级：Redis。任何异常都视为未命中，回退到数据库。
        String redisKey = PARENT_CHUNK_REDIS_KEY_PREFIX + parentChunkId;
        try {
            String json = stringRedisTemplate.opsForValue().get(redisKey);
            if (json != null && !json.isBlank()) {
                KnowledgeSegment cachedSegment =
                        jsonMapper.readValue(json, KnowledgeSegment.class);
                requestParentCache.put(parentChunkId, cachedSegment);
                return cachedSegment;
            }
        } catch (Exception e) {
            // 缓存读取失败或 JSON 损坏都不影响检索，记录后走数据库兜底。
            log.warn("读取 Redis 父分片缓存失败, 回退数据库: parentChunkId={}, 原因={}",
                    parentChunkId, e.getMessage());
        }

        // 第三级：MySQL。查询成功后写回 Redis（覆盖损坏缓存）并放入请求内缓存。
        try {
            KnowledgeSegment parent = segmentMapper.findByChunkId(parentChunkId);
            if (parent == null
                    || parent.getText() == null || parent.getText().isBlank()) {
                // 父分片不存在或文本为空，交由调用方决定保留原子分片，不做无效缓存。
                return parent;
            }
            requestParentCache.put(parentChunkId, parent);
            writeParentChunkCache(parentChunkId, parent);
            return parent;
        } catch (Exception e) {
            log.warn("查询父分片失败, 保留原子分片: parentChunkId={}, 原因={}",
                    parentChunkId, e.getMessage());
            return null;
        }
    }

    /**
     * 将父分片以 JSON 形式写入 Redis，TTL 为 1 小时。
     *
     * <p>缓存写入属于加速操作，失败只影响下次检索的命中率，不影响本次结果。</p>
     *
     * @param parentChunkId 父分片 chunk_id，用于拼接缓存 key
     * @param parent        父分片实体
     */
    private void writeParentChunkCache(String parentChunkId, KnowledgeSegment parent) {
        try {
            stringRedisTemplate.opsForValue().set(
                    PARENT_CHUNK_REDIS_KEY_PREFIX + parentChunkId,
                    jsonMapper.writeValueAsString(parent),
                    PARENT_CACHE_TTL);
        } catch (Exception e) {
            log.warn("写入 Redis 父分片缓存失败: parentChunkId={}, 原因={}",
                    parentChunkId, e.getMessage());
        }
    }

    /**
     * 将数据库父分片转换为 LangChain4j TextSegment。
     *
     * <p>metadata 为父分片持久化的 JSON 字符串，解析后补充 doc_id，与 ES 侧
     * 写入时的元数据保持一致；解析失败时降级为空 metadata。</p>
     *
     * @param parent 父分片实体
     * @return 包含父分片文本和元数据的 TextSegment
     */
    private TextSegment toParentSegment(KnowledgeSegment parent) {
        Map<String, Object> esMetadata = parseMetadataMap(parent.getMetadata());
        if (parent.getDocId() != null) {
            esMetadata.put("doc_id", parent.getDocId());
        }
        return TextSegment.from(parent.getText(), Metadata.from(esMetadata));
    }

    /**
     * 将父分片的 metadata JSON 反序列化为 Map。
     *
     * @param metadataJson metadata JSON 字符串，可能为 null 或非法 JSON
     * @return 元数据 Map，解析失败时返回空 Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMetadataMap(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return jsonMapper.readValue(metadataJson, Map.class);
        } catch (Exception e) {
            log.warn("父分片 metadata JSON 反序列化失败, 降级为空 Map: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * 对两路命中执行 RRF 融合：同一文档的贡献分数累加，按总分降序返回前 N 条。
     *
     * <p>ES 的 _id 与 KNN 的 embeddingId 是同一文档标识，因此可安全作为去重 key；
     * 文档同时被两路命中时，只返回一次且 RRF 分数为两路贡献之和。KNN 子分片替换为
     * 父分片后，其 id 为父分片 chunk_id，与 BM25 命中的 ES _id 仍是同一去重空间。</p>
     *
     * @param bm25Hits BM25 命中列表
     * @param knnHits  KNN 命中列表
     * @return 按 RRF 分数降序排列的内容列表，最多 candidate-count 条候选
     */
    private List<Content> fuse(List<ScoredHit> bm25Hits, List<ScoredHit> knnHits) {
        // LinkedHashMap 保持文档首次出现顺序，作为同分时的稳定排序依据。
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, TextSegment> segments = new LinkedHashMap<>();
        addRrfContributions(rrfScores, segments, bm25Hits);
        addRrfContributions(rrfScores, segments, knnHits);

        // RRF 是 ReRanker 前的粗排，保留 candidate-count 条候选供后续重排。
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(rerankerProperties.getCandidateCount())
                .map(entry -> Content.from(
                        segments.get(entry.getKey()),
                        Map.of(
                                ContentMetadata.SCORE, entry.getValue(),
                                ContentMetadata.EMBEDDING_ID, entry.getKey())))
                .toList();
    }

    /**
     * 将一路命中的排名贡献写入 RRF 分数表，并在首见时记录对应 TextSegment。
     *
     * @param scores   RRF 分数表，key 为文档 ID
     * @param segments 文档 ID 到 TextSegment 的映射
     * @param hits     当前通道的命中列表，列表顺序即排名
     */
    private void addRrfContributions(Map<String, Double> scores,
                                     Map<String, TextSegment> segments,
                                     List<ScoredHit> hits) {
        for (int i = 0; i < hits.size(); i++) {
            ScoredHit hit = hits.get(i);
            // 排名从 1 开始，RRF 单路贡献 = 1 / (k + rank)。
            double contribution = 1.0 / (RRF_K + i + 1);
            scores.merge(hit.id(), contribution, Double::sum);
            segments.putIfAbsent(hit.id(), hit.segment());
        }
    }

    /**
     * 单路降级时，将命中列表按原始分数降序转换为内容列表。
     *
     * <p>此时没有融合过程，SCORE 直接写入该通道的原始相关度分数，便于上层
     * 按分数语义做阈值或排序处理。</p>
     *
     * @param hits 单通道命中列表
     * @return 内容列表，最多 {@value #RESULTS_PER_CHANNEL} 条
     */
    private List<Content> toContent(List<ScoredHit> hits) {
        return hits.stream()
                .sorted(Comparator.comparingDouble(ScoredHit::score).reversed())
                .limit(RESULTS_PER_CHANNEL)
                .map(hit -> Content.from(
                        hit.segment(),
                        Map.of(
                                ContentMetadata.SCORE, hit.score(),
                                ContentMetadata.EMBEDDING_ID, hit.id())))
                .toList();
    }

    /**
     * 对 RRF 融合或单路降级得到的候选执行"可选 BGE 重排 → Top N"。
     *
     * <p>重排只改变顺序并补充 RERANKED_SCORE，不会增减候选；最终统一截断到
     * top-n 条返回，保证关闭重排时行为与重排不可用时一致。</p>
     *
     * @param candidates   RRF 融合或单路降级的候选内容
     * @param originalQuery 用户原始问题，作为 ReRanker 的评分 query
     * @return 重排并截断后的最终内容列表，最多 top-n 条
     */
    private List<Content> rerankAndTrim(List<Content> candidates, String originalQuery) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        // 可选重排：模型可用时按 BGE 分数降序，否则保持原排序。
        List<Content> ranked = tryRerank(candidates, originalQuery);
        // 最终只返回 Top N。
        return ranked.stream().limit(rerankerProperties.getTopN()).toList();
    }

    /**
     * 调用 BGE ReRanker 对候选按相关度降序重排，并保留 BGE 分数。
     *
     * <p>模型禁用、未注入或候选不足时直接返回原排序；评分数量与候选不一致或推理
     * 异常时记录原因并回退原排序，保证检索可用性。重排后的内容保留原有 SCORE 与
     * EMBEDDING_ID，额外写入 RERANKED_SCORE。</p>
     *
     * @param candidates   RRF 融合或单路降级的候选内容
     * @param originalQuery 用户原始问题
     * @return 重排后的内容列表；模型不可用时保持原排序
     */
    private List<Content> tryRerank(List<Content> candidates, String originalQuery) {
        // 模型禁用、未注入或候选不足时跳过重排，直接返回原排序。
        if (!rerankerProperties.isEnabled() || scoringModel == null
                || candidates.size() <= 1) {
            log.debug("BGE ReRanker 未参与重排: enabled={}, candidates={}",
                    rerankerProperties.isEnabled(), candidates.size());
            return candidates;
        }
        try {
            List<TextSegment> segments = candidates.stream()
                    .map(Content::textSegment)
                    .toList();
            // ReRanker 使用用户原问题而非改写问题，避免改写引入的语义偏移。
            Response<List<Double>> response = scoringModel.scoreAll(segments, originalQuery);
            List<Double> scores = response.content();
            if (scores == null || scores.size() != segments.size()) {
                // 评分数量与候选不一致时回退原排序，避免分数错位。
                log.warn("BGE 评分数量不一致, 回退原排序: candidates={}, scores={}",
                        segments.size(), scores == null ? 0 : scores.size());
                return candidates;
            }
            // 按 BGE 分数降序重排；保留原 SCORE/EMBEDDING_ID，补充 RERANKED_SCORE。
            return IntStream.range(0, candidates.size())
                    .boxed()
                    .sorted(Comparator.comparingDouble(
                            (Integer index) -> scores.get(index)).reversed())
                    .map(index -> {
                        Content original = candidates.get(index);
                        Map<ContentMetadata, Object> metadata =
                                new LinkedHashMap<>(original.metadata());
                        metadata.put(ContentMetadata.RERANKED_SCORE, scores.get(index));
                        return Content.from(original.textSegment(), metadata);
                    })
                    .toList();
        } catch (Exception e) {
            // 推理异常或模型不可用时回退原排序，保证检索可用。
            log.warn("BGE ReRanker 推理失败, 回退原排序: {}", e.getMessage());
            return candidates;
        }
    }

    /**
     * 单路检索命中的统一视图，屏蔽 BM25 与 KNN 结果的数据结构差异。
     *
     * @param id      文档 ID（ES _id / embeddingId，父分片替换后为父分片 chunk_id）
     * @param segment 命中的文本片段，包含原文本和 metadata
     * @param score   该通道的原始相关度分数
     */
    private record ScoredHit(String id, TextSegment segment, double score) {
    }
}

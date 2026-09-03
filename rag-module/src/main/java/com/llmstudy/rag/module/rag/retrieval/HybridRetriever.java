package com.llmstudy.rag.module.rag.retrieval;

import com.llmstudy.rag.auth.model.AccessContext;
import com.llmstudy.rag.config.RetrievalProperties;
import com.llmstudy.rag.entity.KnowledgeDocument;
import com.llmstudy.rag.mapper.KnowledgeDocumentMapper;
import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RetrievalQueryPlan;
import com.llmstudy.rag.module.rag.query.DocumentMentionMatcher;
import com.llmstudy.rag.module.rag.query.QueryPageHint;
import com.llmstudy.rag.module.rag.query.RetrievalQueryScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 并行执行语言感知 BM25 与 KNN，再为每条扩展查询各补一路。
 *
 * <p>主两路在单次 ES 请求内按文档语言选择中英文 query。改写策略产出的扩展查询
 * （同义改写 / 子问题 / HyDE 假设答案）以及用户原问题，各自作为独立通道参与 RRF：
 * 被多条查询同时命中的片段会累加 {@code 1/(k+rank)}，从而在进 ReRanker 之前就排到前面。</p>
 *
 * <p>任意一路失败都只记录并继续，全部失败才抛异常；扩展路失败不影响主两路。</p>
 */
@Component
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final Bm25Retriever bm25Retriever;
    private final KnnRetriever knnRetriever;
    private final KnowledgeDocumentMapper documentMapper;
    private final RetrievalProperties properties;
    private final Executor retrievalExecutor;

    @Autowired
    public HybridRetriever(Bm25Retriever bm25Retriever,
                           KnnRetriever knnRetriever,
                           KnowledgeDocumentMapper documentMapper,
                           RetrievalProperties properties,
                           @Qualifier("retrievalExecutor") Executor retrievalExecutor) {
        this.bm25Retriever = bm25Retriever;
        this.knnRetriever = knnRetriever;
        this.documentMapper = documentMapper;
        this.properties = properties;
        this.retrievalExecutor = retrievalExecutor;
    }

    /**
     * 保留给不依赖数据库当前版本快照的单元测试。
     * 生产 Spring 注入使用上方包含 {@link KnowledgeDocumentMapper} 的构造器。
     */
    public HybridRetriever(Bm25Retriever bm25Retriever, KnnRetriever knnRetriever,
                           RetrievalProperties properties) {
        this.bm25Retriever = bm25Retriever;
        this.knnRetriever = knnRetriever;
        this.documentMapper = null;
        this.properties = properties;
        this.retrievalExecutor = Runnable::run;
    }

    /** 无访问上下文的兼容入口，主要供单测使用。 */
    public RetrievalResult retrieve(RetrievalQueryPlan plan) {
        return retrieve(plan, null);
    }

    /**
     * 按当前访问身份并行执行两路检索。
     *
     * <p>两个检索通道共享同一份版本快照，避免发布恰好发生在查询之间时混用版本。
     * 没有可读版本时直接返回空候选，避免向 ES 发出无法命中的无意义查询。</p>
     *
     * @param plan          中英文独立查询
     * @param accessContext 当前请求身份；诊断和 Dataset 可为空，表示全部已发布版本
     */
    public RetrievalResult retrieve(RetrievalQueryPlan plan, AccessContext accessContext) {
        List<String> currentVersionIds = documentMapper == null
                ? null : accessContext == null
                ? documentMapper.findAllCurrentVersionIds()
                : documentMapper.findAccessibleCurrentVersionIds(
                        accessContext.userId(), accessContext.organizationId(),
                        accessContext.isSystemAdmin());
        if (currentVersionIds != null && currentVersionIds.isEmpty()) {
            return RetrievalResult.empty();
        }
        // Resolve entities before classifying scope: natural comparisons rarely say "two papers".
        List<KnowledgeDocument> availableDocuments = documentMapper != null
                ? accessContext == null
                        ? documentMapper.findAll()
                        : documentMapper.findAccessible(
                                accessContext.userId(), accessContext.organizationId(),
                                accessContext.isSystemAdmin())
                : List.of();
        if (currentVersionIds != null) {
            availableDocuments = availableDocuments.stream()
                    .filter(document -> currentVersionIds.contains(document.getCurrentVersionId()))
                    .toList();
        }
        List<String> mentionedVersions = DocumentMentionMatcher.mentionedVersionIds(
                plan.originalQuestion(), availableDocuments);
        if (currentVersionIds != null) {
            mentionedVersions = mentionedVersions.stream()
                    .filter(currentVersionIds::contains)
                    .toList();
        }
        RetrievalQueryScope scope = RetrievalQueryScope.from(plan, mentionedVersions.size());
        int requestedTopK = scope.comprehensive()
                ? Math.max(properties.getPerQueryTopK(),
                        properties.getComprehensivePerQueryTopK())
                : properties.getPerQueryTopK();
        if (!QueryPageHint.pages(plan.originalQuestion()).isEmpty()) {
            requestedTopK = Math.max(requestedTopK, QueryPageHint.RECALL_TOP_K);
        }
        int topK = Math.max(1, requestedTopK);
        List<String> targetVersions = mentionedVersions;
        RetrievalQueryPlan focusedPlan = targetVersions.size() < 2 ? plan
                : new RetrievalQueryPlan(
                        plan.originalQuestion(),
                        DocumentMentionMatcher.withoutDocumentMentions(
                                plan.standaloneZh(), availableDocuments),
                        DocumentMentionMatcher.withoutDocumentMentions(
                                plan.standaloneEn(), availableDocuments));
        CompletableFuture<Lane> bm25Future = CompletableFuture.supplyAsync(
                () -> runBm25(plan, focusedPlan, currentVersionIds, targetVersions, topK),
                retrievalExecutor);
        CompletableFuture<Lane> knnFuture = CompletableFuture.supplyAsync(
                () -> runKnn(plan, currentVersionIds, topK), retrievalExecutor);
        // 扩展路整体只用一次 embedding 批量编码，词面与向量各自并行发起。
        List<CompletableFuture<Lane>> expansionFutures = expansionLanes(
                plan, currentVersionIds, topK);
        List<Lane> expansionResults = expansionFutures.stream()
                .map(CompletableFuture::join)
                .toList();
        RetrievalResult result = new RetrievalResult(
                bm25Future.join(), knnFuture.join(), mentionedVersions, expansionResults);
        // 只看主两路：扩展路是尽力而为的补充，它们即使返回空也不能掩盖主链路全挂。
        if (result.bm25().failed() && result.knn().failed()) {
            IllegalStateException failure = new IllegalStateException("BM25 与 KNN 检索均失败");
            for (Lane lane : result.lanes()) {
                if (lane.cause() != null) {
                    failure.addSuppressed(lane.cause());
                }
            }
            throw failure;
        }
        return result;
    }

    /**
     * 为原问题和每条扩展查询各建一路 BM25 与一路 KNN。
     *
     * <p>向量侧先一次性批量编码全部扩展文本，避免每路各打一次 embedding 接口；
     * 编码失败时只放弃向量扩展路，词面扩展路照常执行。</p>
     */
    private List<CompletableFuture<Lane>> expansionLanes(RetrievalQueryPlan plan,
                                                         List<String> versionIds,
                                                         int topK) {
        List<String> queries = plan.fusionQueries();
        if (queries.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors;
        try {
            vectors = knnRetriever.embedAll(queries);
            if (vectors != null && vectors.size() != queries.size()) {
                vectors = null;
            }
        } catch (Exception e) {
            log.warn("扩展查询批量 embedding 失败，仅保留词面扩展路", e);
            vectors = null;
        }
        List<CompletableFuture<Lane>> futures = new java.util.ArrayList<>();
        for (int index = 0; index < queries.size(); index++) {
            String query = queries.get(index);
            futures.add(CompletableFuture.supplyAsync(
                    () -> runExpansionBm25(query, versionIds, topK), retrievalExecutor));
            if (vectors != null) {
                float[] vector = vectors.get(index);
                futures.add(CompletableFuture.supplyAsync(
                        () -> runExpansionKnn(query, vector, versionIds, topK),
                        retrievalExecutor));
            }
        }
        return List.copyOf(futures);
    }

    private Lane runExpansionBm25(String query, List<String> versionIds, int topK) {
        long started = System.nanoTime();
        try {
            return Lane.ok("bm25-expansion", query,
                    bm25Retriever.retrieve(query, versionIds, topK), elapsedMs(started));
        } catch (Exception e) {
            log.warn("扩展查询 BM25 失败，跳过该路: {}", query, e);
            return Lane.failed("bm25-expansion", query, e, elapsedMs(started));
        }
    }

    private Lane runExpansionKnn(String query, float[] vector,
                                 List<String> versionIds, int topK) {
        long started = System.nanoTime();
        try {
            return Lane.ok("knn-expansion", query,
                    knnRetriever.search(vector, versionIds, topK), elapsedMs(started));
        } catch (Exception e) {
            log.warn("扩展查询 KNN 失败，跳过该路: {}", query, e);
            return Lane.failed("knn-expansion", query, e, elapsedMs(started));
        }
    }

    private Lane runBm25(RetrievalQueryPlan plan,
                         RetrievalQueryPlan focusedPlan,
                         List<String> versionIds,
                         List<String> mentionedVersions, int topK) {
        long started = System.nanoTime();
        try {
            List<RetrievalCandidate> global =
                    bm25Retriever.retrieve(plan, versionIds, topK);
            List<List<RetrievalCandidate>> focused = new java.util.ArrayList<>();
            if (mentionedVersions.size() >= 2) {
                int perDocument = Math.max(1, properties.getCrossDocumentMaxChunks());
                for (String versionId : mentionedVersions) {
                    List<RetrievalCandidate> documentHits = bm25Retriever.retrieve(
                            focusedPlan, List.of(versionId), perDocument);
                    List<RetrievalCandidate> annotated = new java.util.ArrayList<>();
                    for (int index = 0; index < documentHits.size(); index++) {
                        annotated.add(withFocusedRerankQuery(
                                documentHits.get(index), focusedPlan, index + 1));
                    }
                    focused.add(List.copyOf(annotated));
                }
            }
            return Lane.ok("bm25", diagnose(plan),
                    mergeFocused(focused, global, topK), elapsedMs(started));
        } catch (Exception e) {
            log.error("BM25 检索失败，继续 KNN 通道", e);
            return Lane.failed("bm25", diagnose(plan), e, elapsedMs(started));
        }
    }

    private static RetrievalCandidate withFocusedRerankQuery(
            RetrievalCandidate candidate,
            RetrievalQueryPlan focusedPlan,
            int documentRank) {
        Map<String, Object> metadata = new LinkedHashMap<>(candidate.metadata());
        metadata.put(SegmentMetadataKeys.RERANK_QUERY_ZH, focusedPlan.standaloneZh());
        metadata.put(SegmentMetadataKeys.RERANK_QUERY_EN, focusedPlan.standaloneEn());
        metadata.put(SegmentMetadataKeys.FOCUSED_DOCUMENT_RANK, documentRank);
        return candidate.withMetadata(metadata);
    }

    /** 每篇先取第 1 条、再取第 2 条，随后用全库结果补足，保证显式点名文档入围。 */
    private static List<RetrievalCandidate> mergeFocused(
            List<List<RetrievalCandidate>> focused,
            List<RetrievalCandidate> global,
            int limit) {
        Map<String, RetrievalCandidate> merged = new LinkedHashMap<>();
        int maxFocused = focused.stream().mapToInt(List::size).max().orElse(0);
        for (int rank = 0; rank < maxFocused; rank++) {
            for (List<RetrievalCandidate> documentHits : focused) {
                if (rank < documentHits.size()) {
                    RetrievalCandidate candidate = documentHits.get(rank);
                    merged.putIfAbsent(candidate.id(), candidate);
                }
            }
        }
        for (RetrievalCandidate candidate : global) {
            merged.putIfAbsent(candidate.id(), candidate);
        }
        return merged.values().stream().limit(Math.max(1, limit)).toList();
    }

    private Lane runKnn(RetrievalQueryPlan plan, List<String> versionIds, int topK) {
        long started = System.nanoTime();
        try {
            return Lane.ok("knn", diagnose(plan),
                    knnRetriever.retrieve(plan, versionIds, topK), elapsedMs(started));
        } catch (Exception e) {
            log.error("KNN 检索失败，继续 BM25 通道", e);
            return Lane.failed("knn", diagnose(plan), e, elapsedMs(started));
        }
    }

    private static String diagnose(RetrievalQueryPlan plan) {
        return "strategy=document-language; ZH=" + plan.standaloneZh()
                + "; EN=" + plan.standaloneEn();
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /**
     * 单路召回结果。
     *
     * @param skipped 保留诊断 DTO 兼容字段；两路检索不使用跳过状态
     */
    public record Lane(String channel, String query, List<RetrievalCandidate> hits,
                       String error, boolean skipped, long elapsedMs, Exception cause) {

        public static Lane ok(String channel, String query, List<RetrievalCandidate> hits,
                       long elapsedMs) {
            return new Lane(channel, query, List.copyOf(hits), null, false, elapsedMs, null);
        }

        public static Lane failed(String channel, String query, Exception cause, long elapsedMs) {
            return new Lane(channel, query, List.of(), cause.getMessage(),
                    false, elapsedMs, cause);
        }

        public static Lane skipped(String channel, String query) {
            return new Lane(channel, query, List.of(), "query-deduplicated", true, 0, null);
        }

        /** 真正执行失败。跳过和空命中都不算失败。 */
        public boolean failed() {
            return !skipped && error != null;
        }
    }

    /**
     * 两路原始结果。
     *
     * <p>{@link #successful()} 只返回真正执行成功的路，供两路 RRF 使用；
     * 失败路和跳过路等价于公式里的 hit=0。</p>
     */
    public record RetrievalResult(Lane bm25, Lane knn, List<String> mentionedVersionIds,
                                 List<Lane> expansionLanes) {

        public RetrievalResult {
            mentionedVersionIds = List.copyOf(mentionedVersionIds);
            expansionLanes = expansionLanes == null ? List.of() : List.copyOf(expansionLanes);
        }

        public RetrievalResult(Lane bm25, Lane knn, List<String> mentionedVersionIds) {
            this(bm25, knn, mentionedVersionIds, List.of());
        }

        public RetrievalResult(Lane bm25, Lane knn) {
            this(bm25, knn, List.of(), List.of());
        }

        public RetrievalQueryScope scope(RetrievalQueryPlan plan) {
            return RetrievalQueryScope.from(plan, mentionedVersionIds.size());
        }

        public static RetrievalResult empty() {
            return new RetrievalResult(
                    Lane.ok("bm25", "", List.of(), 0),
                    Lane.ok("knn", "", List.of(), 0));
        }

        /** 主两路在前，扩展路在后；诊断与 RRF 共用同一份顺序。 */
        public List<Lane> lanes() {
            List<Lane> all = new java.util.ArrayList<>(2 + expansionLanes.size());
            all.add(bm25);
            all.add(knn);
            all.addAll(expansionLanes);
            return List.copyOf(all);
        }

        /** 参与 RRF 的成功路命中列表，保持 bm25 / knn / 扩展路顺序。 */
        public List<List<RetrievalCandidate>> successful() {
            return successfulLanes().stream().map(Lane::hits).toList();
        }

        /**
         * 与 {@link #successful()} 一一对应的 RRF 权重。
         *
         * @param expansionWeight 扩展路权重；主两路恒为 1.0
         */
        public List<Double> laneWeights(double expansionWeight) {
            return successfulLanes().stream()
                    .map(lane -> lane.channel().endsWith("-expansion") ? expansionWeight : 1.0)
                    .toList();
        }

        private List<Lane> successfulLanes() {
            return lanes().stream()
                    .filter(lane -> !lane.failed() && !lane.skipped())
                    .toList();
        }

        public boolean bm25Degraded() {
            return bm25.failed();
        }

        public boolean knnDegraded() {
            return knn.failed();
        }
    }
}

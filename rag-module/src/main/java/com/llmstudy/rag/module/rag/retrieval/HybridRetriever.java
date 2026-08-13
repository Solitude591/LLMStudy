package com.llmstudy.rag.module.rag.retrieval;

import com.llmstudy.rag.auth.model.AccessContext;
import com.llmstudy.rag.config.RetrievalProperties;
import com.llmstudy.rag.mapper.KnowledgeDocumentMapper;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RetrievalQueryPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 独立执行中英文 BM25 与中英文 KNN，共四路。
 *
 * <p>单路失败记录后继续；BM25 两路都失败视为 BM25 通道降级，KNN 同理。
 * 四路都失败才抛异常。中英文查询相同时跳过重复路，不让同一文本贡献两次 RRF。</p>
 */
@Component
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final Bm25Retriever bm25Retriever;
    private final KnnRetriever knnRetriever;
    private final KnowledgeDocumentMapper documentMapper;
    private final RetrievalProperties properties;

    @Autowired
    public HybridRetriever(Bm25Retriever bm25Retriever,
                           KnnRetriever knnRetriever,
                           KnowledgeDocumentMapper documentMapper,
                           RetrievalProperties properties) {
        this.bm25Retriever = bm25Retriever;
        this.knnRetriever = knnRetriever;
        this.documentMapper = documentMapper;
        this.properties = properties;
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
    }

    /** 无访问上下文的兼容入口，主要供单测使用。 */
    public RetrievalResult retrieve(RetrievalQueryPlan plan) {
        return retrieve(plan, null);
    }

    /**
     * 按当前访问身份执行四路检索。
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
        int topK = Math.max(1, properties.getPerQueryTopK());
        Lane zhBm25 = runBm25("zh_bm25", plan.standaloneZh(), currentVersionIds, topK);
        // 中英文完全相同则跳过英文 BM25，RRF 只计入一路。
        Lane enBm25 = plan.duplicateLanguage()
                ? Lane.skipped("en_bm25", plan.standaloneEn())
                : runBm25("en_bm25", plan.standaloneEn(), currentVersionIds, topK);
        List<Lane> knnLanes = runKnn(plan, currentVersionIds, topK);
        RetrievalResult result = new RetrievalResult(
                zhBm25, enBm25, knnLanes.get(0), knnLanes.get(1));
        if (result.successful().isEmpty()) {
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

    private Lane runBm25(String channel, String query, List<String> versionIds, int topK) {
        long started = System.nanoTime();
        try {
            return Lane.ok(channel, query,
                    bm25Retriever.retrieve(query, versionIds, topK), elapsedMs(started));
        } catch (Exception e) {
            log.error("{} 检索失败，继续其他通道", channel, e);
            return Lane.failed(channel, query, e, elapsedMs(started));
        }
    }

    /**
     * 先批量 embedding，再按向量分别搜。
     *
     * <p>embedding 失败视为 KNN 两路都失败；单次 search 失败只废这一路。
     * 查询去重后可能只有一个向量，英文 KNN 标记为 skipped。</p>
     */
    private List<Lane> runKnn(RetrievalQueryPlan plan, List<String> versionIds, int topK) {
        long embedStarted = System.nanoTime();
        List<float[]> vectors;
        try {
            vectors = knnRetriever.embedAll(plan.uniqueQueries());
        } catch (Exception e) {
            log.error("KNN embedding 失败，KNN 两路均降级", e);
            long elapsed = elapsedMs(embedStarted);
            return List.of(
                    Lane.failed("zh_knn", plan.standaloneZh(), e, elapsed),
                    plan.duplicateLanguage()
                            ? Lane.skipped("en_knn", plan.standaloneEn())
                            : Lane.failed("en_knn", plan.standaloneEn(), e, elapsed));
        }
        Lane zh = searchKnn("zh_knn", plan.standaloneZh(), vectors.getFirst(), versionIds, topK);
        if (plan.duplicateLanguage()) {
            return List.of(zh, Lane.skipped("en_knn", plan.standaloneEn()));
        }
        return List.of(zh, searchKnn("en_knn", plan.standaloneEn(),
                vectors.get(1), versionIds, topK));
    }

    private Lane searchKnn(String channel, String query, float[] vector,
                           List<String> versionIds, int topK) {
        long started = System.nanoTime();
        try {
            return Lane.ok(channel, query,
                    knnRetriever.search(vector, versionIds, topK), elapsedMs(started));
        } catch (Exception e) {
            log.error("{} 检索失败，继续其他通道", channel, e);
            return Lane.failed(channel, query, e, elapsedMs(started));
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /**
     * 单路召回结果。
     *
     * @param skipped true 表示因中英文查询相同而跳过，不是失败
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
     * 四路原始结果。
     *
     * <p>{@link #successful()} 只返回真正执行成功的路，供四路 RRF 使用；
     * 失败路和跳过路等价于公式里的 hit=0。</p>
     */
    public record RetrievalResult(Lane zhBm25, Lane enBm25, Lane zhKnn, Lane enKnn) {

        public static RetrievalResult empty() {
            return new RetrievalResult(
                    Lane.ok("zh_bm25", "", List.of(), 0),
                    Lane.ok("en_bm25", "", List.of(), 0),
                    Lane.ok("zh_knn", "", List.of(), 0),
                    Lane.ok("en_knn", "", List.of(), 0));
        }

        public List<Lane> lanes() {
            return List.of(zhBm25, enBm25, zhKnn, enKnn);
        }

        /** 参与 RRF 的成功路命中列表，保持 zh_bm25 / en_bm25 / zh_knn / en_knn 顺序。 */
        public List<List<RetrievalCandidate>> successful() {
            return lanes().stream()
                    .filter(lane -> !lane.failed() && !lane.skipped())
                    .map(Lane::hits)
                    .toList();
        }

        public boolean bm25Degraded() {
            return zhBm25.failed() && (enBm25.failed() || enBm25.skipped());
        }

        public boolean knnDegraded() {
            return zhKnn.failed() && (enKnn.failed() || enKnn.skipped());
        }
    }
}

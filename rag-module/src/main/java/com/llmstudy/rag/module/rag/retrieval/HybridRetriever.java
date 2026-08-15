package com.llmstudy.rag.module.rag.retrieval;

import com.llmstudy.rag.auth.model.AccessContext;
import com.llmstudy.rag.config.RetrievalProperties;
import com.llmstudy.rag.mapper.KnowledgeDocumentMapper;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RetrievalQueryPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 并行执行语言感知 BM25 与 KNN，共两路。
 *
 * <p>每路在单次 ES 请求内按文档语言选择中英文 query。
 * 单路失败记录后继续，两路都失败才抛异常。</p>
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
        int topK = Math.max(1, properties.getPerQueryTopK());
        CompletableFuture<Lane> bm25Future = CompletableFuture.supplyAsync(
                () -> runBm25(plan, currentVersionIds, topK), retrievalExecutor);
        CompletableFuture<Lane> knnFuture = CompletableFuture.supplyAsync(
                () -> runKnn(plan, currentVersionIds, topK), retrievalExecutor);
        RetrievalResult result = new RetrievalResult(bm25Future.join(), knnFuture.join());
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

    private Lane runBm25(RetrievalQueryPlan plan, List<String> versionIds, int topK) {
        long started = System.nanoTime();
        try {
            return Lane.ok("bm25", diagnose(plan),
                    bm25Retriever.retrieve(plan, versionIds, topK), elapsedMs(started));
        } catch (Exception e) {
            log.error("BM25 检索失败，继续 KNN 通道", e);
            return Lane.failed("bm25", diagnose(plan), e, elapsedMs(started));
        }
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
    public record RetrievalResult(Lane bm25, Lane knn) {

        public static RetrievalResult empty() {
            return new RetrievalResult(
                    Lane.ok("bm25", "", List.of(), 0),
                    Lane.ok("knn", "", List.of(), 0));
        }

        public List<Lane> lanes() {
            return List.of(bm25, knn);
        }

        /** 参与 RRF 的成功路命中列表，保持 bm25 / knn 顺序。 */
        public List<List<RetrievalCandidate>> successful() {
            return lanes().stream()
                    .filter(lane -> !lane.failed() && !lane.skipped())
                    .map(Lane::hits)
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

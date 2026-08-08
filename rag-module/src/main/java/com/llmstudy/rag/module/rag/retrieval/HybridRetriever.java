package com.llmstudy.rag.module.rag.retrieval;

import com.llmstudy.rag.mapper.KnowledgeDocumentMapper;
import com.llmstudy.rag.auth.model.AccessContext;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RewrittenQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 独立执行 BM25 和 KNN 通道，并实现单通道故障降级。
 *
 * <p>受保护检索会先从 MySQL 取得当前用户可读的“已发布版本 ID 快照”，再把同一集合
 * 同时交给两个检索通道，既避免把权限字段写入 Elasticsearch，也避免双路权限漂移。</p>
 */
@Component
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);
    private final Bm25Retriever bm25Retriever;
    private final KnnRetriever knnRetriever;
    private final KnowledgeDocumentMapper documentMapper;

    @Autowired
    public HybridRetriever(Bm25Retriever bm25Retriever,
                           KnnRetriever knnRetriever,
                           KnowledgeDocumentMapper documentMapper) {
        this.bm25Retriever = bm25Retriever;
        this.knnRetriever = knnRetriever;
        this.documentMapper = documentMapper;
    }

    /**
     * 保留给不依赖数据库当前版本快照的单元测试和独立使用场景。
     * 生产 Spring 注入使用上方包含 {@link KnowledgeDocumentMapper} 的构造器。
     */
    public HybridRetriever(Bm25Retriever bm25Retriever, KnnRetriever knnRetriever) {
        this.bm25Retriever = bm25Retriever;
        this.knnRetriever = knnRetriever;
        this.documentMapper = null;
    }

    /**
     * 执行双路检索。任一通道失败时返回另一通道，双路都失败时抛出异常。
     *
     * @param query 原问题与改写问题
     * @return 双路候选及是否发生单路降级的标记
     */
    public RetrievalResult retrieve(RewrittenQuery query) {
        return retrieve(query, null);
    }

    /**
     * 按当前访问身份执行双路检索。
     *
     * @param query 原始问题和语义改写结果
     * @param accessContext 当前请求的权限上下文；兼容测试场景时可为空
     * @return BM25/KNN 原始候选和降级状态
     */
    public RetrievalResult retrieve(RewrittenQuery query, AccessContext accessContext) {
        // 两个检索通道共享同一份指针快照，避免发布恰好发生在双路查询之间时混用版本。
        List<String> currentVersionIds = documentMapper == null
                ? null : accessContext == null
                ? documentMapper.findAllCurrentVersionIds()
                : documentMapper.findAccessibleCurrentVersionIds(
                        accessContext.userId(), accessContext.organizationId(),
                        accessContext.isSystemAdmin());
        if (currentVersionIds != null && currentVersionIds.isEmpty()) {
            // 没有可读版本时直接返回空候选，避免向 ES 发出无法命中的无意义查询。
            return new RetrievalResult(List.of(), List.of(), false);
        }
        List<RetrievalCandidate> bm25 = List.of();
        List<RetrievalCandidate> knn = List.of();
        Exception bm25Failure = null;
        Exception knnFailure = null;
        // 两个 try 必须相互独立，否则第一通道失败会阻止第二通道完成降级。
        try {
            bm25 = currentVersionIds == null
                    ? bm25Retriever.retrieve(query.originalQuestion())
                    : bm25Retriever.retrieve(query.originalQuestion(), currentVersionIds);
        } catch (Exception e) {
            bm25Failure = e;
            log.error("BM25 检索失败，尝试 KNN 降级", e);
        }
        try {
            knn = currentVersionIds == null
                    ? knnRetriever.retrieve(query.rewrittenQuestion())
                    : knnRetriever.retrieve(query.rewrittenQuestion(), currentVersionIds);
        } catch (Exception e) {
            knnFailure = e;
            log.error("KNN 检索失败，尝试 BM25 降级", e);
        }
        if (bm25Failure != null && knnFailure != null) {
            // 主异常保留 BM25 原因，KNN 原因作为 suppressed exception 方便完整排查。
            IllegalStateException failure = new IllegalStateException(
                    "BM25 与 KNN 检索均失败", bm25Failure);
            failure.addSuppressed(knnFailure);
            throw failure;
        }
        return new RetrievalResult(bm25, knn,
                bm25Failure != null || knnFailure != null);
    }

    /** 双路检索原始候选及单通道降级状态。 */
    public record RetrievalResult(List<RetrievalCandidate> bm25,
                                  List<RetrievalCandidate> knn,
                                  boolean degraded) {
        public RetrievalResult {
            // 固化两个通道的返回结果，防止后续融合阶段观察到列表被修改。
            bm25 = List.copyOf(bm25);
            knn = List.copyOf(knn);
        }
    }
}

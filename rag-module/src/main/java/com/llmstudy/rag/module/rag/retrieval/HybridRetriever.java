package com.llmstudy.rag.module.rag.retrieval;

import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RewrittenQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/** 独立执行 BM25 和 KNN 通道，并实现单通道故障降级。 */
@Component
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);
    private final Bm25Retriever bm25Retriever;
    private final KnnRetriever knnRetriever;

    public HybridRetriever(Bm25Retriever bm25Retriever, KnnRetriever knnRetriever) {
        this.bm25Retriever = bm25Retriever;
        this.knnRetriever = knnRetriever;
    }

    /**
     * 执行双路检索。任一通道失败时返回另一通道，双路都失败时抛出异常。
     *
     * @param query 原问题与改写问题
     * @return 双路候选及是否发生单路降级的标记
     */
    public RetrievalResult retrieve(RewrittenQuery query) {
        List<RetrievalCandidate> bm25 = List.of();
        List<RetrievalCandidate> knn = List.of();
        Exception bm25Failure = null;
        Exception knnFailure = null;
        // 两个 try 必须相互独立，否则第一通道失败会阻止第二通道完成降级。
        try {
            bm25 = bm25Retriever.retrieve(query.originalQuestion());
        } catch (Exception e) {
            bm25Failure = e;
            log.error("BM25 检索失败，尝试 KNN 降级", e);
        }
        try {
            knn = knnRetriever.retrieve(query.rewrittenQuestion());
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
            bm25 = List.copyOf(bm25);
            knn = List.copyOf(knn);
        }
    }
}

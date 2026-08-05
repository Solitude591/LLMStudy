package com.llmstudy.rag.module.rag.aggregation;

import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RewrittenQuery;
import com.llmstudy.rag.module.rag.retrieval.HybridRetriever;

import java.util.List;

/** 对多路检索候选执行融合、可选重排和 Top-N 截断的端口。 */
public interface RetrievalAggregator {

    /**
     * @param query  保留原问题和改写问题的查询对象
     * @param result BM25/KNN 双路结果及降级标记
     * @return 最终交给 Prompt 注入器的有序候选
     */
    List<RetrievalCandidate> aggregate(RewrittenQuery query,
                                       HybridRetriever.RetrievalResult result);
}

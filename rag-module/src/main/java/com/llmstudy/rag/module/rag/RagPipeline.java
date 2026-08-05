package com.llmstudy.rag.module.rag;

import com.llmstudy.rag.module.rag.aggregation.RetrievalAggregator;
import com.llmstudy.rag.module.rag.model.RagRequest;
import com.llmstudy.rag.module.rag.model.RagResult;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RewrittenQuery;
import com.llmstudy.rag.module.rag.prompt.RagPromptInjector;
import com.llmstudy.rag.module.rag.query.QueryRewriter;
import com.llmstudy.rag.module.rag.retrieval.HybridRetriever;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 在线 RAG 唯一编排入口。
 *
 * <p>固定按“查询改写 → 混合检索 → 聚合/重排 → Prompt 注入”执行，
 * 各阶段通过项目自有模型交互，不依赖 Controller 或 HTTP DTO。</p>
 */
@Service
public class RagPipeline {

    private final QueryRewriter queryRewriter;
    private final HybridRetriever hybridRetriever;
    private final RetrievalAggregator aggregator;
    private final RagPromptInjector promptInjector;

    public RagPipeline(QueryRewriter queryRewriter,
                       HybridRetriever hybridRetriever,
                       RetrievalAggregator aggregator,
                       RagPromptInjector promptInjector) {
        this.queryRewriter = queryRewriter;
        this.hybridRetriever = hybridRetriever;
        this.aggregator = aggregator;
        this.promptInjector = promptInjector;
    }

    /**
     * 执行一次完整 RAG 处理，空候选时仍返回改写结果，Prompt 为 null。
     *
     * @param request 用户问题、会话上下文和意图注入策略
     * @return 最终 Prompt、查询改写结果与结构化引用
     */
    public RagResult execute(RagRequest request) {
        // 原问题供 BM25 保留精确词面信号，改写问题供 KNN 提升语义召回。
        RewrittenQuery rewritten = queryRewriter.rewrite(request);
        HybridRetriever.RetrievalResult retrieval = hybridRetriever.retrieve(rewritten);
        // 聚合器先融合双路排名，再按配置可选重排并统一截断 Top N。
        List<RetrievalCandidate> candidates = aggregator.aggregate(rewritten, retrieval);
        RagPromptInjector.Injection injection = promptInjector.inject(
                request, rewritten, candidates);
        return new RagResult(injection.prompt(), rewritten, injection.references());
    }
}

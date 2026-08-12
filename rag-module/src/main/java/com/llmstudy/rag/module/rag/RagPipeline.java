package com.llmstudy.rag.module.rag;

import com.llmstudy.rag.config.RerankerProperties;
import com.llmstudy.rag.enums.RagProgressStage;
import com.llmstudy.rag.module.rag.aggregation.RetrievalAggregator;
import com.llmstudy.rag.module.rag.model.RagRequest;
import com.llmstudy.rag.module.rag.model.RagResult;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RewrittenQuery;
import com.llmstudy.rag.module.rag.prompt.RagPromptInjector;
import com.llmstudy.rag.module.rag.query.QueryRewriter;
import com.llmstudy.rag.module.rag.retrieval.HybridRetriever;
import com.llmstudy.rag.module.rag.retrieval.ParentChunkExpander;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 在线 RAG 唯一编排入口。
 *
 * <p>固定按“查询改写 → 混合检索 → 聚合/重排 → 父片展开去重 → Top-N → Prompt 注入”执行。</p>
 */
@Service
public class RagPipeline {

    private final QueryRewriter queryRewriter;
    private final HybridRetriever hybridRetriever;
    private final RetrievalAggregator aggregator;
    private final ParentChunkExpander parentChunkExpander;
    private final RagPromptInjector promptInjector;
    private final RerankerProperties rerankerProperties;

    public RagPipeline(QueryRewriter queryRewriter,
                       HybridRetriever hybridRetriever,
                       RetrievalAggregator aggregator,
                       ParentChunkExpander parentChunkExpander,
                       RagPromptInjector promptInjector,
                       RerankerProperties rerankerProperties) {
        this.queryRewriter = queryRewriter;
        this.hybridRetriever = hybridRetriever;
        this.aggregator = aggregator;
        this.parentChunkExpander = parentChunkExpander;
        this.promptInjector = promptInjector;
        this.rerankerProperties = rerankerProperties;
    }

    /**
     * 执行一次完整 RAG：改写 → 双路检索 → 融合重排 → parent 展开 → Top-N → Prompt。
     *
     * <p>无进度回调的兼容入口；Dataset 与开发接口可继续调用本方法，
     * 内部委托给带空操作 Consumer 的重载，行为与改造前一致。</p>
     *
     * @return 空候选时 Prompt 为 null，但仍带改写结果
     */
    public RagResult execute(RagRequest request) {
        return execute(request, stage -> { });
    }

    /**
     * 带进度回调的完整 RAG。
     *
     * <p>在每个真实耗时边界<strong>开始之前</strong>调用 {@code progress.accept(...)}，
     * 表示该阶段已经进入，而不是伪造完成百分比。调用方（如 RagChatFlow）
     * 负责把 {@link RagProgressStage} 转成对外 SSE 事件；不关心进度时可传空操作。</p>
     */
    public RagResult execute(RagRequest request, Consumer<RagProgressStage> progress) {
        Objects.requireNonNull(progress, "progress");

        // 边界 1：查询改写（LLM 调用，可能较慢）
        progress.accept(RagProgressStage.QUESTION_ANALYSIS);
        RewrittenQuery rewritten = queryRewriter.rewrite(request);

        // 边界 2：混合检索（BM25 + 向量召回）
        progress.accept(RagProgressStage.KNOWLEDGE_RETRIEVAL);
        HybridRetriever.RetrievalResult retrieval = hybridRetriever.retrieve(
                rewritten, request.accessContext());

        // 边界 3：融合 / 重排 / 父片展开 / Prompt 注入（对用户统一表述为「整理资料」）
        progress.accept(RagProgressStage.EVIDENCE_ORGANIZATION);
        // 保留 candidateCount 量级，供 parent 去重后仍有足够不同章节可补位。
        List<RetrievalCandidate> fused = aggregator.aggregate(rewritten, retrieval);
        List<RetrievalCandidate> expanded = parentChunkExpander.expand(fused);
        // 最终 Top-N 必须在 parent 去重之后，否则同 parent 的多个 child 会挤掉其他证据。
        List<RetrievalCandidate> candidates = expanded.stream()
                .limit(Math.max(1, rerankerProperties.getTopN()))
                .toList();
        RagPromptInjector.Injection injection = promptInjector.inject(
                request, rewritten, candidates);
        List<String> chunks = candidates.stream()
                .map(RetrievalCandidate::text)
                .toList();
        return new RagResult(injection.prompt(), rewritten, injection.references(), chunks);
    }
}

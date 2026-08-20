package com.llmstudy.rag.module.rag;

import com.llmstudy.rag.config.RetrievalProperties;
import com.llmstudy.rag.entity.KnowledgeSegment;
import com.llmstudy.rag.enums.RagProgressStage;
import com.llmstudy.rag.module.llm.LlmTraceContext;
import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import com.llmstudy.rag.module.rag.aggregation.RrfRerankAggregator;
import com.llmstudy.rag.module.rag.model.RagRequest;
import com.llmstudy.rag.module.rag.model.RagResult;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RetrievalDiagnoseResponse;
import com.llmstudy.rag.module.rag.model.RetrievalDiagnoseResponse.Expand;
import com.llmstudy.rag.module.rag.model.RetrievalDiagnoseResponse.Hit;
import com.llmstudy.rag.module.rag.model.RetrievalDiagnoseResponse.Lane;
import com.llmstudy.rag.module.rag.model.RetrievalDiagnoseResponse.QueryPlan;
import com.llmstudy.rag.module.rag.model.RetrievalQueryPlan;
import com.llmstudy.rag.module.rag.prompt.RagPromptInjector;
import com.llmstudy.rag.module.rag.query.QueryRewriter;
import com.llmstudy.rag.module.rag.query.RetrievalQueryScope;
import com.llmstudy.rag.module.rag.retrieval.HybridRetriever;
import com.llmstudy.rag.module.rag.retrieval.ParentChunkExpander;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 在线 RAG 唯一编排入口。
 *
 * <p>固定按「查询改写 → 两路并行召回 → RRF → parent 分组 → BGE → 名次融合
 * → parent 展开补位 → Top-N → Prompt 注入」执行。诊断接口复用同一条
 * {@link #run}，只是停在证据选择、不调用回答模型。</p>
 */
@Service
public class RagPipeline {

    private static final Logger log = LoggerFactory.getLogger(RagPipeline.class);

    private final QueryRewriter queryRewriter;
    private final HybridRetriever hybridRetriever;
    private final RrfRerankAggregator aggregator;
    private final ParentChunkExpander parentChunkExpander;
    private final RagPromptInjector promptInjector;
    private final RetrievalProperties properties;

    public RagPipeline(QueryRewriter queryRewriter,
                       HybridRetriever hybridRetriever,
                       RrfRerankAggregator aggregator,
                       ParentChunkExpander parentChunkExpander,
                       RagPromptInjector promptInjector,
                       RetrievalProperties properties) {
        this.queryRewriter = queryRewriter;
        this.hybridRetriever = hybridRetriever;
        this.aggregator = aggregator;
        this.parentChunkExpander = parentChunkExpander;
        this.promptInjector = promptInjector;
        this.properties = properties;
    }

    /**
     * 无进度回调的兼容入口；Dataset 与开发接口可继续调用本方法。
     *
     * @return 空候选时 Prompt 为 null，但仍带改写结果
     */
    public RagResult execute(RagRequest request) {
        return execute(request, stage -> { });
    }

    /**
     * 带进度回调的完整 RAG。
     *
     * <p>在每个真实耗时边界<strong>开始之前</strong>调用 {@code progress.accept(...)}。
     * 调用方（如 RagChatFlow）负责把 {@link RagProgressStage} 转成对外 SSE 事件。</p>
     */
    public RagResult execute(RagRequest request, Consumer<RagProgressStage> progress) {
        Steps steps = run(request, progress);
        RagPromptInjector.Injection injection =
                promptInjector.inject(request, steps.plan, steps.selected);
        return new RagResult(injection.prompt(), steps.plan, injection.references(),
                steps.selected.stream().map(RetrievalCandidate::text).toList());
    }

    /**
     * 检索诊断：与 {@link #execute} 走同一套改写和排序，不注入 Prompt、不调回答模型。
     *
     * <p>{@code traceId} 在改写前生成，并写入 {@link LlmTraceContext} / MDC，
     * 贯穿改写、ES、BGE 日志，可与响应字段一对一关联。</p>
     *
     * @param includeText true 返回完整证据正文，false 截成 300 个 Unicode code point
     */
    public RetrievalDiagnoseResponse diagnose(RagRequest request, boolean includeText) {
        String traceId = UUID.randomUUID().toString();
        try (LlmTraceContext ignored = LlmTraceContext.openDiagnose(traceId)) {
            log.info("retrieval diagnose start traceId={}", traceId);
            Steps steps = run(request, stage -> { });
            List<String> failures = new ArrayList<>();
            steps.retrieval.lanes().stream()
                    .filter(HybridRetriever.Lane::failed)
                    .map(lane -> lane.channel() + ": " + lane.error())
                    .forEach(failures::add);
            // BGE 未使用时把稳定 reason 写入 failures，区分禁用、候选不足与推理故障。
            if (!steps.ranked.bgeUsed() && steps.ranked.bgeReason() != null
                    && !steps.ranked.bgeReason().isBlank()) {
                failures.add("bge: " + steps.ranked.bgeReason());
            }
            return new RetrievalDiagnoseResponse(
                    traceId,
                    new QueryPlan(steps.plan.originalQuestion(),
                            steps.plan.standaloneZh(), steps.plan.standaloneEn()),
                    steps.retrieval.lanes().stream()
                            .map(lane -> new Lane(lane.channel(), lane.query(), lane.skipped(),
                                    lane.error(), lane.elapsedMs(),
                                    hits(lane.hits(), includeText)))
                            .toList(),
                    hits(steps.ranked.rrf(), includeText),
                    hits(steps.ranked.grouped(), includeText),
                    hits(steps.ranked.afterBge(), includeText),
                    hits(steps.ranked.ranked(), includeText),
                    steps.expand,
                    hits(steps.selected, includeText),
                    steps.retrieval.bm25Degraded(),
                    steps.retrieval.knnDegraded(),
                    steps.ranked.bgeUsed(),
                    steps.ranked.bgeReason(),
                    steps.ranked.bgeElapsedMs(),
                    steps.ranked.bgeQuery(),
                    failures);
        }
    }

    /**
     * 改写 → 检索 → 排序 → 展开补位。在线与诊断共用，避免两条链排序不一致。
     *
     * <p>进度边界与改造前一致：改写前发问题分析，检索前发知识检索，
     * 融合重排前发整理资料。</p>
     */
    private Steps run(RagRequest request, Consumer<RagProgressStage> progress) {
        Objects.requireNonNull(progress, "progress");
        progress.accept(RagProgressStage.QUESTION_ANALYSIS);
        RetrievalQueryPlan plan = queryRewriter.rewrite(request);

        progress.accept(RagProgressStage.KNOWLEDGE_RETRIEVAL);
        HybridRetriever.RetrievalResult retrieval =
                hybridRetriever.retrieve(plan, request.accessContext());

        progress.accept(RagProgressStage.EVIDENCE_ORGANIZATION);
        RrfRerankAggregator.RankedEvidence ranked = aggregator.aggregate(plan, retrieval);
        List<Expand> expand = new ArrayList<>();
        RetrievalQueryScope scope = RetrievalQueryScope.from(plan);
        List<RetrievalCandidate> selected = backfill(
                prioritizeFocusedDocuments(ranked.ranked(), scope), expand, scope);
        return new Steps(plan, retrieval, ranked, List.copyOf(expand), selected);
    }

    /**
     * 按最终排序依次展开 parent，去重后不足 Top-N 则继续往后补。
     *
     * <p>必须先排序再展开：同一章节的多个 child 在分组阶段已经只占一个名额，
     * 这里再按 parent/standalone ID 去重，避免展开后两条变成同一章。</p>
     */
    private List<RetrievalCandidate> backfill(List<RetrievalCandidate> ranked,
                                              List<Expand> expand,
                                              RetrievalQueryScope scope) {
        int requestedTopN = scope.comprehensive()
                ? Math.max(properties.getTopN(), properties.getComprehensiveTopN())
                : properties.getTopN();
        int topN = Math.max(1, requestedTopN);
        int documentCap = Math.max(1, properties.getCrossDocumentMaxChunks());
        Map<String, KnowledgeSegment> cache = new HashMap<>();
        Map<String, Integer> chunksPerDocument = new HashMap<>();
        Set<String> emitted = new HashSet<>();
        List<RetrievalCandidate> selected = new ArrayList<>();
        List<RetrievalCandidate> deferred = new ArrayList<>();
        for (RetrievalCandidate candidate : ranked) {
            if (selected.size() >= topN) {
                break;
            }
            RetrievalCandidate output = parentChunkExpander.expandOne(candidate, cache);
            String action = expandAction(candidate, output);
            if (!emitted.add(output.id())) {
                expand.add(new Expand(candidate.id(), output.id(), "dropped-duplicate"));
                continue;
            }
            expand.add(new Expand(candidate.id(), output.id(), action));
            String documentKey = documentKey(output);
            if (scope.crossDocument()
                    && chunksPerDocument.getOrDefault(documentKey, 0) >= documentCap) {
                deferred.add(output);
                continue;
            }
            selected.add(output);
            chunksPerDocument.merge(documentKey, 1, Integer::sum);
        }
        // 文档不足时仍按原排序补足 Top-N，避免多样性约束造成证据预算浪费。
        for (RetrievalCandidate candidate : deferred) {
            if (selected.size() >= topN) {
                break;
            }
            selected.add(candidate);
        }
        return List.copyOf(selected);
    }

    private static String documentKey(RetrievalCandidate candidate) {
        Object docId = candidate.metadata().get(SegmentMetadataKeys.DOC_ID);
        if (docId != null && !docId.toString().isBlank()) {
            return "doc:" + docId;
        }
        Object versionId = candidate.metadata().get(SegmentMetadataKeys.VERSION_ID);
        if (versionId != null && !versionId.toString().isBlank()) {
            return "version:" + versionId;
        }
        return "chunk:" + candidate.id();
    }

    /** 跨论文题先为每篇显式点名文档保留前两条词面证据，再回到融合排序。 */
    private static List<RetrievalCandidate> prioritizeFocusedDocuments(
            List<RetrievalCandidate> ranked, RetrievalQueryScope scope) {
        if (!scope.crossDocument()) {
            return ranked;
        }
        List<RetrievalCandidate> prioritized = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (RetrievalCandidate candidate : ranked) {
            Object focusedRank = candidate.metadata().get(
                    SegmentMetadataKeys.FOCUSED_DOCUMENT_RANK);
            if (focusedRank != null && focusedRank(focusedRank) <= 2) {
                prioritized.add(candidate);
                ids.add(candidate.id());
            }
        }
        for (RetrievalCandidate candidate : ranked) {
            if (ids.add(candidate.id())) {
                prioritized.add(candidate);
            }
        }
        return List.copyOf(prioritized);
    }

    private static int focusedRank(Object value) {
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * 诊断用的展开动作：replaced / kept-no-parent / kept-lookup-failed。
     *
     * <p>无 parent 时 groupingKey 等于自身 id；有 parent 但输出仍是 child，说明回查失败。</p>
     */
    private static String expandAction(RetrievalCandidate input, RetrievalCandidate output) {
        if (!output.id().equals(input.id())) {
            return "replaced";
        }
        return input.groupingKey().equals(input.id()) ? "kept-no-parent" : "kept-lookup-failed";
    }

    /** 把候选列表转成诊断 Hit；{@code rank} 就是该阶段列表下标 + 1。 */
    private static List<Hit> hits(List<RetrievalCandidate> list, boolean includeText) {
        List<Hit> hits = new ArrayList<>(list.size());
        for (int index = 0; index < list.size(); index++) {
            RetrievalCandidate candidate = list.get(index);
            hits.add(new Hit(candidate.id(), candidate.groupingKey(),
                    candidate.rawScore(), candidate.rrfScore(),
                    candidate.bgeScore(), candidate.finalScore(),
                    index + 1, clip(candidate.text(), includeText)));
        }
        return hits;
    }

    /**
     * 默认只返回 300 个 Unicode code point，避免诊断响应被整章 parent 撑爆。
     *
     * <p>截断发生在映射 DTO 时，不影响检索和 rerank 使用的完整正文。</p>
     */
    private static String clip(String text, boolean includeText) {
        if (includeText || text == null) {
            return text;
        }
        int[] codePoints = text.codePoints().limit(301).toArray();
        return codePoints.length <= 300 ? text : new String(codePoints, 0, 300);
    }

    private record Steps(RetrievalQueryPlan plan,
                         HybridRetriever.RetrievalResult retrieval,
                         RrfRerankAggregator.RankedEvidence ranked,
                         List<Expand> expand,
                         List<RetrievalCandidate> selected) {
    }
}

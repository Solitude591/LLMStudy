package com.llmstudy.rag.module.rag.prompt;

import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import com.llmstudy.rag.module.rag.model.RagAnswerMode;
import com.llmstudy.rag.module.rag.model.RagReference;
import com.llmstudy.rag.module.rag.model.RagFocusInformation;
import com.llmstudy.rag.module.rag.model.RagRequest;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RetrievalQueryPlan;
import com.llmstudy.rag.module.llm.model.LlmPrompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 将聚合候选渲染为编号证据，按意图注入 Prompt，并生成结构化引用。 */
@Component
public class RagPromptInjector {

    private static final Logger log = LoggerFactory.getLogger(RagPromptInjector.class);
    private final RagPromptTemplateRegistry templateRegistry;

    public RagPromptInjector(RagPromptTemplateRegistry templateRegistry) {
        this.templateRegistry = templateRegistry;
    }

    /**
     * 根据已排序候选生成最终 Prompt 和引用列表。
     *
     * @param request    包含原问题与意图策略的 RAG 请求
     * @param plan       查询改写结果，保留参数以明确 Pipeline 阶段边界
     * @param candidates 聚合、重排并截断后的候选
     * @return 可交给 LLM 的 Prompt 与按编号对齐的引用
     */
    public Injection inject(RagRequest request, RetrievalQueryPlan plan,
                            List<RetrievalCandidate> candidates) {
        if (candidates.isEmpty()) {
            return new Injection(null, List.of());
        }
        // Prompt 中的 [n] 和 references 的 citation 使用同一个下标生成，防止展示引用错位。
        StringBuilder information = new StringBuilder();
        List<RagReference> references = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            RetrievalCandidate candidate = candidates.get(index);
            int citation = index + 1;
            Map<String, Object> metadata = candidate.metadata();
            String docId = string(metadata.get(SegmentMetadataKeys.DOC_ID));
            String chunkId = stringOr(metadata.get(SegmentMetadataKeys.CHUNK_ID), candidate.id());
            String headerPath = string(metadata.get(SegmentMetadataKeys.HEADER_PATH));
            String sourceUrl = string(metadata.get(SegmentMetadataKeys.SOURCE_URL));
            Integer pageStart = integer(metadata.get(SegmentMetadataKeys.PAGE_START));
            Integer pageEnd = integer(metadata.get(SegmentMetadataKeys.PAGE_END));
            information.append('[').append(citation).append("]\n")
                    .append("doc_id: ").append(known(docId)).append('\n')
                    .append("chunk_id: ").append(known(chunkId)).append('\n')
                    .append("章节: ").append(known(headerPath)).append('\n')
                    .append("页码: ").append(formatPages(pageStart, pageEnd)).append('\n')
                    .append("来源: ").append(known(sourceUrl)).append('\n')
                    .append("正文:\n").append(candidate.text()).append("\n\n");
            references.add(new RagReference(citation, docId, chunkId,
                    headerPath, sourceUrl, pageStart, pageEnd,
                    candidate.citationScore(), candidate.bgeScore()));
        }
        RagPromptTemplateRegistry.TemplateSelection selection =
                templateRegistry.select(request.intentContext().answerMode());
        Map<String, Object> variables = Map.of(
                "information", information.toString().trim(),
                "question", request.question(),
                "intentContext", formatIntentContext(request));
        LlmPrompt prompt = render(selection, variables);
        return new Injection(prompt, references);
    }

    /** 渲染专用模板，变量错误等运行时故障会再回退通用模板。 */
    private LlmPrompt render(RagPromptTemplateRegistry.TemplateSelection selection,
                             Map<String, Object> variables) {
        try {
            return renderPrompt(selection, variables);
        } catch (RuntimeException e) {
            // 通用模板本身失败时无更低级别的安全模板，必须暴露配置错误。
            if (selection.effectiveMode() == RagAnswerMode.GENERIC) {
                throw e;
            }
            log.warn("RAG 专用回答模板渲染失败，回退通用模板: mode={}",
                    selection.effectiveMode(), e);
            return renderPrompt(templateRegistry.select(RagAnswerMode.GENERIC), variables);
        }
    }

    /** 分别渲染稳定规则和运行时数据，保留消息角色边界。 */
    private static LlmPrompt renderPrompt(
            RagPromptTemplateRegistry.TemplateSelection selection,
            Map<String, Object> variables) {
        String systemMessage = renderTemplate(selection.systemTemplate(), Map.of());
        String userMessage = renderTemplate(selection.userTemplate(), variables);
        return new LlmPrompt(systemMessage, userMessage);
    }

    /** 使用 Spring AI StringTemplate 渲染已选模板。 */
    private static String renderTemplate(String template, Map<String, Object> variables) {
        return new PromptTemplate(template).create(variables).getContents();
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static String stringOr(Object value, String fallback) {
        String string = string(value);
        return string == null || string.isBlank() ? fallback : string;
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            int page = number.intValue();
            return page > 0 ? page : null;
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            int page = Integer.parseInt(value.toString().trim());
            return page > 0 ? page : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String formatPages(Integer pageStart, Integer pageEnd) {
        if (pageStart == null || pageEnd == null) {
            return "未知";
        }
        if (pageStart.equals(pageEnd)) {
            return "第 " + pageStart + " 页";
        }
        return "第 " + pageStart + "–" + pageEnd + " 页";
    }

    private static String known(String value) {
        return value == null || value.isBlank() ? "未知" : value;
    }

    /** 将主意图和非空检索焦点转为半结构化的 Prompt 片段。 */
    private static String formatIntentContext(RagRequest request) {
        RagFocusInformation focus = request.intentContext().focusInformation();
        StringBuilder context = new StringBuilder("主意图: ")
                .append(request.intentContext().answerMode());
        if (focus.isEmpty()) {
            return context.append("\n未抽取到额外检索焦点").toString();
        }
        context.append("\n已识别检索焦点:");
        append(context, "论文标题", focus.paperTitles());
        append(context, "作者", focus.authors());
        append(context, "研究主题", focus.researchTopics());
        append(context, "方法或模型", focus.methodsOrModels());
        append(context, "数据集", focus.datasets());
        append(context, "评价指标", focus.metrics());
        append(context, "其他约束", focus.otherConstraints());
        return context.toString();
    }

    /** 仅输出有值的焦点类别，避免大量空字段干扰模型。 */
    private static void append(StringBuilder target, String label, List<String> values) {
        if (!values.isEmpty()) {
            target.append("\n- ").append(label).append(": ")
                    .append(String.join("、", values));
        }
    }

    /** Prompt 注入阶段的文本与结构化引用输出。 */
    public record Injection(LlmPrompt prompt, List<RagReference> references) {
        public Injection {
            references = List.copyOf(references);
        }
    }
}

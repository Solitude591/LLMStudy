package com.llmstudy.rag.module.rag.prompt;

import com.llmstudy.rag.module.rag.model.RagAnswerMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/** 预加载意图专用回答模板，并在专用模板不可用时提供通用降级。 */
@Component
public class RagPromptTemplateRegistry {

    private static final Logger log = LoggerFactory.getLogger(RagPromptTemplateRegistry.class);
    private static final String GENERIC_TEMPLATE = "classpath:prompts/rag/answer.st";
    private static final Map<RagAnswerMode, String> TEMPLATE_PATHS = Map.of(
            RagAnswerMode.PAPER_RETRIEVAL,
            "classpath:prompts/rag/answer/paper-retrieval.st",
            RagAnswerMode.PAPER_SUMMARY,
            "classpath:prompts/rag/answer/paper-summary.st",
            RagAnswerMode.PAPER_CONTENT_QA,
            "classpath:prompts/rag/answer/paper-content-qa.st",
            RagAnswerMode.METHOD_OR_CONCEPT,
            "classpath:prompts/rag/answer/method-or-concept.st",
            RagAnswerMode.EXPERIMENT_OR_RESULT,
            "classpath:prompts/rag/answer/experiment-or-result.st",
            RagAnswerMode.COMPARISON_OR_CRITIQUE,
            "classpath:prompts/rag/answer/comparison-or-critique.st",
            RagAnswerMode.ACADEMIC_PAPER_ASSISTANCE,
            "classpath:prompts/rag/answer/academic-paper-assistance.st");

    private final Map<RagAnswerMode, String> templates =
            new EnumMap<>(RagAnswerMode.class);

    /**
     * 启动时将模板读入内存。通用模板是必需资源，专用模板缺失可降级。
     */
    public RagPromptTemplateRegistry(ResourceLoader resourceLoader) {
        // 先加载通用模板，确保任何专用模板故障都有可用的最后兜底。
        templates.put(RagAnswerMode.GENERIC,
                readRequired(resourceLoader.getResource(GENERIC_TEMPLATE), GENERIC_TEMPLATE));
        TEMPLATE_PATHS.forEach((mode, path) -> {
            try {
                templates.put(mode, read(resourceLoader.getResource(path)));
            } catch (Exception e) {
                log.warn("RAG 专用回答模板加载失败，将回退通用模板: mode={}, path={}",
                        mode, path, e);
            }
        });
    }

    /**
     * 选择指定回答策略的模板；null 和未加载的策略都返回通用模板。
     *
     * @param requestedMode 意图映射得到的回答策略
     * @return 实际生效策略、模板文本及降级标记
     */
    public TemplateSelection select(RagAnswerMode requestedMode) {
        RagAnswerMode normalized = requestedMode == null
                ? RagAnswerMode.GENERIC : requestedMode;
        String template = templates.get(normalized);
        if (template != null) {
            return new TemplateSelection(normalized, template, false);
        }
        log.warn("RAG 回答模板不可用，回退通用模板: requestedMode={}", normalized);
        return new TemplateSelection(RagAnswerMode.GENERIC,
                templates.get(RagAnswerMode.GENERIC), true);
    }

    /** 读取必需模板，失败时转换为启动错误。 */
    private static String readRequired(Resource resource, String path) {
        try {
            return read(resource);
        } catch (Exception e) {
            throw new IllegalStateException("读取通用 RAG 回答模板失败: " + path, e);
        }
    }

    /** 以 UTF-8 读取单个 classpath 模板。 */
    private static String read(Resource resource) throws IOException {
        if (!resource.exists()) {
            throw new IOException("模板资源不存在: " + resource.getDescription());
        }
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }

    /** 模板选择的实际策略、文本与降级标记。 */
    public record TemplateSelection(RagAnswerMode effectiveMode,
                                    String template,
                                    boolean fallback) {
    }
}

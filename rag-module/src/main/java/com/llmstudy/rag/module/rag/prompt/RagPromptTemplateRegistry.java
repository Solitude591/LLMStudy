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

/** 预加载 RAG 回答的 System/User 模板，并为专用 System 模板提供降级。 */
@Component
public class RagPromptTemplateRegistry {

    private static final Logger log = LoggerFactory.getLogger(RagPromptTemplateRegistry.class);
    private static final String GENERIC_SYSTEM_TEMPLATE =
            "classpath:prompts/rag/answer/system/generic.st";
    private static final String USER_TEMPLATE =
            "classpath:prompts/rag/answer/user.st";
    private static final Map<RagAnswerMode, String> TEMPLATE_PATHS = Map.of(
            RagAnswerMode.PAPER_RETRIEVAL,
            "classpath:prompts/rag/answer/system/paper-retrieval.st",
            RagAnswerMode.PAPER_SUMMARY,
            "classpath:prompts/rag/answer/system/paper-summary.st",
            RagAnswerMode.PAPER_CONTENT_QA,
            "classpath:prompts/rag/answer/system/paper-content-qa.st",
            RagAnswerMode.METHOD_OR_CONCEPT,
            "classpath:prompts/rag/answer/system/method-or-concept.st",
            RagAnswerMode.EXPERIMENT_OR_RESULT,
            "classpath:prompts/rag/answer/system/experiment-or-result.st",
            RagAnswerMode.COMPARISON_OR_CRITIQUE,
            "classpath:prompts/rag/answer/system/comparison-or-critique.st",
            RagAnswerMode.ACADEMIC_PAPER_ASSISTANCE,
            "classpath:prompts/rag/answer/system/academic-paper-assistance.st");

    private final Map<RagAnswerMode, String> systemTemplates =
            new EnumMap<>(RagAnswerMode.class);
    private final String userTemplate;

    /**
     * 启动时将模板读入内存。通用模板是必需资源，专用模板缺失可降级。
     */
    public RagPromptTemplateRegistry(ResourceLoader resourceLoader) {
        // 通用 System 和共享 User 模板都是完整 RAG 请求的必需资源。
        systemTemplates.put(RagAnswerMode.GENERIC, readRequired(
                resourceLoader.getResource(GENERIC_SYSTEM_TEMPLATE),
                GENERIC_SYSTEM_TEMPLATE));
        userTemplate = readRequired(
                resourceLoader.getResource(USER_TEMPLATE), USER_TEMPLATE);
        TEMPLATE_PATHS.forEach((mode, path) -> {
            try {
                systemTemplates.put(mode, read(resourceLoader.getResource(path)));
            } catch (Exception e) {
                log.warn("RAG 专用 System 模板加载失败，将回退通用模板: mode={}, path={}",
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
        String systemTemplate = systemTemplates.get(normalized);
        if (systemTemplate != null) {
            return new TemplateSelection(normalized, systemTemplate,
                    userTemplate, false);
        }
        log.warn("RAG 回答模板不可用，回退通用模板: requestedMode={}", normalized);
        return new TemplateSelection(RagAnswerMode.GENERIC,
                systemTemplates.get(RagAnswerMode.GENERIC), userTemplate, true);
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
                                    String systemTemplate,
                                    String userTemplate,
                                    boolean fallback) {
    }
}

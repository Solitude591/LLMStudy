package com.llmstudy.rag.module.rag.query;

import com.llmstudy.rag.module.llm.LlmTraceContext;
import com.llmstudy.rag.module.rag.model.RagRequest;
import com.llmstudy.rag.module.rag.model.RetrievalQueryPlan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 基于 LLM 的双语查询改写器。
 *
 * <p>一次调用同时生成中英文独立查询。解析使用项目已有 {@link JsonMapper}，
 * 不剥 Markdown、不截断、不忽略 JSON 之外的文本。</p>
 */
@Component
public class LlmQueryRewriter implements QueryRewriter {

    private final ChatClient chatClient;
    private final JsonMapper jsonMapper;
    private final String systemTemplate;
    private final String userTemplate;

    public LlmQueryRewriter(
            ChatClient chatClient,
            JsonMapper jsonMapper,
            @Value("classpath:prompts/rag/query-rewrite/system.st")
            Resource systemResource,
            @Value("classpath:prompts/rag/query-rewrite/user.st")
            Resource userResource) {
        this.chatClient = chatClient;
        this.jsonMapper = jsonMapper;
        this.systemTemplate = read(systemResource);
        this.userTemplate = read(userResource);
    }

    /**
     * 调用改写模型并严格解析 JSON。
     *
     * <p>模型输出不是本方法的信任边界：缺字段、空字段、Markdown 包裹或尾部解释
     * 一律视为改写失败，由上层返回统一安全文案。</p>
     */
    @Override
    public RetrievalQueryPlan rewrite(RagRequest request) {
        String userMessage = new PromptTemplate(userTemplate).create(Map.of(
                "query", request.question(),
                "conversationContext", request.conversationContext())).getContents();
        ChatResponse response;
        try {
            // temperature=0：改写是检索前置条件，需要稳定可复现的 JSON。
            response = chatClient.prompt()
                    .system(systemTemplate)
                    .user(userMessage)
                    .options(OpenAiChatOptions.builder().temperature(0.0))
                    .advisors(spec -> spec.params(
                            LlmTraceContext.params("query-rewrite")))
                    .call()
                    .chatResponse();
        } catch (RuntimeException e) {
            throw new QueryRewriteException(e);
        }
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                || response.getResult().getOutput().getText().isBlank()) {
            throw new QueryRewriteException("问题改写模型未返回有效内容");
        }
        Payload payload = parseStrict(response.getResult().getOutput().getText());
        if (isBlank(payload.standaloneZh()) || isBlank(payload.standaloneEn())) {
            throw new QueryRewriteException("改写结果缺少中文或英文独立查询");
        }
        try {
            // originalQuestion 只取请求原文，不信任模型回显。
            return new RetrievalQueryPlan(
                    request.question(), payload.standaloneZh(), payload.standaloneEn());
        } catch (IllegalArgumentException e) {
            // compact ctor 的 IAE 会变成 HTTP 400，必须转成改写失败。
            throw new QueryRewriteException(e);
        }
    }

    /**
     * 整段响应必须是单个 JSON 对象。
     *
     * <p>不以 <code>{</code> 开头的内容（含 Markdown 代码块）直接失败。
     * {@code readValue} 后再读一个 token，用于拒绝 JSON 后面的解释文字。</p>
     */
    private Payload parseStrict(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("{")) {
            throw new QueryRewriteException("改写结果不是 JSON 对象");
        }
        try (JsonParser parser = jsonMapper.createParser(trimmed)) {
            Payload payload = jsonMapper.readValue(parser, Payload.class);
            if (parser.nextToken() != null) {
                throw new QueryRewriteException("改写结果包含 JSON 之外的文本");
            }
            return payload;
        } catch (QueryRewriteException e) {
            throw e;
        } catch (Exception e) {
            throw new QueryRewriteException(e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 在 Bean 初始化时读取模板，资源缺失则阻止不完整应用启动。 */
    private static String read(Resource resource) {
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取问题改写提示词失败", e);
        }
    }

    /** 模型 JSON 载荷；字段名必须与 Prompt 中的 schema 一致。 */
    private record Payload(String standaloneZh, String standaloneEn) {
    }
}

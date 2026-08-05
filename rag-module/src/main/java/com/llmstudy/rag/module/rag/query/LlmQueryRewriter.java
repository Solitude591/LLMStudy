package com.llmstudy.rag.module.rag.query;

import com.llmstudy.rag.module.rag.model.RagRequest;
import com.llmstudy.rag.module.rag.model.RewrittenQuery;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** 基于 LLM 的查询改写器，提示词作为 classpath 资源独立维护。 */
@Component
public class LlmQueryRewriter implements QueryRewriter {

    private final ChatModel chatModel;
    private final String template;

    public LlmQueryRewriter(ChatModel chatModel,
                            @Value("classpath:prompts/rag/query-rewrite.st") Resource resource) {
        this.chatModel = chatModel;
        this.template = read(resource);
    }

    /** {@inheritDoc} */
    @Override
    public RewrittenQuery rewrite(RagRequest request) {
        String prompt = new PromptTemplate(template).create(Map.of(
                "query", request.question(),
                "conversationContext", request.conversationContext())).getContents();
        // 改写阶段是检索前置条件，空响应不应静默退回原问题掩盖故障。
        ChatResponse response = chatModel.call(new Prompt(prompt));
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null
                || response.getResult().getOutput().getText().isBlank()) {
            throw new IllegalStateException("问题改写模型未返回有效内容");
        }
        return new RewrittenQuery(request.question(),
                response.getResult().getOutput().getText());
    }

    /** 在 Bean 初始化时读取模板，资源缺失则阻止不完整应用启动。 */
    private static String read(Resource resource) {
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取问题改写提示词失败", e);
        }
    }
}

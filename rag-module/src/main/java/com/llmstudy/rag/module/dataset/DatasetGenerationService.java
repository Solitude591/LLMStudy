package com.llmstudy.rag.module.dataset;

import com.llmstudy.rag.auth.model.AccessContext;
import com.llmstudy.rag.dto.DatasetGenerateResponse;
import com.llmstudy.rag.module.chat.flow.RagChatFlow;
import com.llmstudy.rag.module.chat.stream.ChatStreamExecutor;
import com.llmstudy.rag.module.llm.LlmFileLoggingAdvisor;
import com.llmstudy.rag.module.rag.RagPipeline;
import com.llmstudy.rag.module.rag.model.RagIntentContext;
import com.llmstudy.rag.module.rag.model.RagRequest;
import com.llmstudy.rag.module.rag.model.RagResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * RAGAS 数据集样本生成：复用在线 RAG Pipeline，但不创建会话、不落库聊天消息。
 */
@Service
public class DatasetGenerationService {

    private final RagPipeline ragPipeline;
    private final ChatClient chatClient;

    public DatasetGenerationService(RagPipeline ragPipeline, ChatClient chatClient) {
        this.ragPipeline = ragPipeline;
        this.chatClient = chatClient;
    }

    /**
     * 对单个问题执行完整 RAG 检索并生成回答。
     *
     * @param query         用户原始问题
     * @param accessContext 入口线程捕获的访问身份，用于检索权限过滤
     * @return 原始问题、模型回答与最终 chunk 正文
     */
    public DatasetGenerateResponse generate(String query, AccessContext accessContext) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("用户问题不能为空");
        }
        String originalQuery = query.trim();
        if (accessContext == null) {
            throw new IllegalArgumentException("访问上下文不能为空");
        }

        RagResult result = ragPipeline.execute(new RagRequest(
                originalQuery,
                "无",
                RagIntentContext.generic(),
                accessContext));

        if (result.empty()) {
            return new DatasetGenerateResponse(
                    originalQuery, RagChatFlow.NO_KNOWLEDGE_ANSWER, result.chunks());
        }

        ChatClient.ChatClientRequestSpec request = chatClient.prompt();
        if (result.prompt().hasSystemMessage()) {
            request = request.system(result.prompt().systemMessage());
        }
        var response = request
                .user(result.prompt().userMessage())
                .advisors(spec -> spec.param(LlmFileLoggingAdvisor.STAGE_KEY, "dataset-generate"))
                .call()
                .chatResponse();
        String content = ChatStreamExecutor.extractContent(response, true);
        return new DatasetGenerateResponse(originalQuery, content, result.chunks());
    }
}

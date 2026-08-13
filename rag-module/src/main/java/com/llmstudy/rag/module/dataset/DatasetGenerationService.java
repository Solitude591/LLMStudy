package com.llmstudy.rag.module.dataset;

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
     * <p>公开评测接口不携带登录身份；{@code accessContext} 传 {@code null}，
     * 检索使用全部已发布版本（见 {@code HybridRetriever}）。</p>
     *
     * @param query 用户原始问题
     * @return 原始问题、模型回答与最终 chunk 正文
     */
    public DatasetGenerateResponse generate(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("用户问题不能为空");
        }
        String originalQuery = query.trim();

        RagResult result = ragPipeline.execute(new RagRequest(
                originalQuery,
                "无",
                RagIntentContext.generic(),
                null));

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

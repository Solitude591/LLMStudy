package com.llmstudy.rag.module.chat.stream;

import com.llmstudy.rag.entity.ChatMessage;
import com.llmstudy.rag.enums.MessageType;
import com.llmstudy.rag.module.chat.conversation.ConversationService;
import com.llmstudy.rag.module.chat.model.ChatPreparation;
import com.llmstudy.rag.module.chat.model.ChatStreamEvent;
import com.llmstudy.rag.module.llm.LlmFileLoggingAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.atomic.AtomicReference;

/** 统一处理模型分片、用量聚合、助手消息落库和内部流事件。 */
@Component
public class ChatStreamExecutor {

    private final ChatClient chatClient;
    private final ConversationService conversationService;
    private final JsonMapper jsonMapper;

    public ChatStreamExecutor(ChatClient chatClient,
                              ConversationService conversationService,
                              JsonMapper jsonMapper) {
        this.chatClient = chatClient;
        this.conversationService = conversationService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 执行已准备的聊天请求；固定回答直接输出，其余请求调用模型流式接口。
     *
     * @param preparation 包含 Prompt、历史和持久化上下文的准备结果
     * @return DELTA 事件与最终 DONE 事件组成的有序流
     */
    public Flux<ChatStreamEvent> execute(ChatPreparation preparation) {
        if (preparation.fixed()) {
            return fixed(preparation);
        }
        // 模型用量和模型名可能只在最后一个分片出现，因此跨分片累计。
        StringBuilder answer = new StringBuilder();
        AtomicReference<Integer> tokenCount = new AtomicReference<>();
        AtomicReference<String> modelName = new AtomicReference<>();
        ChatClient.ChatClientRequestSpec request = chatClient.prompt();
        if (preparation.prompt().hasSystemMessage()) {
            request = request.system(preparation.prompt().systemMessage());
        }
        Flux<ChatStreamEvent> deltas = request
                .messages(preparation.history())
                .user(preparation.prompt().userMessage())
                .advisors(spec -> spec
                        .param(LlmFileLoggingAdvisor.STAGE_KEY, "final-answer")
                        .param(LlmFileLoggingAdvisor.CONVERSATION_ID_KEY,
                                preparation.conversationId())
                        .param(LlmFileLoggingAdvisor.MESSAGE_ID_KEY,
                                preparation.userMessageId()))
                .stream()
                .chatResponse()
                .map(response -> {
                    String content = extractContent(response, false);
                    answer.append(content);
                    ResponseInfo info = responseInfo(response);
                    if (info.tokenCount() != null && info.tokenCount() > 0) {
                        tokenCount.set(info.tokenCount());
                    }
                    if (info.modelName() != null && !info.modelName().isBlank()) {
                        modelName.set(info.modelName());
                    }
                    return ChatStreamEvent.delta(preparation, content);
                });
        // 数据库落库是阻塞操作，放到 boundedElastic，且只在分片流正常完成后执行。
        Mono<ChatStreamEvent> done = Mono.fromSupplier(() -> persist(
                preparation, answer.toString(), tokenCount.get(), modelName.get()))
                .subscribeOn(Schedulers.boundedElastic());
        return deltas.concatWith(done);
    }

    /** 输出空检索等场景的可控固定回答，仍保持 DELTA/DONE 协议。 */
    private Flux<ChatStreamEvent> fixed(ChatPreparation preparation) {
        return Flux.just(ChatStreamEvent.delta(preparation, preparation.fixedAnswer()))
                .concatWith(Mono.fromSupplier(() -> persist(preparation,
                                preparation.fixedAnswer(), null, null))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /** 将完整助手回答及 RAG 引用一次性落库，随后生成 DONE 事件。 */
    private ChatStreamEvent persist(ChatPreparation preparation, String content,
                                    Integer tokenCount, String modelName) {
        String references = null;
        if (!preparation.ragReferences().isEmpty()) {
            try {
                references = jsonMapper.writeValueAsString(preparation.ragReferences());
            } catch (Exception e) {
                throw new IllegalStateException("序列化 RAG 引用失败", e);
            }
        }
        ChatMessage message = conversationService.saveMessage(
                preparation.conversationId(), MessageType.ASSISTANT,
                content, tokenCount, modelName, references, null);
        return ChatStreamEvent.done(preparation, message.getMessageId(),
                tokenCount, modelName);
    }

    /** 安全提取响应文本；流式空分片可忽略，非流式空响应则报错。 */
    public static String extractContent(
            org.springframework.ai.chat.model.ChatResponse response,
            boolean required) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            if (required) {
                throw new IllegalStateException("模型响应缺少输出内容");
            }
            return "";
        }
        String content = response.getResult().getOutput().getText();
        if (required && content.isBlank()) {
            throw new IllegalStateException("模型未返回有效内容");
        }
        return content;
    }

    /** 提取模型名和 Token 总数，兼容部分流式分片不带 metadata 的情况。 */
    public static ResponseInfo responseInfo(
            org.springframework.ai.chat.model.ChatResponse response) {
        if (response == null) {
            return new ResponseInfo(null, null);
        }
        ChatResponseMetadata metadata = response.getMetadata();
        if (metadata == null) {
            return new ResponseInfo(null, null);
        }
        Usage usage = metadata.getUsage();
        return new ResponseInfo(usage == null ? null : usage.getTotalTokens(),
                metadata.getModel());
    }

    /** 从模型响应 metadata 中聚合的用量信息。 */
    public record ResponseInfo(Integer tokenCount, String modelName) {
    }
}

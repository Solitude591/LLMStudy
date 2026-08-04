package com.llmstudy.rag.controller;

import com.llmstudy.rag.config.ChatProperties;
import com.llmstudy.rag.dto.ChatConversationResponse;
import com.llmstudy.rag.dto.ChatRequest;
import com.llmstudy.rag.dto.ChatResponse;
import com.llmstudy.rag.dto.ChatStreamResponse;
import com.llmstudy.rag.entity.ChatConversation;
import com.llmstudy.rag.entity.ChatMessage;
import com.llmstudy.rag.enums.MessageType;
import com.llmstudy.rag.service.ChatService;
import com.llmstudy.rag.service.TitleSummaryService;
import com.llmstudy.rag.module.KnowEngineQueryTransformer;
import dev.langchain4j.rag.query.Query;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/chat/client")
public class ChatClientController {

    /** 负责组装 Prompt 并调用底层聊天模型。 */
    private final ChatClient chatClient;

    /** 提供给 QueryTransformer 直接调用的 Spring AI 底层模型。 */
    private final ChatModel chatModel;

    /** 负责会话、消息查询与 MySQL 持久化。 */
    private final ChatService chatService;

    /** 负责在虚拟线程中生成并回写会话标题。 */
    private final TitleSummaryService titleSummaryService;

    /** 统一提供历史窗口、临时标题长度和默认用户等聊天业务参数。 */
    private final ChatProperties chatProperties;

    /**
     * 创建聊天接口控制器，统一注入模型调用、消息持久化和会话配置依赖。
     *
     * @param chatClient          负责常规聊天的 Spring AI ChatClient
     * @param chatModel           负责问题改写的 Spring AI 底层模型
     * @param chatService         会话和消息持久化服务
     * @param titleSummaryService 会话标题生成服务
     * @param chatProperties      聊天业务配置
     */
    public ChatClientController(ChatClient chatClient,
                                ChatModel chatModel,
                                ChatService chatService,
                                TitleSummaryService titleSummaryService,
                                ChatProperties chatProperties) {
        this.chatClient = chatClient;
        this.chatModel = chatModel;
        this.chatService = chatService;
        this.titleSummaryService = titleSummaryService;
        this.chatProperties = chatProperties;
    }

    /**
     * 执行一次非流式聊天。
     *
     * <p>处理顺序：创建/恢复会话、加载历史、保存用户消息、
     * 调用模型、保存助手消息，最后向前端返回所有业务 ID 和模型用量。</p>
     *
     * @param request 聊天请求
     * @return 完整的聊天响应
     */
    @PostMapping("/ask")
    public ChatResponse ask(@RequestBody ChatRequest request) {
        // 先统一完成空值校验、字符串去空格和临时用户 ID 补齐。
        ValidatedChatRequest validated = validate(request);

        // conversationId 为空时创建 UUID 会话，有值时恢复已有会话。
        ChatConversation conversation = resolveConversation(validated);

        // 仅加载本次用户消息之前的历史，防止当前问题被重复放入 Prompt。
        List<Message> history = loadHistory(conversation.getConversationId());

        // 模型调用前先落库 USER 消息；即使模型失败，也能保留用户的原始问题。
        ChatMessage userMessage = chatService.saveMessage(
                conversation.getConversationId(),
                MessageType.USER,
                validated.query(),
                null);

        // 首次提问时异步生成精准标题；生成失败会保留临时标题。
        triggerTitleGenerationIfFirstMessage(
                history.isEmpty(), validated.query(), conversation);

        // 使用 chatResponse() 而不是 content()，以便同时获得正文、Token 和模型名称。
        org.springframework.ai.chat.model.ChatResponse aiResponse =
                chatClient.prompt()
                        .messages(history)
                        .user(validated.query())
                        .call()
                        .chatResponse();

        // 将供应商响应解析为业务字段，并对空回复做明确报错。
        String answer = extractContent(aiResponse);
        ModelResponseInfo responseInfo = extractResponseInfo(aiResponse);

        // 只把模型用量写入 ASSISTANT 消息，USER 消息不具备这些响应元数据。
        ChatMessage assistantMessage = chatService.saveMessage(
                conversation.getConversationId(),
                MessageType.ASSISTANT,
                answer,
                responseInfo.tokenCount(),
                responseInfo.modelName());

        // 返回会话 ID 和两条消息 ID，前端可用它们继续会话或定位消息。
        return new ChatResponse(
                conversation.getConversationId(),
                conversation.getTitle(),
                userMessage.getMessageId(),
                assistantMessage.getMessageId(),
                answer,
                responseInfo.tokenCount(),
                responseInfo.modelName());
    }

    /**
     * 执行一次流式聊天，通过 SSE 依次返回 START、DELTA 和 DONE 事件。
     *
     * @param request 聊天请求
     * @return 流式事件序列
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatStreamResponse> stream(@RequestBody ChatRequest request) {
        // 流式与非流式使用相同的请求校验、会话恢复和历史加载规则。
        ValidatedChatRequest validated = validate(request);
        ChatConversation conversation = resolveConversation(validated);
        List<Message> history = loadHistory(conversation.getConversationId());

        // 先保存 USER 消息，START 事件中会立即把该消息 ID 返回前端。
        ChatMessage userMessage = chatService.saveMessage(
                conversation.getConversationId(),
                MessageType.USER,
                validated.query(),
                null);

        // 提前保存不变的业务 ID，便于在后续的 Reactor 回调中安全引用。
        String conversationId = conversation.getConversationId();
        String userMessageId = userMessage.getMessageId();

        // 首次提问时异步生成精准标题；生成失败会保留临时标题。
        triggerTitleGenerationIfFirstMessage(
                history.isEmpty(), validated.query(), conversation);

        // 每个 DELTA 只包含增量文本，这里同时聚合完整回答，供流结束后一次性落库。
        StringBuilder answer = new StringBuilder();

        // Token 和模型信息可能只在最后一个 ChatResponse 中出现，因此跨分片保留最新有效值。
        AtomicReference<Integer> tokenCount = new AtomicReference<>();
        AtomicReference<String> modelName = new AtomicReference<>();

        // 读取带元数据的 ChatResponse 流，不直接读取纯文本 content 流。
        Flux<ChatStreamResponse> deltas = chatClient.prompt()
                .messages(history)
                .user(validated.query())
                .stream()
                .chatResponse()
                .map(aiResponse -> {
                    // 部分供应商会发送只含元数据的空文本分片，流式场景允许它返回空字符串。
                    String content = extractContent(aiResponse, false);
                    answer.append(content);

                    // 如果当前分片携带用量信息，就更新最终将写入数据库的值。
                    ModelResponseInfo info = extractResponseInfo(aiResponse);
                    if (info.tokenCount() != null && info.tokenCount() > 0) {
                        tokenCount.set(info.tokenCount());
                    }
                    if (info.modelName() != null && !info.modelName().isBlank()) {
                        modelName.set(info.modelName());
                    }
                    // 将当前文本分片包装为结构化 DELTA 事件。
                    return ChatStreamResponse.delta(
                            conversationId, userMessageId, content);
                });

        // 只有上游模型流正常完成后才会执行：保存完整助手回复并构造 DONE 事件。
        Mono<ChatStreamResponse> done = Mono.fromSupplier(() -> {
            ChatMessage assistantMessage = chatService.saveMessage(
                    conversationId,
                    MessageType.ASSISTANT,
                    answer.toString(),
                    tokenCount.get(),
                    modelName.get());
            return ChatStreamResponse.done(
                    conversationId,
                    userMessageId,
                    assistantMessage.getMessageId(),
                    tokenCount.get(),
                    modelName.get());
        });

        // START 先返回 ID，中间连续返回文本，落库成功后最后返回 DONE。
        return Flux.just(ChatStreamResponse.start(
                        conversationId, conversation.getTitle(), userMessageId))
                .concatWith(deltas)
                .concatWith(done);
    }

    /**
     * 查询单个会话的当前摘要，供前端获取异步生成后写入数据库的新标题。
     *
     * @param conversationId 会话业务 ID
     * @return 当前会话标题、状态和更新时间
     */
    @GetMapping("/conversations/{conversationId}")
    public ChatConversationResponse getConversation(
            @PathVariable String conversationId) {
        // Service 已负责参数校验；这里额外处理不存在记录，避免转换 null 实体。
        ChatConversation conversation = chatService.getConversation(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在: " + conversationId);
        }
        return ChatConversationResponse.from(conversation);
    }

    /**
     * 调用论文知识库 QueryTransformer 测试问题改写效果。
     *
     * <p>该接口只用于观察模板的改写结果，不创建会话、不保存消息，
     * 也不执行后续的向量检索。</p>
     *
     * @param query 需要测试改写的原始问题
     * @return 改写后问题和原始问题
     */
    @GetMapping("/test-transfomrer")
    public Map<String, String> testTransformer(@RequestParam String query) {
        // 测试接口不复用聊天请求 DTO，因此在这里直接校验查询参数。
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("原始问题不能为空");
        }
        String originalQuery = query.trim();

        // chatService 和 sourceMessageId 都传 null，确保本次测试不会将改写结果回写数据库。
        Query transformedQuery = new KnowEngineQueryTransformer(
                chatModel, null, null)
                .transform(Query.from(originalQuery))
                .stream()
                .toList()
                .getFirst();

        // 改写后 Query 只返回一条；原问题保留在返回 Query 的 metadata 中，这里直接用请求参数。
        return Map.of(
                "transformedQuery", transformedQuery.text(),
                "originalQuery", originalQuery);
    }

    /**
     * 当前会话没有历史消息时触发异步标题生成。
     *
     * <p>不能只根据请求中是否传入 conversationId 判断，因为兼容接口允许前端
     * 预先生成会话 ID；以持久化历史是否为空判断，才能覆盖两种创建方式。</p>
     */
    private void triggerTitleGenerationIfFirstMessage(boolean firstMessage,
                                                      String firstQuery,
                                                      ChatConversation conversation) {
        if (firstMessage) {
            titleSummaryService.generateTitleAsync(
                    conversation.getConversationId(), firstQuery);
        }
    }

    /**
     * 根据请求解析会话：未传会话 ID 时创建 UUID 会话，否则恢复或创建指定会话。
     */
    private ChatConversation resolveConversation(ValidatedChatRequest request) {
        if (request.conversationId() == null) {
            return chatService.createConversation(
                    request.userId(), buildTitle(request.query()));
        }
        return chatService.getOrCreateConversation(
                request.conversationId(),
                request.userId(),
                buildTitle(request.query()));
    }

    /**
     * 从 MySQL 加载最近的业务消息，并转换为 Spring AI 可接受的历史消息。
     */
    private List<Message> loadHistory(String conversationId) {
        return chatService.listRecentMessages(
                        conversationId, chatProperties.getHistoryLimit()).stream()
                .map(ChatClientController::toAiMessage)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 把数据库消息转换为模型消息；第一版仅将 USER 和 ASSISTANT 放入上下文。
     */
    private static Message toAiMessage(ChatMessage message) {
        return switch (message.getMessageType()) {
            case USER -> new UserMessage(message.getContent());
            case ASSISTANT -> new AssistantMessage(message.getContent());
            default -> null;
        };
    }

    /**
     * 从非流式响应提取正文，缺少内容时直接报错，避免保存空的助手消息。
     */
    private static String extractContent(
            org.springframework.ai.chat.model.ChatResponse response) {
        String content = extractContent(response, true);
        if (content.isBlank()) {
            throw new IllegalStateException("模型未返回有效内容");
        }
        return content;
    }

    /**
     * 从模型响应提取文本，required 决定缺少文本时报错还是返回空字符串。
     */
    private static String extractContent(
            org.springframework.ai.chat.model.ChatResponse response,
            boolean required) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            if (required) {
                throw new IllegalStateException("模型响应缺少输出内容");
            }
            return "";
        }
        return response.getResult().getOutput().getText();
    }

    /**
     * 从 ChatResponseMetadata 中提取 Token 总数和实际模型名称。
     */
    private static ModelResponseInfo extractResponseInfo(
            org.springframework.ai.chat.model.ChatResponse response) {
        if (response == null) {
            return new ModelResponseInfo(null, null);
        }
        ChatResponseMetadata metadata = response.getMetadata();
        if (metadata == null) {
            return new ModelResponseInfo(null, null);
        }
        Usage usage = metadata.getUsage();
        Integer totalTokens = usage == null ? null : usage.getTotalTokens();
        return new ModelResponseInfo(totalTokens, metadata.getModel());
    }

    /**
     * 校验并标准化外部请求，使后续流程只处理已校验的值。
     */
    private ValidatedChatRequest validate(ChatRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        if (request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("用户问题不能为空");
        }
        String conversationId = normalizeNullable(request.conversationId());
        String userId = normalizeNullable(request.userId());
        return new ValidatedChatRequest(
                conversationId,
                userId == null ? chatProperties.getDefaultUserId() : userId,
                request.query().trim());
    }

    /**
     * 把 null、空字符串和纯空格统一转为 null，非空值去除首尾空格。
     */
    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 使用首次问题生成简短会话标题，合并连续空白并限制展示长度。
     */
    private String buildTitle(String query) {
        String title = query.replaceAll("\\s+", " ").trim();
        int maxLength = chatProperties.getInitialTitleMaxLength();
        return title.length() <= maxLength
                ? title : title.substring(0, maxLength);
    }

    /** Controller 内部使用的已校验请求，不作为 HTTP DTO 暴露。 */
    private record ValidatedChatRequest(
            String conversationId,
            String userId,
            String query) {
    }

    /** 将模型响应元数据压缩为当前业务真正需要的两个字段。 */
    private record ModelResponseInfo(
            Integer tokenCount,
            String modelName) {
    }
}

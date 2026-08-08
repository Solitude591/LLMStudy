package com.llmstudy.rag.module.chat;

import com.llmstudy.rag.config.ChatProperties;
import com.llmstudy.rag.entity.ChatConversation;
import com.llmstudy.rag.entity.ChatMessage;
import com.llmstudy.rag.enums.MessageType;
import com.llmstudy.rag.module.chat.conversation.ConversationService;
import com.llmstudy.rag.module.chat.flow.ChatFlow;
import com.llmstudy.rag.module.chat.flow.ChatFlowContext;
import com.llmstudy.rag.module.chat.flow.CommonChatFlow;
import com.llmstudy.rag.module.chat.flow.RagChatFlow;
import com.llmstudy.rag.module.chat.intent.IntentRecognizer;
import com.llmstudy.rag.module.chat.model.ChatCommand;
import com.llmstudy.rag.module.chat.model.ChatPreparation;
import com.llmstudy.rag.module.chat.model.ChatStreamEvent;
import com.llmstudy.rag.module.chat.model.IntentRecognitionResult;
import com.llmstudy.rag.module.chat.stream.ChatStreamExecutor;
import com.llmstudy.rag.module.chat.title.TitleSummaryService;
import com.llmstudy.rag.module.llm.LlmFileLoggingAdvisor;
import com.llmstudy.rag.module.llm.LlmTraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 聊天应用编排器，统一处理会话加载、意图路由、消息落库与模型调用。
 *
 * <p>Controller 只需将 HTTP 请求转换为 {@link ChatCommand}，不直接感知
 * Common/RAG Flow、意图 metadata 或 Reactor 流的组装细节。</p>
 */
@Service
public class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);
    private final ConversationService conversationService;
    private final IntentRecognizer intentRecognizer;
    private final CommonChatFlow commonChatFlow;
    private final RagChatFlow ragChatFlow;
    private final ChatStreamExecutor streamExecutor;
    private final TitleSummaryService titleSummaryService;
    private final ChatProperties properties;
    private final ChatClient chatClient;
    private final JsonMapper jsonMapper;

    public ChatOrchestrator(ConversationService conversationService,
                            IntentRecognizer intentRecognizer,
                            CommonChatFlow commonChatFlow,
                            RagChatFlow ragChatFlow,
                            ChatStreamExecutor streamExecutor,
                            TitleSummaryService titleSummaryService,
                            ChatProperties properties,
                            ChatClient chatClient,
                            JsonMapper jsonMapper) {
        this.conversationService = conversationService;
        this.intentRecognizer = intentRecognizer;
        this.commonChatFlow = commonChatFlow;
        this.ragChatFlow = ragChatFlow;
        this.streamExecutor = streamExecutor;
        this.titleSummaryService = titleSummaryService;
        this.properties = properties;
        this.chatClient = chatClient;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 初始化会话并生成可执行的聊天准备信息。
     *
     * @param command     内部聊天命令
     * @param routeIntent 是否执行意图识别并在 Common/RAG 之间路由
     * @return 模型调用、引用落库和流式输出所需的上下文
     */
    public ChatPreparation prepare(ChatCommand command, boolean routeIntent) {
        return route(initialize(command), routeIntent);
    }

    /** 解决会话归属、加载最近历史，并在路由前先持久化用户消息。 */
    private ChatBase initialize(ChatCommand command) {
        ChatConversation conversation = command.conversationId() == null
                ? conversationService.createConversation(command.userId(), buildTitle(command.query()))
                : conversationService.getOrCreateConversation(command.conversationId(),
                        command.userId(), buildTitle(command.query()));
        List<Message> history = conversationService.listRecentMessages(
                        conversation.getConversationId(), properties.getHistoryLimit()).stream()
                .map(ChatOrchestrator::toAiMessage)
                .filter(Objects::nonNull)
                .toList();
        // 意图识别和检索可能耗时或失败，用户原问题必须先于它们落库。
        ChatMessage userMessage = conversationService.saveMessage(
                conversation.getConversationId(), MessageType.USER,
                command.query(), null);
        if (history.isEmpty()) {
            titleSummaryService.generateTitleAsync(
                    conversation.getConversationId(), command.query());
        }

        return new ChatBase(command, conversation, userMessage, history);
    }

    /** 根据意图选择聊天 Flow，并回写识别 metadata 与查询改写结果。 */
    private ChatPreparation route(ChatBase base, boolean routeIntent) {
        try (LlmTraceContext ignored = LlmTraceContext.open(
                base.conversation().getConversationId(),
                base.userMessage().getMessageId())) {
            return routeWithinTrace(base, routeIntent);
        }
    }

    /** 在已建立日志关联上下文的条件下执行意图识别和 RAG 路由。 */
    private ChatPreparation routeWithinTrace(ChatBase base, boolean routeIntent) {
        ChatFlow flow = commonChatFlow;
        IntentRecognitionResult intent = null;
        if (routeIntent) {
            intent = intentRecognizer.recognize(
                    base.command().query(), base.history());
            persistIntentMetadata(base.userMessage().getMessageId(), intent);
            // 识别服务失败时会返回 related=true/UNKNOWN，保守进入 RAG。
            if (intent.related()) {
                flow = ragChatFlow;
            }
        }
        // 身份快照与问题、历史一起进入 Flow，后续异步步骤不再读取 Sa-Token ThreadLocal。
        ChatFlow.FlowPreparation flowPreparation = flow.prepare(new ChatFlowContext(
                base.command().query(), base.history(), intent,
                base.command().accessContext()));
        if (flowPreparation.rewrittenQuery() != null) {
            conversationService.updateMessageTransformContent(
                    base.userMessage().getMessageId(), flowPreparation.rewrittenQuery());
        }
        return new ChatPreparation(base.conversation().getConversationId(),
                base.conversation().getTitle(), base.userMessage().getMessageId(), base.history(),
                flowPreparation.prompt(), flowPreparation.references(),
                flowPreparation.fixedAnswer());
    }

    /**
     * 执行意图路由后的流式聊天，保证内部事件顺序为 START、DELTA、DONE。
     *
     * @param command 聊天命令
     * @return 可由 Controller 映射为 SSE 响应的事件流
     */
    public Flux<ChatStreamEvent> stream(ChatCommand command) {
        ChatBase base = initialize(command);
        ChatPreparation start = new ChatPreparation(
                base.conversation().getConversationId(), base.conversation().getTitle(),
                base.userMessage().getMessageId(), base.history(), null, List.of(), null);
        // 意图识别、查询改写和检索包含阻塞调用，统一切到弹性线程池。
        Flux<ChatStreamEvent> routed = Mono.fromCallable(() -> route(base, true))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(streamExecutor::execute);
        return Flux.just(ChatStreamEvent.start(start)).concatWith(routed);
    }

    /**
     * 执行非流式普通聊天。为保持既有接口语义，该入口不进行意图路由。
     *
     * @param command 聊天命令
     * @return 包含消息 ID 和模型用量的完整回答
     */
    public ChatAnswer ask(ChatCommand command) {
        ChatPreparation preparation = prepare(command, false);
        ChatClient.ChatClientRequestSpec request = chatClient.prompt();
        if (preparation.prompt().hasSystemMessage()) {
            request = request.system(preparation.prompt().systemMessage());
        }
        org.springframework.ai.chat.model.ChatResponse response = request
                .messages(preparation.history())
                .user(preparation.prompt().userMessage())
                .advisors(spec -> spec
                        .param(LlmFileLoggingAdvisor.STAGE_KEY, "final-answer")
                        .param(LlmFileLoggingAdvisor.CONVERSATION_ID_KEY,
                                preparation.conversationId())
                        .param(LlmFileLoggingAdvisor.MESSAGE_ID_KEY,
                                preparation.userMessageId()))
                .call().chatResponse();
        String content = ChatStreamExecutor.extractContent(response, true);
        ChatStreamExecutor.ResponseInfo info = ChatStreamExecutor.responseInfo(response);
        ChatMessage assistant = conversationService.saveMessage(
                preparation.conversationId(), MessageType.ASSISTANT, content,
                info.tokenCount(), info.modelName());
        return new ChatAnswer(preparation.conversationId(),
                preparation.conversationTitle(), preparation.userMessageId(),
                assistant.getMessageId(), content, info.tokenCount(), info.modelName());
    }

    /** 将路由决策记录到用户消息 metadata，失败不中断聊天主流程。 */
    private void persistIntentMetadata(String messageId, IntentRecognitionResult intent) {
        try {
            // 将完整路由决策作为审计信息保存，不改变现有数据库字段。
            conversationService.updateMessageMetadata(messageId,
                    jsonMapper.writeValueAsString(Map.of("intentRouting", intent)));
        } catch (Exception e) {
            log.error("保存意图识别 metadata 失败: messageId={}", messageId, e);
        }
    }

    /** 由首个问题生成有长度上限的临时会话标题。 */
    private String buildTitle(String query) {
        String title = query.replaceAll("\\s+", " ").trim();
        int limit = properties.getInitialTitleMaxLength();
        return title.length() <= limit ? title : title.substring(0, limit);
    }

    /** 将持久化消息转换为 Spring AI 历史消息，其他内部类型不进入模型上下文。 */
    private static Message toAiMessage(ChatMessage message) {
        return switch (message.getMessageType()) {
            case USER -> new UserMessage(message.getContent());
            case ASSISTANT -> new AssistantMessage(message.getContent());
            default -> null;
        };
    }

    /** 非流式聊天的内部完整结果。 */
    public record ChatAnswer(String conversationId, String conversationTitle,
                             String userMessageId, String assistantMessageId,
                             String content, Integer tokenCount, String modelName) {
    }

    private record ChatBase(ChatCommand command, ChatConversation conversation,
                            ChatMessage userMessage, List<Message> history) {
    }
}

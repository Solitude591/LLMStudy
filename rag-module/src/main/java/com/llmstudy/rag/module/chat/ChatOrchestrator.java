package com.llmstudy.rag.module.chat;

import com.llmstudy.rag.config.ChatProperties;
import com.llmstudy.rag.entity.ChatConversation;
import com.llmstudy.rag.entity.ChatMessage;
import com.llmstudy.rag.enums.ChatProgressStage;
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
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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

    /**
     * 无进度回调的路由入口；同步 ask / prepare 等场景使用。
     * 内部传入空操作 Consumer，不向任何下游推送进度。
     */
    private ChatPreparation route(ChatBase base, boolean routeIntent) {
        return route(base, routeIntent, stage -> { });
    }

    /**
     * 根据意图选择聊天 Flow，并回写识别 metadata 与查询改写结果。
     *
     * @param progress 阶段回调；流式入口会把它接到 Reactor sink，用于发 PROGRESS 事件
     */
    private ChatPreparation route(ChatBase base, boolean routeIntent,
                                  Consumer<ChatProgressStage> progress) {
        try (LlmTraceContext ignored = LlmTraceContext.open(
                base.conversation().getConversationId(),
                base.userMessage().getMessageId())) {
            return routeWithinTrace(base, routeIntent, progress);
        }
    }

    /**
     * 在已建立日志关联上下文的条件下执行意图识别和 RAG 路由。
     *
     * <p>进度约定：在真实耗时步骤<strong>开始前</strong>调用 {@code progress.accept}。
     * CommonChatFlow 会忽略回调中的 RAG 阶段；RagChatFlow 会在 Pipeline 边界继续上报。</p>
     */
    private ChatPreparation routeWithinTrace(ChatBase base, boolean routeIntent,
                                             Consumer<ChatProgressStage> progress) {
        ChatFlow flow = commonChatFlow;
        IntentRecognitionResult intent = null;
        if (routeIntent) {
            // 意图识别可能调用 LLM，先发进度再阻塞等待结果
            progress.accept(ChatProgressStage.INTENT_RECOGNITION);
            intent = intentRecognizer.recognize(
                    base.command().query(), base.history());
            persistIntentMetadata(base.userMessage().getMessageId(), intent);
            // 识别服务失败时会返回 related=true/UNKNOWN，保守进入 RAG。
            if (intent.related()) {
                flow = ragChatFlow;
            }
        }
        // 必须把同一个 progress 继续传给 Flow，否则 RAG 中间阶段无法冒泡到 SSE。
        // 身份快照与问题、历史一起进入 Flow，后续异步步骤不再读取 Sa-Token ThreadLocal。
        ChatFlow.FlowPreparation flowPreparation = flow.prepare(new ChatFlowContext(
                base.command().query(), base.history(), intent,
                base.command().accessContext()), progress);
        if (flowPreparation.rewrittenQuery() != null) {
            conversationService.updateMessageTransformContent(
                    base.userMessage().getMessageId(), flowPreparation.rewrittenQuery());
        }
        // Flow 已产出 Prompt / 固定回答，下一步进入最终模型或固定答案输出
        progress.accept(ChatProgressStage.ANSWER_GENERATION);
        return new ChatPreparation(base.conversation().getConversationId(),
                base.conversation().getTitle(), base.userMessage().getMessageId(), base.history(),
                flowPreparation.prompt(), flowPreparation.references(),
                flowPreparation.fixedAnswer());
    }

    /**
     * 执行意图路由后的流式聊天。
     *
     * <p>事件顺序：START →（零或多条 PROGRESS）→ DELTA… → DONE。
     * 准备阶段用短生命周期的 {@link Flux#create} 仅推送少量 PROGRESS；
     * 模型流通过 {@code concatWith + defer} 接入，保留下游背压，避免嵌套
     * {@code subscribe} 向模型请求 {@code Long.MAX_VALUE}。</p>
     *
     * @param command 聊天命令
     * @return 可由 Controller 映射为 SSE 响应的事件流
     */
    public Flux<ChatStreamEvent> stream(ChatCommand command) {
        // 同步完成会话解析与用户消息落库，便于 START 立刻带上 conversationId / userMessageId
        ChatBase base = initialize(command);
        ChatPreparation start = new ChatPreparation(
                base.conversation().getConversationId(), base.conversation().getTitle(),
                base.userMessage().getMessageId(), base.history(), null, List.of(), null);
        // 准备阶段结束后供 concat 第二段读取；在 complete 之前写入，与订阅线程 happens-before。
        AtomicReference<ChatPreparation> prepared = new AtomicReference<>();

        // 仅承载准备期 PROGRESS（最多 5 条），有界缓冲足够；不在此处订阅模型流。
        Flux<ChatStreamEvent> progressFlux = Flux.<ChatStreamEvent>create(sink -> {
            Disposable prepareTask = Schedulers.boundedElastic().schedule(() -> {
                try {
                    // Consumer：下层 accept(阶段) → 这里写成 PROGRESS 事件
                    Consumer<ChatProgressStage> progress = stage -> {
                        if (!sink.isCancelled()) {
                            sink.next(ChatStreamEvent.progress(start, stage));
                        }
                    };
                    ChatPreparation preparation = route(base, true, progress);
                    if (sink.isCancelled()) {
                        return;
                    }
                    prepared.set(preparation);
                    sink.complete();
                } catch (Throwable error) {
                    if (!sink.isCancelled()) {
                        sink.error(error);
                    }
                }
            });
            sink.onCancel(prepareTask::dispose);
        }, FluxSink.OverflowStrategy.BUFFER);

        // START → 进度 → 模型 DELTA/DONE；后两段串行，模型段走操作符链传 demand
        return Flux.concat(
                Flux.just(ChatStreamEvent.start(start)),
                progressFlux.concatWith(Flux.defer(() -> {
                    ChatPreparation preparation = prepared.get();
                    if (preparation == null) {
                        return Flux.error(new IllegalStateException(
                                "流式准备完成但缺少 ChatPreparation"));
                    }
                    return streamExecutor.execute(preparation);
                })));
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

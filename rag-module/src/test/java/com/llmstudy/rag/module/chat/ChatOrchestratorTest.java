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
import com.llmstudy.rag.module.chat.model.ChatIntent;
import com.llmstudy.rag.module.chat.model.ChatPreparation;
import com.llmstudy.rag.module.chat.model.ChatStreamEvent;
import com.llmstudy.rag.module.chat.model.IntentKeyInformation;
import com.llmstudy.rag.module.chat.model.IntentRecognitionResult;
import com.llmstudy.rag.module.chat.stream.ChatStreamExecutor;
import com.llmstudy.rag.module.chat.title.TitleSummaryService;
import com.llmstudy.rag.module.rag.model.RagReference;
import com.llmstudy.rag.module.llm.model.LlmPrompt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatOrchestratorTest {

    private ConversationService conversations;
    private IntentRecognizer recognizer;
    private CommonChatFlow commonFlow;
    private RagChatFlow ragFlow;
    private ChatStreamExecutor executor;
    private ChatOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationService.class);
        recognizer = mock(IntentRecognizer.class);
        commonFlow = mock(CommonChatFlow.class);
        ragFlow = mock(RagChatFlow.class);
        executor = mock(ChatStreamExecutor.class);
        ChatConversation conversation = new ChatConversation();
        conversation.setConversationId("conversation-1");
        conversation.setTitle("title");
        ChatMessage user = new ChatMessage();
        user.setMessageId("user-1");
        when(conversations.createConversation(eq("user"), anyString()))
                .thenReturn(conversation);
        when(conversations.listRecentMessages("conversation-1", 20)).thenReturn(List.of());
        when(conversations.saveMessage(eq("conversation-1"), any(),
                eq("question"), eq(null))).thenReturn(user);
        orchestrator = new ChatOrchestrator(conversations, recognizer,
                commonFlow, ragFlow, executor, mock(TitleSummaryService.class),
                new ChatProperties(), mock(ChatClient.class), JsonMapper.builder().build());
    }

    @Test
    void unrelatedIntentSelectsCommonFlow() {
        when(recognizer.recognize("question", List.of())).thenReturn(
                new IntentRecognitionResult(false,
                        com.llmstudy.rag.module.chat.model.ChatIntent.GENERAL_CHAT,
                        "general", null, false));
        when(commonFlow.prepare(any(ChatFlowContext.class))).thenReturn(
                new ChatFlow.FlowPreparation(LlmPrompt.userOnly("question"),
                        null, List.of(), null));

        ChatPreparation preparation = orchestrator.prepare(
                new ChatCommand(null, "user", "question"), true);

        assertEquals("question", preparation.prompt().userMessage());
        verify(ragFlow, never()).prepare(any(ChatFlowContext.class));
    }

    @Test
    void intentFallbackSelectsRagAndPersistsRewrite() {
        RagReference reference = new RagReference(1, "doc", "chunk",
                null, null, 0.5, null);
        when(recognizer.recognize("question", List.of()))
                .thenReturn(IntentRecognitionResult.fallback("unavailable"));
        when(ragFlow.prepare(any(ChatFlowContext.class))).thenReturn(
                new ChatFlow.FlowPreparation(new LlmPrompt("system", "rag prompt"), "rewritten",
                        List.of(reference), null));

        ChatPreparation preparation = orchestrator.prepare(
                new ChatCommand(null, "user", "question"), true);

        assertEquals(List.of(reference), preparation.ragReferences());
        verify(conversations).updateMessageTransformContent("user-1", "rewritten");
        verify(conversations).updateMessageMetadata(eq("user-1"), anyString());
    }

    @Test
    void passesRecognizedIntentAndKeyInformationToRagFlow() {
        IntentRecognitionResult recognized = new IntentRecognitionResult(
                true, ChatIntent.EXPERIMENT_OR_RESULT, "询问实验指标",
                new IntentKeyInformation(List.of("Paper"), List.of(), List.of(),
                        List.of("RAG"), List.of("HotpotQA"), List.of("F1"),
                        List.of("表 3")), false);
        when(recognizer.recognize("question", List.of())).thenReturn(recognized);
        when(ragFlow.prepare(any(ChatFlowContext.class))).thenReturn(
                new ChatFlow.FlowPreparation(new LlmPrompt("system", "rag prompt"),
                        "rewritten", List.of(), null));

        orchestrator.prepare(new ChatCommand(null, "user", "question"), true);

        ArgumentCaptor<ChatFlowContext> captor =
                ArgumentCaptor.forClass(ChatFlowContext.class);
        verify(ragFlow).prepare(captor.capture());
        assertEquals(recognized, captor.getValue().intent());
        assertEquals("question", captor.getValue().query());
    }

    @Test
    void streamAlwaysOrdersStartDeltaDone() {
        when(recognizer.recognize("question", List.of())).thenReturn(
                new IntentRecognitionResult(false,
                        com.llmstudy.rag.module.chat.model.ChatIntent.GENERAL_CHAT,
                        "general", null, false));
        when(commonFlow.prepare(any(ChatFlowContext.class))).thenReturn(
                new ChatFlow.FlowPreparation(LlmPrompt.userOnly("question"),
                        null, List.of(), null));
        when(executor.execute(any())).thenAnswer(invocation -> {
            ChatPreparation preparation = invocation.getArgument(0);
            return Flux.just(ChatStreamEvent.delta(preparation, "delta"),
                    ChatStreamEvent.done(preparation, "assistant-1", 1, "model"));
        });

        List<ChatStreamEvent.Type> types = orchestrator.stream(
                        new ChatCommand(null, "user", "question"))
                .map(ChatStreamEvent::type).collectList().block();

        assertEquals(List.of(ChatStreamEvent.Type.START,
                ChatStreamEvent.Type.DELTA, ChatStreamEvent.Type.DONE), types);
    }

    @Test
    void synchronousCallKeepsSystemAndCurrentUserRolesSeparated() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("回答"))))
                .build());
        when(commonFlow.prepare(any(ChatFlowContext.class))).thenReturn(
                new ChatFlow.FlowPreparation(
                        new LlmPrompt("系统规则", "当前用户数据"),
                        null, List.of(), null));
        ChatMessage assistant = new ChatMessage();
        assistant.setMessageId("assistant-1");
        when(conversations.saveMessage(eq("conversation-1"),
                eq(MessageType.ASSISTANT), eq("回答"),
                nullable(Integer.class), nullable(String.class))).thenReturn(assistant);
        ChatOrchestrator realClientOrchestrator = new ChatOrchestrator(
                conversations, recognizer, commonFlow, ragFlow, executor,
                mock(TitleSummaryService.class), new ChatProperties(),
                ChatClient.builder(model).build(), JsonMapper.builder().build());

        ChatOrchestrator.ChatAnswer answer = realClientOrchestrator.ask(
                new ChatCommand(null, "user", "question"));

        assertEquals("回答", answer.content());
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(captor.capture());
        assertEquals(2, captor.getValue().getInstructions().size());
        assertTrue(captor.getValue().getInstructions().get(0) instanceof SystemMessage);
        assertTrue(captor.getValue().getInstructions().get(1) instanceof UserMessage);
        assertEquals("系统规则",
                captor.getValue().getInstructions().get(0).getText());
        assertEquals("当前用户数据",
                captor.getValue().getInstructions().get(1).getText());
    }
}

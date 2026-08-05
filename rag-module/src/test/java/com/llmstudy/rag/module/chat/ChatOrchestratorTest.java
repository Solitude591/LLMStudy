package com.llmstudy.rag.module.chat;

import com.llmstudy.rag.config.ChatProperties;
import com.llmstudy.rag.entity.ChatConversation;
import com.llmstudy.rag.entity.ChatMessage;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

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
                new ChatFlow.FlowPreparation("question", null, List.of(), null));

        ChatPreparation preparation = orchestrator.prepare(
                new ChatCommand(null, "user", "question"), true);

        assertEquals("question", preparation.prompt());
        verify(ragFlow, never()).prepare(any(ChatFlowContext.class));
    }

    @Test
    void intentFallbackSelectsRagAndPersistsRewrite() {
        RagReference reference = new RagReference(1, "doc", "chunk",
                null, null, 0.5, null);
        when(recognizer.recognize("question", List.of()))
                .thenReturn(IntentRecognitionResult.fallback("unavailable"));
        when(ragFlow.prepare(any(ChatFlowContext.class))).thenReturn(
                new ChatFlow.FlowPreparation("rag prompt", "rewritten",
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
                new ChatFlow.FlowPreparation("rag prompt", "rewritten", List.of(), null));

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
                new ChatFlow.FlowPreparation("question", null, List.of(), null));
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
}

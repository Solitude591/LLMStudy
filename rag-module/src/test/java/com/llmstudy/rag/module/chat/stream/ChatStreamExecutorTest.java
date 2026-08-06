package com.llmstudy.rag.module.chat.stream;

import com.llmstudy.rag.entity.ChatMessage;
import com.llmstudy.rag.module.chat.conversation.ConversationService;
import com.llmstudy.rag.module.chat.model.ChatPreparation;
import com.llmstudy.rag.module.chat.model.ChatStreamEvent;
import com.llmstudy.rag.module.llm.model.LlmPrompt;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatStreamExecutorTest {

    @Test
    void fixedAnswerEmitsDeltaThenPersistsAndEmitsDone() {
        ConversationService conversations = mock(ConversationService.class);
        ChatMessage saved = new ChatMessage();
        saved.setMessageId("assistant-1");
        when(conversations.saveMessage(eq("conversation-1"), any(),
                eq("no knowledge"), eq(null), eq(null), eq(null), eq(null)))
                .thenReturn(saved);
        ChatPreparation preparation = new ChatPreparation(
                "conversation-1", "title", "user-1", List.of(),
                null, List.of(), "no knowledge");

        List<ChatStreamEvent> events = new ChatStreamExecutor(
                mock(ChatClient.class), conversations, JsonMapper.builder().build())
                .execute(preparation).collectList().block();

        assertEquals(List.of(ChatStreamEvent.Type.DELTA, ChatStreamEvent.Type.DONE),
                events.stream().map(ChatStreamEvent::type).toList());
        assertEquals("assistant-1", events.getLast().assistantMessageId());
    }

    @Test
    void streamsSystemThenHistoryThenCurrentUserMessage() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                ChatResponse.builder().generations(List.of(new Generation(
                        new AssistantMessage("回答")))).build()));
        ConversationService conversations = mock(ConversationService.class);
        ChatMessage saved = new ChatMessage();
        saved.setMessageId("assistant-1");
        when(conversations.saveMessage(eq("conversation-1"), any(),
                eq("回答"), eq(null), eq(null), eq(null), eq(null)))
                .thenReturn(saved);
        ChatPreparation preparation = new ChatPreparation(
                "conversation-1", "title", "user-1",
                List.of(new UserMessage("历史问题"), new AssistantMessage("历史回答")),
                new LlmPrompt("系统规则", "当前用户数据"), List.of(), null);

        List<ChatStreamEvent> events = new ChatStreamExecutor(
                ChatClient.builder(model).build(), conversations,
                JsonMapper.builder().build())
                .execute(preparation).collectList().block();

        assertEquals(ChatStreamEvent.Type.DONE, events.getLast().type());
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(model).stream(captor.capture());
        List<Message> messages = captor.getValue().getInstructions();
        assertEquals(4, messages.size());
        assertTrue(messages.get(0) instanceof SystemMessage);
        assertTrue(messages.get(1) instanceof UserMessage);
        assertTrue(messages.get(2) instanceof AssistantMessage);
        assertTrue(messages.get(3) instanceof UserMessage);
        assertEquals("系统规则", messages.get(0).getText());
        assertEquals("当前用户数据", messages.get(3).getText());
    }
}

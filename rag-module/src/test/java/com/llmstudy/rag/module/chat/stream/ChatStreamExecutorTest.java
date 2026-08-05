package com.llmstudy.rag.module.chat.stream;

import com.llmstudy.rag.entity.ChatMessage;
import com.llmstudy.rag.module.chat.conversation.ConversationService;
import com.llmstudy.rag.module.chat.model.ChatPreparation;
import com.llmstudy.rag.module.chat.model.ChatStreamEvent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
}

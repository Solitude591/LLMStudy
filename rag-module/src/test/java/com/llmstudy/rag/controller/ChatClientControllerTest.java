package com.llmstudy.rag.controller;

import com.llmstudy.rag.auth.model.AuthenticatedUser;
import com.llmstudy.rag.auth.model.UserRole;
import com.llmstudy.rag.auth.service.CurrentUserProvider;
import com.llmstudy.rag.entity.ChatConversation;
import com.llmstudy.rag.entity.ChatMessage;
import com.llmstudy.rag.enums.ConversationStatus;
import com.llmstudy.rag.enums.MessageType;
import com.llmstudy.rag.module.chat.ChatOrchestrator;
import com.llmstudy.rag.module.chat.conversation.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatClientControllerTest {

    private ConversationService conversationService;
    private CurrentUserProvider currentUserProvider;
    private ChatClientController controller;

    @BeforeEach
    void setUp() {
        conversationService = mock(ConversationService.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        when(currentUserProvider.requireCurrentUser()).thenReturn(
                new AuthenticatedUser("default-user", "alice", "Alice", "org-a", UserRole.USER));
        controller = new ChatClientController(
                mock(ChatOrchestrator.class), conversationService, currentUserProvider);
    }

    @Test
    void listConversations_usesDefaultUserAndMapsDatabaseRecords() {
        ChatConversation conversation = new ChatConversation();
        conversation.setConversationId("conversation-1");
        conversation.setTitle("医学图像分割");
        conversation.setConversationStatus(ConversationStatus.ACTIVE);
        when(conversationService.listConversations("default-user"))
                .thenReturn(List.of(conversation));

        var result = controller.listConversations();

        assertEquals(1, result.size());
        assertEquals("conversation-1", result.getFirst().conversationId());
        assertEquals(ConversationStatus.ACTIVE, result.getFirst().status());
    }

    @Test
    void listMessages_mapsStoredMessages() {
        ChatMessage message = new ChatMessage();
        message.setMessageId("message-1");
        message.setMessageType(MessageType.ASSISTANT);
        message.setContent("回答");
        message.setTokenCount(12);
        when(conversationService.listMessages("conversation-1", "default-user"))
                .thenReturn(List.of(message));

        var result = controller.listMessages("conversation-1");

        assertEquals(1, result.size());
        assertEquals(MessageType.ASSISTANT, result.getFirst().type());
        assertEquals(12, result.getFirst().tokenCount());
    }

    @Test
    void deleteConversation_delegatesToLogicalDeleteService() {
        var response = controller.deleteConversation("conversation-1");

        verify(conversationService).deleteConversation("conversation-1", "default-user");
        assertEquals(204, response.getStatusCode().value());
        assertTrue(response.getHeaders().isEmpty());
    }
}

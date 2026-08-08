package com.llmstudy.rag.module.chat.conversation;

import com.llmstudy.rag.entity.ChatConversation;
import com.llmstudy.rag.entity.ChatMessage;
import com.llmstudy.rag.enums.ConversationStatus;
import com.llmstudy.rag.enums.MessageType;
import com.llmstudy.rag.mapper.ChatConversationMapper;
import com.llmstudy.rag.mapper.ChatMessageMapper;
import com.llmstudy.rag.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultConversationServiceTest {

    private ChatConversationMapper conversationMapper;
    private ChatMessageMapper messageMapper;
    private ChatHistoryCache historyCache;
    private SnowflakeIdGenerator idGenerator;
    private DefaultConversationService chatService;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(ChatConversationMapper.class);
        messageMapper = mock(ChatMessageMapper.class);
        historyCache = mock(ChatHistoryCache.class);
        idGenerator = mock(SnowflakeIdGenerator.class);
        chatService = new DefaultConversationService(
                conversationMapper, messageMapper, historyCache, idGenerator);
    }

    @Test
    void createConversation_生成Uuid并使用活跃状态() {
        when(conversationMapper.insert(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);
        when(conversationMapper.findByConversationId(anyString()))
                .thenAnswer(invocation -> {
                    ChatConversation result = new ChatConversation();
                    result.setConversationId(invocation.getArgument(0));
                    result.setUserId("user-1");
                    result.setTitle("测试会话");
                    result.setConversationStatus(ConversationStatus.ACTIVE);
                    return result;
                });

        ChatConversation result =
                chatService.createConversation(" user-1 ", " 测试会话 ");

        assertNotNull(UUID.fromString(result.getConversationId()));
        assertEquals(ConversationStatus.ACTIVE, result.getStatus());

        ArgumentCaptor<ChatConversation> captor =
                ArgumentCaptor.forClass(ChatConversation.class);
        verify(conversationMapper).insert(captor.capture());
        assertEquals("user-1", captor.getValue().getUserId());
        assertEquals("测试会话", captor.getValue().getTitle());
        assertEquals(ConversationStatus.ACTIVE, captor.getValue().getStatus());
    }

    @Test
    void saveMessage_保存消息并刷新会话时间() {
        ChatConversation conversation = new ChatConversation();
        conversation.setConversationId("conversation-1");
        conversation.setConversationStatus(ConversationStatus.ACTIVE);
        conversation.setMessageVersion(0L);

        when(conversationMapper.findByConversationId("conversation-1"))
                .thenReturn(conversation);
        when(idGenerator.nextId()).thenReturn(2001L);
        when(messageMapper.insert(org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);
        when(conversationMapper.touchAndIncrementMessageVersion(
                "conversation-1")).thenAnswer(invocation -> {
                    conversation.setMessageVersion(1L);
                    return 1;
                });
        when(messageMapper.findByMessageId("2001"))
                .thenAnswer(invocation -> {
                    ChatMessage result = new ChatMessage();
                    result.setMessageId("2001");
                    result.setConversationId("conversation-1");
                    result.setMessageType(MessageType.USER);
                    result.setContent("你好");
                    return result;
                });

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            ChatMessage result = chatService.saveMessage(
                    "conversation-1", MessageType.USER,
                    "你好", 42, "test-model");

            assertEquals("2001", result.getMessageId());
            assertEquals(MessageType.USER, result.getMessageType());

            ArgumentCaptor<ChatMessage> captor =
                    ArgumentCaptor.forClass(ChatMessage.class);
            verify(messageMapper).insert(captor.capture());
            assertEquals("USER", captor.getValue().getType());
            assertEquals(42, captor.getValue().getTokenCount());
            assertEquals("test-model", captor.getValue().getModelName());
            verify(conversationMapper).touchAndIncrementMessageVersion(
                    "conversation-1");

            verify(historyCache, never()).push(
                    "conversation-1", result, 1L);
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    TransactionSynchronization::afterCommit);
            verify(historyCache).push("conversation-1", result, 1L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void deleteConversation_将会话标记为已删除() {
        ChatConversation conversation = new ChatConversation();
        conversation.setConversationId("conversation-1");
        conversation.setUserId("user-1");
        conversation.setConversationStatus(ConversationStatus.ACTIVE);
        when(conversationMapper.findByConversationIdAndUserId(
                "conversation-1", "user-1"))
                .thenReturn(conversation);
        when(conversationMapper.updateStatus(
                "conversation-1", ConversationStatus.DELETED))
                .thenReturn(1);

        chatService.deleteConversation("conversation-1", "user-1");

        verify(conversationMapper).updateStatus(
                "conversation-1", ConversationStatus.DELETED);
        verify(historyCache).delete("conversation-1");
    }

    @Test
    void updateMessageMetadata_只更新Metadata字段() {
        when(messageMapper.updateMetadata("2001", "{\"related\":true}"))
                .thenReturn(1);

        chatService.updateMessageMetadata("2001", "{\"related\":true}");

        verify(messageMapper).updateMetadata(
                "2001", "{\"related\":true}");
    }
}

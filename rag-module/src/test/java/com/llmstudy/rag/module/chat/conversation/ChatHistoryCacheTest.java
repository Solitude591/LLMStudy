package com.llmstudy.rag.module.chat.conversation;

import com.llmstudy.rag.config.ChatProperties;
import com.llmstudy.rag.entity.ChatMessage;
import com.llmstudy.rag.enums.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatHistoryCacheTest {

    private StringRedisTemplate redis;
    private ListOperations<String, String> lists;
    private JsonMapper jsonMapper;
    private ChatHistoryCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        lists = mock(ListOperations.class);
        jsonMapper = JsonMapper.builder().build();
        ChatProperties properties = new ChatProperties();
        properties.setHistoryCacheTtlSeconds(60);
        when(redis.opsForList()).thenReturn(lists);
        cache = new ChatHistoryCache(redis, jsonMapper, properties);
    }

    @Test
    void matchingVersionReturnsMessagesAndRenewsTtl() throws Exception {
        ChatMessage message = new ChatMessage();
        message.setMessageId("m-1");
        message.setConversationId("conversation-1");
        message.setMessageType(MessageType.USER);
        message.setContent("你好");
        when(lists.range("chat:history:v2:conversation-1", 0, -1))
                .thenReturn(List.of("3", jsonMapper.writeValueAsString(message)));

        List<ChatMessage> result = cache.get("conversation-1", 3L);

        assertEquals(1, result.size());
        assertEquals("m-1", result.getFirst().getMessageId());
        verify(redis).expire("chat:history:v2:conversation-1",
                Duration.ofSeconds(60));
    }

    @Test
    void mismatchedVersionIsTreatedAsCacheMiss() {
        when(lists.range("chat:history:v2:conversation-1", 0, -1))
                .thenReturn(List.of("2"));

        assertNull(cache.get("conversation-1", 3L));
        verify(redis, never()).expire(
                "chat:history:v2:conversation-1", Duration.ofSeconds(60));
    }

    @Test
    void luaScriptsArePackagedAsClasspathResources() throws Exception {
        ClassPathResource push = new ClassPathResource(
                "redis/chat-history-push.lua");
        ClassPathResource replace = new ClassPathResource(
                "redis/chat-history-replace.lua");

        assertTrue(push.exists());
        assertTrue(replace.exists());
        assertTrue(push.getContentAsString(StandardCharsets.UTF_8)
                .contains("current_version ~= next_version - 1"));
        assertTrue(replace.getContentAsString(StandardCharsets.UTF_8)
                .contains("current_version > incoming_version"));
    }
}

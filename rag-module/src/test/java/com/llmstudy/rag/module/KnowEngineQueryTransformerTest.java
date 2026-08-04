package com.llmstudy.rag.module;

import com.llmstudy.rag.service.ChatService;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KnowEngineQueryTransformer 的单元测试，验证改写后只返回一条 Query，
 * 且原问题通过 metadata.chatMessage 保留，供混合检索器读取执行 BM25。
 */
class KnowEngineQueryTransformerTest {

    @Test
    void transform_只返回改写后Query且原问题保留在metadata中() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(ChatResponse.builder()
                .generations(List.of(new Generation(
                        new AssistantMessage("改写后的问题"))))
                .build());

        // chatService 与 sourceMessageId 传 null，避免测试触发数据库回写。
        KnowEngineQueryTransformer transformer =
                new KnowEngineQueryTransformer(chatModel, mock(ChatService.class), null);

        List<Query> queries = transformer
                .transform(Query.from("用户原始问题"))
                .stream()
                .toList();

        // 只返回改写后 Query，不再同时返回原 Query，避免 Retriever 被调用两次。
        assertEquals(1, queries.size());
        Query transformed = queries.getFirst();
        assertEquals("改写后的问题", transformed.text());

        // 原问题写入 metadata.chatMessage，供 BM25 通道读取。
        UserMessage chatMessage = (UserMessage) transformed.metadata().chatMessage();
        assertTrue(chatMessage.hasSingleText());
        assertEquals("用户原始问题", chatMessage.singleText());
    }
}

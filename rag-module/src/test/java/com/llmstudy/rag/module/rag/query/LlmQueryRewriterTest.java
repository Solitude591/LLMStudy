package com.llmstudy.rag.module.rag.query;

import com.llmstudy.rag.module.rag.model.RagRequest;
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
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmQueryRewriterTest {

    @Test
    void parsesBilingualJsonAndKeepsOriginalQuestion() {
        LlmQueryRewriter rewriter = rewriter("""
                {"standaloneZh":"MDCL-UNet 在表 3 的 Dice 是多少？",\
                "standaloneEn":"What is the Dice of MDCL-UNet in Table 3?"}
                """);

        var result = rewriter.rewrite(new RagRequest(
                "它的 Dice 是多少？", "USER: MDCL-UNet 表 3"));

        assertEquals("它的 Dice 是多少？", result.originalQuestion());
        assertEquals("MDCL-UNet 在表 3 的 Dice 是多少？", result.standaloneZh());
        assertEquals("What is the Dice of MDCL-UNet in Table 3?", result.standaloneEn());
    }

    @Test
    void sendsStableRulesAsSystemAndRuntimeContextAsUser() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(jsonResponse(
                "{\"standaloneZh\":\"中文\",\"standaloneEn\":\"English\"}"));
        LlmQueryRewriter rewriter = new LlmQueryRewriter(
                ChatClient.builder(model).build(),
                JsonMapper.builder().build(),
                text("稳定改写规则"),
                text("<history>{conversationContext}</history>\n"
                        + "<query>{query}</query>"));

        rewriter.rewrite(new RagRequest("它的 F1 是多少？", "USER: RAG 论文"));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(captor.capture());
        List<org.springframework.ai.chat.messages.Message> messages =
                captor.getValue().getInstructions();
        assertEquals(2, messages.size());
        assertTrue(messages.get(0) instanceof SystemMessage);
        assertTrue(messages.get(1) instanceof UserMessage);
        assertEquals("稳定改写规则", messages.get(0).getText());
        assertTrue(messages.get(1).getText().contains("USER: RAG 论文"));
        assertTrue(messages.get(1).getText().contains("它的 F1 是多少？"));
        assertFalse(messages.get(0).getText().contains("它的 F1 是多少？"));
    }

    @Test
    void markdownFenceIsRewriteFailure() {
        LlmQueryRewriter rewriter = rewriter("""
                ```json
                {"standaloneZh":"中文","standaloneEn":"English"}
                ```
                """);
        QueryRewriteException error = assertThrows(QueryRewriteException.class,
                () -> rewriter.rewrite(new RagRequest("问题", "无")));
        assertEquals(QueryRewriteException.SAFE_MESSAGE, error.getMessage());
    }

    @Test
    void trailingTextIsRewriteFailure() {
        LlmQueryRewriter rewriter = rewriter(
                "{\"standaloneZh\":\"中文\",\"standaloneEn\":\"English\"}\n解释一下");
        assertThrows(QueryRewriteException.class,
                () -> rewriter.rewrite(new RagRequest("问题", "无")));
    }

    @Test
    void emptyFieldIsRewriteFailure() {
        LlmQueryRewriter rewriter = rewriter(
                "{\"standaloneZh\":\"中文\",\"standaloneEn\":\"\"}");
        assertThrows(QueryRewriteException.class,
                () -> rewriter.rewrite(new RagRequest("问题", "无")));
    }

    private static LlmQueryRewriter rewriter(String modelOutput) {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(jsonResponse(modelOutput));
        return new LlmQueryRewriter(
                ChatClient.builder(model).build(),
                JsonMapper.builder().build(),
                text("rules"),
                text("{conversationContext}{query}"));
    }

    private static ChatResponse jsonResponse(String text) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .build();
    }

    private static ByteArrayResource text(String value) {
        return new ByteArrayResource(value.getBytes(StandardCharsets.UTF_8));
    }
}

package com.llmstudy.rag.module.rag.query;

import com.llmstudy.rag.module.rag.model.RagRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmQueryRewriterTest {

    @Test
    void sendsStableRulesAsSystemAndRuntimeContextAsUser() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(ChatResponse.builder()
                .generations(List.of(new Generation(
                        new AssistantMessage("改写后的问题"))))
                .build());
        LlmQueryRewriter rewriter = new LlmQueryRewriter(
                ChatClient.builder(model).build(),
                text("稳定改写规则"),
                text("<history>{conversationContext}</history>\n"
                        + "<query>{query}</query>"));

        var result = rewriter.rewrite(new RagRequest(
                "它的 F1 是多少？", "USER: RAG 论文"));

        assertEquals("改写后的问题", result.rewrittenQuestion());
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

    private static ByteArrayResource text(String value) {
        return new ByteArrayResource(value.getBytes(StandardCharsets.UTF_8));
    }
}

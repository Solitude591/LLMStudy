package com.llmstudy.rag.module.chat.intent;

import com.llmstudy.rag.config.IntentProperties;
import com.llmstudy.rag.module.chat.model.IntentRecognitionResult;
import com.llmstudy.rag.module.chat.model.ChatIntent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmIntentRecognizerTest {

    @Test
    void recognize_解析结构化结果并根据意图规范化相关性() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(response("""
                {
                  "related": false,
                  "intent": "METHOD_OR_CONCEPT",
                  "reason": "问题在询问论文方法",
                  "keyInformation": {
                    "paperTitles": [],
                    "authors": [],
                    "researchTopics": ["检索增强生成"],
                    "methodsOrModels": ["RAG"],
                    "datasets": [],
                    "metrics": [],
                    "otherConstraints": []
                  },
                  "fallback": false
                }
                """));

        IntentRecognitionResult result = service(chatModel).recognize(
                "它是如何做混合检索的？",
                List.of(
                        new UserMessage("我们来读这篇 RAG 论文"),
                        new AssistantMessage("好的")));

        assertTrue(result.related());
        assertEquals(ChatIntent.METHOD_OR_CONCEPT, result.intent());
        assertEquals(List.of("RAG"), result.keyInformation().methodsOrModels());
        assertFalse(result.fallback());
    }

    @Test
    void recognize_模型输出不可解析时保守进入Rag() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(response("not-json"));

        IntentRecognitionResult result =
                service(chatModel).recognize("今天天气如何", List.of());

        assertTrue(result.related());
        assertEquals(ChatIntent.UNKNOWN, result.intent());
        assertTrue(result.fallback());
    }

    private static LlmIntentRecognizer service(ChatModel chatModel) {
        when(chatModel.getOptions()).thenReturn(
                OpenAiChatOptions.builder().build());
        return new LlmIntentRecognizer(
                ChatClient.builder(chatModel).build(),
                new IntentProperties(),
                new ClassPathResource("prompts/chat/intent-recognition.st"));
    }

    private static ChatResponse response(String content) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(
                        new AssistantMessage(content))))
                .build();
    }
}

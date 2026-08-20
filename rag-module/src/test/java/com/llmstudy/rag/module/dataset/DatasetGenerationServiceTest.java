package com.llmstudy.rag.module.dataset;

import com.llmstudy.rag.dto.DatasetGenerateResponse;
import com.llmstudy.rag.config.RagAnswerProperties;
import com.llmstudy.rag.module.chat.flow.RagChatFlow;
import com.llmstudy.rag.module.llm.model.LlmPrompt;
import com.llmstudy.rag.module.rag.RagPipeline;
import com.llmstudy.rag.module.rag.model.RagIntentContext;
import com.llmstudy.rag.module.rag.model.RagReference;
import com.llmstudy.rag.module.rag.model.RagRequest;
import com.llmstudy.rag.module.rag.model.RagResult;
import com.llmstudy.rag.module.rag.model.RetrievalQueryPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasetGenerationServiceTest {

    private RagPipeline pipeline;
    private ChatModel chatModel;
    private DatasetGenerationService service;

    @BeforeEach
    void setUp() {
        pipeline = mock(RagPipeline.class);
        chatModel = mock(ChatModel.class);
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        service = new DatasetGenerationService(
                pipeline, ChatClient.builder(chatModel).build(), new RagAnswerProperties());
    }

    @Test
    void passesNullAccessContextAndOriginalQueryToPipeline() {
        when(pipeline.execute(any())).thenReturn(emptyResult());

        service.generate("表 3 中哪个模型的 F1 最高？");

        ArgumentCaptor<RagRequest> captor = ArgumentCaptor.forClass(RagRequest.class);
        verify(pipeline).execute(captor.capture());
        RagRequest request = captor.getValue();
        assertEquals("表 3 中哪个模型的 F1 最高？", request.question());
        assertEquals("无", request.conversationContext());
        assertEquals(RagIntentContext.generic(), request.intentContext());
        assertNull(request.accessContext());
    }

    @Test
    void returnsOriginalQueryNotRewrittenQuestion() {
        when(pipeline.execute(any())).thenReturn(new RagResult(
                new LlmPrompt("system", "user"),
                new RetrievalQueryPlan("原问题", "改写后的问题", "rewritten"),
                List.of(new RagReference(1, "doc", "c1", null, null, null, null, 0.9, null)),
                List.of("证据正文")));
        when(chatModel.call(any(Prompt.class))).thenReturn(ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("Hybrid RAG 最高[1]。"))))
                .build());

        DatasetGenerateResponse response = service.generate("原问题");

        assertEquals("原问题", response.query());
        assertEquals("Hybrid RAG 最高[1]。", response.response());
        assertEquals(List.of("证据正文"), response.chunks());
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertEquals(0.0, ((OpenAiChatOptions) prompt.getValue().getOptions())
                .getTemperature());
    }

    @Test
    void emptyRetrievalReturnsFixedAnswerWithoutCallingLlm() {
        when(pipeline.execute(any())).thenReturn(emptyResult());

        DatasetGenerateResponse response = service.generate("无结果问题");

        assertEquals("无结果问题", response.query());
        assertEquals(RagChatFlow.NO_KNOWLEDGE_ANSWER, response.response());
        assertEquals(List.of(), response.chunks());
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void blankModelResponseThrows() {
        when(pipeline.execute(any())).thenReturn(new RagResult(
                new LlmPrompt("system", "user"),
                new RetrievalQueryPlan("q", "rewritten", "rewritten"),
                List.of(new RagReference(1, "doc", "c1", null, null, null, null, 0.5, null)),
                List.of("chunk")));
        when(chatModel.call(any(Prompt.class))).thenReturn(ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("   "))))
                .build());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.generate("q"));
        assertTrue(error.getMessage().contains("有效内容")
                || error.getMessage().contains("输出内容"));
    }

    @Test
    void rejectsBlankQuery() {
        assertThrows(IllegalArgumentException.class, () -> service.generate("  "));
        verify(pipeline, never()).execute(any());
    }

    private static RagResult emptyResult() {
        return new RagResult(null, new RetrievalQueryPlan("q", "rewritten", "rewritten"),
                List.of(), List.of());
    }
}

package com.llmstudy.rag.module.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmFileLoggingAdvisorTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private boolean originalAdditive;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger("LLM_CALL_FILE");
        originalAdditive = logger.isAdditive();
        originalLevel = logger.getLevel();
        logger.setAdditive(false);
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setAdditive(originalAdditive);
        logger.setLevel(originalLevel);
    }

    @Test
    void callLogsReadableRequestAndResponseWithSameCallId() {
        ChatModel model = model();
        when(model.call(any(Prompt.class))).thenReturn(response("回答内容"));
        ChatClient client = client(model);

        String content = client.prompt()
                .user("用户问题")
                .advisors(spec -> spec.params(LlmTraceContext.params("test-call")))
                .call()
                .content();

        assertEquals("回答内容", content);
        String logs = formattedLogs();
        assertTrue(logs.contains("LLM REQUEST"));
        assertTrue(logs.contains("stage: test-call"));
        assertTrue(logs.contains("用户问题"));
        assertTrue(logs.contains("LLM RESPONSE"));
        assertTrue(logs.contains("回答内容"));
        assertEquals(1, distinctCallIds());
    }

    @Test
    void streamLogsOneAggregatedResponseInsteadOfTokenChunks() {
        ChatModel model = model();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                response("流式"), response("回答")));
        ChatClient client = client(model);

        String content = String.join("", client.prompt()
                .user("流式问题")
                .stream()
                .content()
                .collectList()
                .block());

        assertEquals("流式回答", content);
        String logs = formattedLogs();
        assertTrue(logs.contains("stream: true"));
        assertTrue(logs.contains("流式回答"));
        assertEquals(1, appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("LLM RESPONSE"))
                .count());
    }

    @Test
    void diagnoseTraceContextAddsAndClearsTraceId() {
        assertNull(LlmTraceContext.params("before").get(LlmFileLoggingAdvisor.TRACE_ID_KEY));
        try (LlmTraceContext ignored = LlmTraceContext.openDiagnose("trace-diagnose-1")) {
            assertEquals("trace-diagnose-1", LlmTraceContext.params("rewrite")
                    .get(LlmFileLoggingAdvisor.TRACE_ID_KEY));
            assertEquals("trace-diagnose-1", org.slf4j.MDC.get(LlmTraceContext.MDC_TRACE_ID));
        }
        assertNull(LlmTraceContext.params("after").get(LlmFileLoggingAdvisor.TRACE_ID_KEY));
        assertNull(org.slf4j.MDC.get(LlmTraceContext.MDC_TRACE_ID));
    }

    @Test
    void traceContextAddsAndRestoresConversationIdentifiers() {
        assertEquals("-", LlmTraceContext.params("before").getOrDefault(
                LlmFileLoggingAdvisor.CONVERSATION_ID_KEY, "-"));

        try (LlmTraceContext ignored =
                     LlmTraceContext.open("conversation-1", "message-1")) {
            assertEquals("conversation-1", LlmTraceContext.params("rewrite").get(
                    LlmFileLoggingAdvisor.CONVERSATION_ID_KEY));
            assertEquals("message-1", LlmTraceContext.params("rewrite").get(
                    LlmFileLoggingAdvisor.MESSAGE_ID_KEY));
        }

        assertEquals("-", LlmTraceContext.params("after").getOrDefault(
                LlmFileLoggingAdvisor.CONVERSATION_ID_KEY, "-"));
    }

    private ChatClient client(ChatModel model) {
        return ChatClient.builder(model)
                .defaultAdvisors(new LlmFileLoggingAdvisor())
                .build();
    }

    private ChatModel model() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        return model;
    }

    private String formattedLogs() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }

    private long distinctCallIds() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .flatMap(message -> message.lines()
                        .filter(line -> line.trim().startsWith("callId:")))
                .map(line -> line.substring(line.indexOf(':') + 1).trim())
                .distinct()
                .count();
    }

    private static ChatResponse response(String content) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(
                        new AssistantMessage(content))))
                .build();
    }
}

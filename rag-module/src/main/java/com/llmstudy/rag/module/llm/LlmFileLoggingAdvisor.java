package com.llmstudy.rag.module.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将全部 ChatClient 请求与完整响应写入独立的 LLM 调用日志。
 * 流式响应通过 Spring AI 聚合器在完成时记录一次，不逐分片刷日志。
 */
@Component
public class LlmFileLoggingAdvisor implements CallAdvisor, StreamAdvisor {

    public static final String STAGE_KEY = "llm.log.stage";
    public static final String CONVERSATION_ID_KEY = "llm.log.conversation-id";
    public static final String MESSAGE_ID_KEY = "llm.log.message-id";
    public static final String TRACE_ID_KEY = "llm.log.trace-id";

    private static final Logger log = LoggerFactory.getLogger("LLM_CALL_FILE");

    @Override
    public ChatClientResponse adviseCall(
            ChatClientRequest request, CallAdvisorChain chain) {
        String callId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        logRequest(callId, false, request);
        try {
            ChatClientResponse response = chain.nextCall(request);
            logResponse(callId, startedAt, response);
            return response;
        } catch (RuntimeException | Error e) {
            logFailure(callId, startedAt, e);
            throw e;
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            String callId = UUID.randomUUID().toString();
            long startedAt = System.nanoTime();
            AtomicBoolean terminalLogged = new AtomicBoolean();
            logRequest(callId, true, request);
            try {
                Flux<ChatClientResponse> responses = chain.nextStream(request);
                return new ChatClientMessageAggregator()
                        .aggregateChatClientResponse(responses, response -> {
                            terminalLogged.set(true);
                            logResponse(callId, startedAt, response);
                        })
                        .doOnError(error -> {
                            if (terminalLogged.compareAndSet(false, true)) {
                                logFailure(callId, startedAt, error);
                            }
                        })
                        .doOnCancel(() -> {
                            if (terminalLogged.compareAndSet(false, true)) {
                                log.warn("""

                                        ========== LLM CALL CANCELLED ==========
                                        callId: {}
                                        elapsedMs: {}
                                        ==========================================
                                        """, callId, elapsedMillis(startedAt));
                            }
                        });
            } catch (RuntimeException | Error e) {
                logFailure(callId, startedAt, e);
                throw e;
            }
        });
    }

    private void logRequest(String callId, boolean stream,
                            ChatClientRequest request) {
        Map<String, Object> context = request.context();
        log.info("""

                ========== LLM REQUEST ==========
                callId: {}
                stage: {}
                conversationId: {}
                messageId: {}
                traceId: {}
                stream: {}
                options: {}
                messages:
                {}
                ========== END REQUEST ==========
                """,
                callId,
                contextValue(context, STAGE_KEY),
                contextValue(context, CONVERSATION_ID_KEY),
                contextValue(context, MESSAGE_ID_KEY),
                contextValue(context, TRACE_ID_KEY),
                stream,
                request.prompt().getOptions(),
                formatMessages(request.prompt().getInstructions()));
    }

    private void logResponse(String callId, long startedAt,
                             ChatClientResponse clientResponse) {
        ChatResponse response = clientResponse == null
                ? null : clientResponse.chatResponse();
        log.info("""

                ========== LLM RESPONSE ==========
                callId: {}
                elapsedMs: {}
                metadata: {}
                generations:
                {}
                ========== END RESPONSE ==========
                """,
                callId,
                elapsedMillis(startedAt),
                response == null ? null : response.getMetadata(),
                formatGenerations(response));
    }

    private void logFailure(String callId, long startedAt, Throwable error) {
        log.error("""

                ========== LLM CALL FAILED ==========
                callId: {}
                elapsedMs: {}
                errorType: {}
                errorMessage: {}
                ======================================
                """,
                callId,
                elapsedMillis(startedAt),
                error.getClass().getName(),
                error.getMessage(),
                error);
    }

    private static String formatMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "(none)";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (i > 0) {
                text.append('\n');
            }
            text.append("--- message[").append(i).append("] role=")
                    .append(message.getMessageType()).append(" ---\n")
                    .append(message.getText());
        }
        return text.toString();
    }

    private static String formatGenerations(ChatResponse response) {
        if (response == null || response.getResults() == null
                || response.getResults().isEmpty()) {
            return "(none)";
        }
        StringBuilder text = new StringBuilder();
        List<Generation> generations = response.getResults();
        for (int i = 0; i < generations.size(); i++) {
            Generation generation = generations.get(i);
            if (i > 0) {
                text.append('\n');
            }
            text.append("--- generation[").append(i).append("] metadata=")
                    .append(generation.getMetadata()).append(" ---\n")
                    .append(generation.getOutput() == null
                            ? null : generation.getOutput().getText());
        }
        return text.toString();
    }

    private static String contextValue(Map<String, Object> context, String key) {
        if (context == null || context.get(key) == null) {
            return "-";
        }
        return String.valueOf(context.get(key));
    }

    private static long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    @Override
    public String getName() {
        return LlmFileLoggingAdvisor.class.getSimpleName();
    }

    @Override
    public int getOrder() {
        // 紧贴最终 ChatModel Advisor，记录其他 Advisor 改写后的真实出站请求。
        return Ordered.LOWEST_PRECEDENCE - 1;
    }
}

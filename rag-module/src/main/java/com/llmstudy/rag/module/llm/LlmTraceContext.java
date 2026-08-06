package com.llmstudy.rag.module.llm;

import java.util.LinkedHashMap;
import java.util.Map;

/** 单次同步 RAG 路由期间的 LLM 日志关联上下文。 */
public final class LlmTraceContext implements AutoCloseable {

    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();

    private final Trace previous;

    private LlmTraceContext(Trace trace) {
        this.previous = CURRENT.get();
        CURRENT.set(trace);
    }

    public static LlmTraceContext open(
            String conversationId, String messageId) {
        return new LlmTraceContext(new Trace(conversationId, messageId));
    }

    /** 生成 Advisor 参数，同时补充当前会话与用户消息标识。 */
    public static Map<String, Object> params(String stage) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(LlmFileLoggingAdvisor.STAGE_KEY, stage);
        Trace trace = CURRENT.get();
        if (trace != null) {
            putIfPresent(params, LlmFileLoggingAdvisor.CONVERSATION_ID_KEY,
                    trace.conversationId());
            putIfPresent(params, LlmFileLoggingAdvisor.MESSAGE_ID_KEY,
                    trace.messageId());
        }
        return Map.copyOf(params);
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    private static void putIfPresent(
            Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private record Trace(String conversationId, String messageId) {
    }
}

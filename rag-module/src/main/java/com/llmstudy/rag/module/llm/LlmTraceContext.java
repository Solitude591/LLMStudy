package com.llmstudy.rag.module.llm;

import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单次同步 RAG 路由期间的 LLM / 检索日志关联上下文。
 *
 * <p>诊断链路额外携带 {@code traceId}，并同步写入 SLF4J MDC，
 * 便于用响应里的 traceId 检索改写、ES、BGE 相关日志。</p>
 */
public final class LlmTraceContext implements AutoCloseable {

    public static final String MDC_TRACE_ID = "traceId";

    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();

    private final Trace previous;
    private final String previousMdcTraceId;

    private LlmTraceContext(Trace trace) {
        this.previous = CURRENT.get();
        this.previousMdcTraceId = MDC.get(MDC_TRACE_ID);
        CURRENT.set(trace);
        if (trace.traceId() == null || trace.traceId().isBlank()) {
            MDC.remove(MDC_TRACE_ID);
        } else {
            MDC.put(MDC_TRACE_ID, trace.traceId());
        }
    }

    /** 聊天路由：关联会话与用户消息。 */
    public static LlmTraceContext open(String conversationId, String messageId) {
        return new LlmTraceContext(new Trace(conversationId, messageId, null));
    }

    /**
     * 检索诊断：在改写/检索开始前绑定 traceId。
     *
     * <p>conversationId / messageId 留空，诊断不落聊天消息。</p>
     */
    public static LlmTraceContext openDiagnose(String traceId) {
        return new LlmTraceContext(new Trace(null, null, traceId));
    }

    /** 生成 Advisor 参数，同时补充当前会话、用户消息与诊断 traceId。 */
    public static Map<String, Object> params(String stage) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(LlmFileLoggingAdvisor.STAGE_KEY, stage);
        Trace trace = CURRENT.get();
        if (trace != null) {
            putIfPresent(params, LlmFileLoggingAdvisor.CONVERSATION_ID_KEY,
                    trace.conversationId());
            putIfPresent(params, LlmFileLoggingAdvisor.MESSAGE_ID_KEY,
                    trace.messageId());
            putIfPresent(params, LlmFileLoggingAdvisor.TRACE_ID_KEY,
                    trace.traceId());
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
        if (previousMdcTraceId == null) {
            MDC.remove(MDC_TRACE_ID);
        } else {
            MDC.put(MDC_TRACE_ID, previousMdcTraceId);
        }
    }

    private static void putIfPresent(
            Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private record Trace(String conversationId, String messageId, String traceId) {
    }
}

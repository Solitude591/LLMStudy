package com.llmstudy.rag.dto;

/**
 * 流式聊天 SSE 事件。
 *
 * @param event              事件类型：START、DELTA 或 DONE
 * @param conversationId     会话 ID，每个事件都会携带
 * @param userMessageId      本次用户消息 ID
 * @param assistantMessageId 助手消息 ID，仅 DONE 事件有值
 * @param content            本次增量文本，仅 DELTA 事件有值
 * @param tokenCount         Token 总数，仅 DONE 事件可能有值
 * @param modelName          模型名称，仅 DONE 事件可能有值
 */
public record ChatStreamResponse(
        String event,
        String conversationId,
        String userMessageId,
        String assistantMessageId,
        String content,
        Integer tokenCount,
        String modelName) {

    /**
     * 构造流开始事件，让前端在收到模型文本之前拿到会话和消息 ID。
     */
    public static ChatStreamResponse start(
            String conversationId, String userMessageId) {
        return new ChatStreamResponse(
                "START", conversationId, userMessageId,
                null, null, null, null);
    }

    /**
     * 构造模型文本增量事件。
     */
    public static ChatStreamResponse delta(
            String conversationId, String userMessageId, String content) {
        return new ChatStreamResponse(
                "DELTA", conversationId, userMessageId,
                null, content, null, null);
    }

    /**
     * 构造流完成事件，返回已落库的助手消息 ID 和模型用量。
     */
    public static ChatStreamResponse done(
            String conversationId,
            String userMessageId,
            String assistantMessageId,
            Integer tokenCount,
            String modelName) {
        return new ChatStreamResponse(
                "DONE", conversationId, userMessageId,
                assistantMessageId, null, tokenCount, modelName);
    }
}

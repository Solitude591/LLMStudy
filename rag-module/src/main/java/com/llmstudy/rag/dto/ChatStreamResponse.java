package com.llmstudy.rag.dto;

/**
 * 流式聊天对外 SSE 事件 DTO。
 *
 * <p>在原有 START / DELTA / DONE 之上扩展可空的进度字段。旧前端若忽略
 * 未知 {@code event=PROGRESS}，不影响 DELTA/DONE 的原有语义。</p>
 *
 * @param event              事件类型：START、PROGRESS、DELTA 或 DONE
 * @param conversationId     会话 ID，每个事件都会携带
 * @param conversationTitle  会话临时标题，仅 START 事件有值
 * @param userMessageId      本次用户消息 ID
 * @param assistantMessageId 助手消息 ID，仅 DONE 事件有值
 * @param content            本次增量文本，仅 DELTA 事件有值
 * @param tokenCount         Token 总数，仅 DONE 事件可能有值
 * @param modelName          模型名称，仅 DONE 事件可能有值
 * @param progressName       进度阶段代码，仅 PROGRESS 事件有值
 * @param progressMessage    进度中文文案，仅 PROGRESS 事件有值
 */
public record ChatStreamResponse(
        String event,
        String conversationId,
        String conversationTitle,
        String userMessageId,
        String assistantMessageId,
        String content,
        Integer tokenCount,
        String modelName,
        String progressName,
        String progressMessage) {

    /**
     * 构造流开始事件，让前端在收到模型文本之前拿到会话和消息 ID。
     */
    public static ChatStreamResponse start(
            String conversationId,
            String conversationTitle,
            String userMessageId) {
        return new ChatStreamResponse(
                "START", conversationId, conversationTitle, userMessageId,
                null, null, null, null, null, null);
    }

    /**
     * 构造准备阶段进度事件。
     *
     * <p>不携带回答正文；前端应在 assistant 等待区替换展示，
     * 且不得把文案累加进最终回答 content。</p>
     */
    public static ChatStreamResponse progress(
            String conversationId,
            String userMessageId,
            String progressName,
            String progressMessage) {
        return new ChatStreamResponse(
                "PROGRESS", conversationId, null, userMessageId,
                null, null, null, null, progressName, progressMessage);
    }

    /**
     * 构造模型文本增量事件。
     */
    public static ChatStreamResponse delta(
            String conversationId, String userMessageId, String content) {
        return new ChatStreamResponse(
                "DELTA", conversationId, null, userMessageId,
                null, content, null, null, null, null);
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
                "DONE", conversationId, null, userMessageId,
                assistantMessageId, null, tokenCount, modelName, null, null);
    }
}

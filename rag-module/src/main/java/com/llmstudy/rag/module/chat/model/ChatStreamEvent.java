package com.llmstudy.rag.module.chat.model;

import com.llmstudy.rag.enums.ChatProgressStage;

/**
 * 聊天模块内部流协议，由 Controller 映射为对外 SSE DTO。
 *
 * <p>{@code PROGRESS} 不参与回答正文，也不落库；仅用于流式准备阶段
 * 向客户端推送真实进度。START / DELTA / DONE 的进度字段恒为 null。</p>
 */
public record ChatStreamEvent(Type type, String conversationId,
                              String conversationTitle, String userMessageId,
                              String assistantMessageId, String content,
                              Integer tokenCount, String modelName,
                              // 仅 PROGRESS 有值：阶段枚举名，如 KNOWLEDGE_RETRIEVAL
                              String progressName,
                              // 仅 PROGRESS 有值：后端统一维护的中文文案
                              String progressMessage) {

    /** 流事件类型；PROGRESS 为准备阶段进度，ERROR 为改写失败后的终止事件。 */
    public enum Type { START, PROGRESS, DELTA, DONE, ERROR }

    /** 创建会话已建立的 START 事件；进度字段留空。 */
    public static ChatStreamEvent start(ChatPreparation preparation) {
        return new ChatStreamEvent(Type.START, preparation.conversationId(),
                preparation.conversationTitle(), preparation.userMessageId(),
                null, null, null, null, null, null);
    }

    /**
     * 创建 PROGRESS 事件。
     *
     * <p>从 {@link ChatProgressStage} 同时取出稳定阶段码和中文文案，
     * 保证前后端对阶段语义一致。</p>
     */
    public static ChatStreamEvent progress(ChatPreparation preparation,
                                           ChatProgressStage stage) {
        return new ChatStreamEvent(Type.PROGRESS, preparation.conversationId(), null,
                preparation.userMessageId(), null, null, null, null,
                stage.name(), stage.message());
    }

    /** 创建携带单个模型文本分片的 DELTA 事件；进度字段留空。 */
    public static ChatStreamEvent delta(ChatPreparation preparation, String content) {
        return new ChatStreamEvent(Type.DELTA, preparation.conversationId(), null,
                preparation.userMessageId(), null, content, null, null,
                null, null);
    }

    /** 创建携带助手消息 ID 及模型用量的 DONE 事件；进度字段留空。 */
    public static ChatStreamEvent done(ChatPreparation preparation,
                                       String assistantMessageId,
                                       Integer tokenCount, String modelName) {
        return new ChatStreamEvent(Type.DONE, preparation.conversationId(), null,
                preparation.userMessageId(), assistantMessageId, null,
                tokenCount, modelName, null, null);
    }

    /**
     * 创建 ERROR 事件。
     *
     * <p>查询改写失败时在 START/PROGRESS 之后发送，然后结束流。
     * 不生成助手回答，也不写入虚假引用。content 固定为安全文案。</p>
     */
    public static ChatStreamEvent error(ChatPreparation preparation, String content) {
        return new ChatStreamEvent(Type.ERROR, preparation.conversationId(), null,
                preparation.userMessageId(), null, content, null, null, null, null);
    }
}

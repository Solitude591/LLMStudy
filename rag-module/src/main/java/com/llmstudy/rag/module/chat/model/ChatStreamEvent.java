package com.llmstudy.rag.module.chat.model;

/** 内部流协议，由 Controller 映射为对外 SSE DTO。 */
public record ChatStreamEvent(Type type, String conversationId,
                              String conversationTitle, String userMessageId,
                              String assistantMessageId, String content,
                              Integer tokenCount, String modelName) {

    public enum Type { START, DELTA, DONE }

    /** 创建会话已建立的 START 事件。 */
    public static ChatStreamEvent start(ChatPreparation preparation) {
        return new ChatStreamEvent(Type.START, preparation.conversationId(),
                preparation.conversationTitle(), preparation.userMessageId(),
                null, null, null, null);
    }

    /** 创建携带单个模型文本分片的 DELTA 事件。 */
    public static ChatStreamEvent delta(ChatPreparation preparation, String content) {
        return new ChatStreamEvent(Type.DELTA, preparation.conversationId(), null,
                preparation.userMessageId(), null, content, null, null);
    }

    /** 创建携带助手消息 ID 及模型用量的 DONE 事件。 */
    public static ChatStreamEvent done(ChatPreparation preparation,
                                       String assistantMessageId,
                                       Integer tokenCount, String modelName) {
        return new ChatStreamEvent(Type.DONE, preparation.conversationId(), null,
                preparation.userMessageId(), assistantMessageId, null,
                tokenCount, modelName);
    }
}

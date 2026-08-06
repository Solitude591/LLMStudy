package com.llmstudy.rag.module.chat.model;

import com.llmstudy.rag.module.rag.model.RagReference;
import com.llmstudy.rag.module.llm.model.LlmPrompt;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/** 已完成 Flow 准备的模型调用与持久化上下文。 */
public record ChatPreparation(String conversationId, String conversationTitle,
                              String userMessageId, List<Message> history,
                              LlmPrompt prompt, List<RagReference> ragReferences,
                              String fixedAnswer) {

    public ChatPreparation {
        history = List.copyOf(history);
        ragReferences = ragReferences == null ? List.of() : List.copyOf(ragReferences);
    }

    /** @return 是否应跳过 LLM，直接输出可控固定回答 */
    public boolean fixed() {
        return fixedAnswer != null;
    }
}

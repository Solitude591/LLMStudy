package com.llmstudy.rag.module.chat.flow;

import com.llmstudy.rag.module.chat.model.IntentRecognitionResult;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/** Common 与 RAG 聊天 Flow 共享的输入上下文。 */
public record ChatFlowContext(String query, List<Message> history,
                              IntentRecognitionResult intent) {

    public ChatFlowContext {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("用户问题不能为空");
        }
        history = history == null ? List.of() : List.copyOf(history);
    }
}

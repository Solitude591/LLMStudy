package com.llmstudy.rag.module.chat.flow;

import com.llmstudy.rag.module.chat.model.IntentRecognitionResult;
import com.llmstudy.rag.auth.model.AccessContext;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Common 与 RAG 聊天 Flow 共享的输入上下文。
 *
 * <p>访问上下文和会话历史一起作为不可变数据传递，RAG Flow 可据此过滤文档版本。</p>
 */
public record ChatFlowContext(String query, List<Message> history,
                              IntentRecognitionResult intent,
                              AccessContext accessContext) {

    public ChatFlowContext {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("用户问题不能为空");
        }
        // 防御性复制，避免异步生成期间外部线程继续修改历史消息列表。
        history = history == null ? List.of() : List.copyOf(history);
    }

    /**
     * 无鉴权上下文的兼容构造器，仅供不执行受保护检索的旧调用或单元测试使用。
     */
    public ChatFlowContext(String query, List<Message> history,
                           IntentRecognitionResult intent) {
        this(query, history, intent, null);
    }
}

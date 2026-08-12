package com.llmstudy.rag.module.chat.flow;

import com.llmstudy.rag.enums.ChatProgressStage;
import com.llmstudy.rag.module.llm.model.LlmPrompt;
import com.llmstudy.rag.module.rag.model.RagReference;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.function.Consumer;

/** 普通聊天与 RAG 聊天共用的准备阶段协议。 */
public interface ChatFlow {

    /**
     * 将用户问题、历史消息和意图结果转换为模型可执行的输入。
     *
     * @param context Flow 共享上下文
     * @return 提示词、改写问题、RAG 引用或固定回答
     */
    FlowPreparation prepare(ChatFlowContext context);

    /**
     * 带进度回调的准备入口。
     *
     * <p>默认实现忽略 {@code progress}，直接委托无回调版本，因此
     * {@code CommonChatFlow} 等无需改造。需要上报阶段的实现
     * （如 {@code RagChatFlow}）应覆盖本方法。</p>
     *
     * @param context  Flow 共享上下文
     * @param progress 阶段开始时调用的回调；调用方可传空操作 Consumer
     */
    default FlowPreparation prepare(ChatFlowContext context,
                                    Consumer<ChatProgressStage> progress) {
        return prepare(context);
    }

    /** 不含意图的兼容入口，主要供普通聊天和既有调用方使用。 */
    default FlowPreparation prepare(String query, List<Message> history) {
        return prepare(new ChatFlowContext(query, history, null));
    }

    /** Flow 阶段产生的 Prompt、改写结果、引用或固定回答。 */
    record FlowPreparation(LlmPrompt prompt, String rewrittenQuery,
                           List<RagReference> references, String fixedAnswer) {
        public FlowPreparation {
            references = references == null ? List.of() : List.copyOf(references);
        }
    }
}

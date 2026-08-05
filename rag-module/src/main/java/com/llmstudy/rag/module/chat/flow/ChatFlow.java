package com.llmstudy.rag.module.chat.flow;

import com.llmstudy.rag.module.rag.model.RagReference;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/** 普通聊天与 RAG 聊天共用的准备阶段协议。 */
public interface ChatFlow {

    /**
     * 将用户问题、历史消息和意图结果转换为模型可执行的输入。
     *
     * @param context Flow 共享上下文
     * @return 提示词、改写问题、RAG 引用或固定回答
     */
    FlowPreparation prepare(ChatFlowContext context);

    /** 不含意图的兼容入口，主要供普通聊天和既有调用方使用。 */
    default FlowPreparation prepare(String query, List<Message> history) {
        return prepare(new ChatFlowContext(query, history, null));
    }

    /** Flow 阶段产生的 Prompt、改写结果、引用或固定回答。 */
    record FlowPreparation(String prompt, String rewrittenQuery,
                           List<RagReference> references, String fixedAnswer) {
        public FlowPreparation {
            references = references == null ? List.of() : List.copyOf(references);
        }
    }
}

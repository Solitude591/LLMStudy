package com.llmstudy.rag.module.chat.flow;

import com.llmstudy.rag.module.llm.model.LlmPrompt;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

/** 普通聊天 Flow：不改写问题、不执行检索，直接使用用户原问题。 */
@Component
public class CommonChatFlow implements ChatFlow {

    /** {@inheritDoc} */
    @Override
    public FlowPreparation prepare(ChatFlowContext context) {
        return new FlowPreparation(LlmPrompt.userOnly(context.query()),
                null, List.of(), null);
    }
}

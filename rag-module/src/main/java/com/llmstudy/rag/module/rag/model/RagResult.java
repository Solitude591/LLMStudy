package com.llmstudy.rag.module.rag.model;

import com.llmstudy.rag.module.llm.model.LlmPrompt;

import java.util.List;

/** 在线 RAG Pipeline 的最终输出。 */
public record RagResult(LlmPrompt prompt, RewrittenQuery rewrittenQuery,
                        List<RagReference> references) {

    public RagResult {
        references = references == null ? List.of() : List.copyOf(references);
    }

    /** @return 是否没有找到可支撑回答的知识库引用 */
    public boolean empty() {
        return references.isEmpty();
    }
}

package com.llmstudy.rag.module.rag.model;

import com.llmstudy.rag.module.llm.model.LlmPrompt;

import java.util.List;

/** 在线 RAG Pipeline 的最终输出。 */
public record RagResult(LlmPrompt prompt, RewrittenQuery rewrittenQuery,
                        List<RagReference> references, List<String> chunks) {

    public RagResult {
        references = references == null ? List.of() : List.copyOf(references);
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }

    /** @return 是否没有找到可支撑回答的知识库引用 */
    public boolean empty() {
        return references.isEmpty();
    }
}

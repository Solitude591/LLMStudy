package com.llmstudy.rag.module.rag.query;

import com.llmstudy.rag.module.rag.model.RagRequest;
import com.llmstudy.rag.module.rag.model.RewrittenQuery;

/** 将当前问题与会话上下文改写为更适合语义检索的独立查询。 */
public interface QueryRewriter {

    /**
     * @param request RAG 原始请求
     * @return 同时保留原问题与改写问题的结果
     */
    RewrittenQuery rewrite(RagRequest request);
}

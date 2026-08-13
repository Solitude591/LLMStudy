package com.llmstudy.rag.module.rag.query;

import com.llmstudy.rag.module.rag.model.RagRequest;
import com.llmstudy.rag.module.rag.model.RetrievalQueryPlan;

/** 将当前问题与会话上下文改写为中英文独立检索查询。 */
public interface QueryRewriter {

    /**
     * @param request RAG 原始请求
     * @return 原问题 + 中文独立查询 + 英文独立查询
     * @throws QueryRewriteException 模型调用、空响应、JSON 非法或校验失败
     */
    RetrievalQueryPlan rewrite(RagRequest request);
}

package com.llmstudy.rag.module.rag.rerank;

import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import com.llmstudy.rag.module.rag.model.RetrievalQueryPlan;

import java.util.List;

/** 对融合后候选做跨编码相关性重排的端口。 */
public interface CandidateReranker {

    /**
     * @param plan       中英文独立查询；按候选文档语言选择实际 query
     * @param candidates parent 分组后的代表 child
     * @return 结构化重排结果；失败时 {@code used=false} 且带稳定 reason，不中断问答
     */
    RerankResult rerank(RetrievalQueryPlan plan, List<RetrievalCandidate> candidates);
}

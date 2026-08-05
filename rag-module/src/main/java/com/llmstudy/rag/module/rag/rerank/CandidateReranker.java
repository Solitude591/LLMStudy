package com.llmstudy.rag.module.rag.rerank;

import com.llmstudy.rag.module.rag.model.RetrievalCandidate;

import java.util.List;

/** 对融合后候选做跨编码相关性重排的端口。 */
public interface CandidateReranker {

    /**
     * @param question   用于评分的用户原问题
     * @param candidates 已按 RRF 或单通道分数排序的候选
     * @return 重排后候选；禁用或失败时应保留输入顺序
     */
    List<RetrievalCandidate> rerank(String question, List<RetrievalCandidate> candidates);
}

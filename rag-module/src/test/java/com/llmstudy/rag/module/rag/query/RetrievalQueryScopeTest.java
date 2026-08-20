package com.llmstudy.rag.module.rag.query;

import com.llmstudy.rag.module.rag.model.RetrievalQueryPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalQueryScopeTest {

    @Test
    void recognizesMultiTaskCrossPaperAndSynthesisQuestions() {
        assertTrue(scope("DAT 对三类任务的作用是否一致？").comprehensive());
        assertTrue(scope("三篇论文分别如何应对显存限制？").crossDocument());
        assertTrue(scope("系统概括 nnU-Net 的完整流程").comprehensive());
    }

    @Test
    void keepsMultiFieldFactQuestionOnStandardBudget() {
        RetrievalQueryScope scope = scope("LoRA 的秩和缩放因子分别是多少？");

        assertFalse(scope.comprehensive());
        assertFalse(scope.crossDocument());
    }

    private static RetrievalQueryScope scope(String question) {
        return RetrievalQueryScope.from(
                new RetrievalQueryPlan(question, question, "factual query"));
    }
}

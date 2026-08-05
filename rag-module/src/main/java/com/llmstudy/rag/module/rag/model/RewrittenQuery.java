package com.llmstudy.rag.module.rag.model;

/** 将原问题与改写问题显式保存为业务数据，不借用框架 metadata 传递。 */
public record RewrittenQuery(String originalQuestion, String rewrittenQuestion) {

    public RewrittenQuery {
        if (originalQuestion == null || originalQuestion.isBlank()
                || rewrittenQuestion == null || rewrittenQuestion.isBlank()) {
            throw new IllegalArgumentException("原问题和改写问题不能为空");
        }
        originalQuestion = originalQuestion.trim();
        rewrittenQuestion = rewrittenQuestion.trim();
    }
}

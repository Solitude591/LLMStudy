package com.llmstudy.rag.module.rag.model;

/** 仅传递给检索后 Prompt 阶段的回答策略与焦点数据。 */
public record RagIntentContext(RagAnswerMode answerMode,
                               RagFocusInformation focusInformation) {

    public RagIntentContext {
        answerMode = answerMode == null ? RagAnswerMode.GENERIC : answerMode;
        focusInformation = focusInformation == null
                ? RagFocusInformation.empty() : focusInformation;
    }

    /** @return 使用通用回答模板且不含额外焦点的上下文 */
    public static RagIntentContext generic() {
        return new RagIntentContext(RagAnswerMode.GENERIC, RagFocusInformation.empty());
    }
}

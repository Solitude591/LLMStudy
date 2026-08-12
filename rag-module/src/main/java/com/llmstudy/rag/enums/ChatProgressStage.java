package com.llmstudy.rag.enums;

/**
 * 流式聊天对外可见的进度阶段。
 *
 * <p>阶段代码（枚举名）和中文文案都由后端统一维护，前端只展示
 * {@link #message()}，不硬编码文案。进度表示「该阶段已经开始」，
 * 不提供百分比或预计耗时。</p>
 *
 * <p>典型顺序：
 * <ul>
 *   <li>RAG：INTENT → QUESTION → KNOWLEDGE → EVIDENCE → ANSWER</li>
 *   <li>普通聊天：INTENT → ANSWER（跳过知识库相关阶段）</li>
 * </ul>
 * </p>
 */
public enum ChatProgressStage {

    /** 调用意图识别模型之前。 */
    INTENT_RECOGNITION("正在识别您的意图…"),

    /** 查询改写 / 问题理解开始之前（仅 RAG）。 */
    QUESTION_ANALYSIS("正在理解您的问题…"),

    /** 混合检索开始之前（仅 RAG）。 */
    KNOWLEDGE_RETRIEVAL("正在查询知识库…"),

    /** RRF / 重排 / 父片展开 / Prompt 注入开始之前（仅 RAG）。 */
    EVIDENCE_ORGANIZATION("正在整理相关资料…"),

    /** Flow 准备完成、即将调用最终回答模型之前。 */
    ANSWER_GENERATION("正在生成回答…");

    /** 直接下发给前端的中文提示文案。 */
    private final String message;

    ChatProgressStage(String message) {
        this.message = message;
    }

    /** @return 该阶段对应的用户可读文案 */
    public String message() {
        return message;
    }
}

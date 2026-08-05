package com.llmstudy.rag.module.chat.model;

/**
 * LLM 结构化意图识别结果，同时作为消息 metadata 中的路由审计数据。
 */
public record IntentRecognitionResult(
        boolean related,
        ChatIntent intent,
        String reason,
        IntentKeyInformation keyInformation,
        boolean fallback) {

    /**
     * 规范化模型结果；相关性最终由强类型意图决定，避免模型输出自相矛盾。
     */
    public IntentRecognitionResult normalized() {
        if (intent == null || intent == ChatIntent.UNKNOWN) {
            return fallback("意图识别结果缺少有效分类");
        }
        String normalizedReason = reason == null || reason.isBlank()
                ? "模型未提供分类理由"
                : reason.trim();
        IntentKeyInformation normalizedInformation = keyInformation == null
                ? IntentKeyInformation.empty()
                : keyInformation.normalized();
        return new IntentRecognitionResult(
                intent.isKnowledgeBaseRelated(),
                intent,
                normalizedReason,
                normalizedInformation,
                false);
    }

    /** 识别不可用时保守进入论文 RAG 分支。 */
    public static IntentRecognitionResult fallback(String reason) {
        String message = reason == null || reason.isBlank()
                ? "意图识别不可用，保守进入 RAG"
                : reason.trim();
        return new IntentRecognitionResult(
                true,
                ChatIntent.UNKNOWN,
                message,
                IntentKeyInformation.empty(),
                true);
    }
}

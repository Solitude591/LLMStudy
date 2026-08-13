package com.llmstudy.rag.dto;

/**
 * 检索诊断 HTTP 入参。
 *
 * @param query                用户原问题
 * @param conversationContext  会话上下文；空则按「无」处理
 * @param includeText          true 返回完整正文，false 或省略时截断为 300 字预览
 */
public record RetrievalDiagnoseRequest(
        String query, String conversationContext, Boolean includeText) {
}

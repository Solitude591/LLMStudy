package com.llmstudy.rag.dto;

/**
 * RAGAS 评估数据集生成请求。
 *
 * @param query 用户原始问题；不允许为 null、空字符串或全空白（由 Controller/Service 校验）
 */
public record DatasetGenerateRequest(String query) {

    public DatasetGenerateRequest {
        query = query == null ? null : query.trim();
    }
}

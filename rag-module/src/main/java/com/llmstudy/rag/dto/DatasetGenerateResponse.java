package com.llmstudy.rag.dto;

import java.util.List;
import com.llmstudy.rag.module.rag.model.RetrievalDiagnoseResponse;

/**
 * RAGAS 评估数据集生成结果。
 *
 * @param query    用户提交的原始问题
 * @param response AI 生成的完整回答
 * @param chunks   最终参与回答生成的 chunk 正文列表（无引用 metadata）
 */
public record DatasetGenerateResponse(
        String query,
        String response,
        List<String> chunks,
        RetrievalDiagnoseResponse retrievalDiagnostics) {

    public DatasetGenerateResponse(String query, String response, List<String> chunks) {
        this(query, response, chunks, null);
    }

    public DatasetGenerateResponse {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }
}

package com.llmstudy.rag.module.rag.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** 与 LangChain4j/Spring AI 解耦的检索候选。 */
public record RetrievalCandidate(String id, String text, Map<String, Object> metadata,
                                 double score, Double rerankedScore) {

    public RetrievalCandidate {
        if (id == null || id.isBlank() || text == null || text.isBlank()) {
            throw new IllegalArgumentException("候选 ID 和文本不能为空");
        }
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /** 创建保留原检索分数、同时记录重排分数的新候选。 */
    public RetrievalCandidate withRerankedScore(double value) {
        return new RetrievalCandidate(id, text, metadata, score, value);
    }
}

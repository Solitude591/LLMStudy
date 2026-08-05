package com.llmstudy.rag.module.knowledge.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** 知识分片器生成的框架无关业务分片。 */
public record KnowledgeChunk(String chunkId, String text, Map<String, Object> metadata,
                             boolean skipEmbedding) {

    public KnowledgeChunk {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException("chunkId 不能为空");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("分片文本不能为空");
        }
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}

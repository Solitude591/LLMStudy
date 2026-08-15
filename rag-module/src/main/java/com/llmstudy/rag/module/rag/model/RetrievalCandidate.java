package com.llmstudy.rag.module.rag.model;

import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 与 LangChain4j/Spring AI 解耦的检索候选。
 *
 * <p>raw / RRF / BGE / final 使用独立字段，禁止用一个 {@code score} 串改语义。
 * 各召回路名次不挂在候选上：诊断接口按各阶段列表下标还原 rank。</p>
 */
public record RetrievalCandidate(
        String id, String text, Map<String, Object> metadata,
        double rawScore, Double rrfScore, Double bgeScore, Double finalScore) {

    public RetrievalCandidate {
        if (id == null || id.isBlank() || text == null || text.isBlank()) {
            throw new IllegalArgumentException("候选 ID 和文本不能为空");
        }
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /**
     * 兼容旧构造：原始检索分 + 可选 BGE 分。
     *
     * <p>单路命中和既有单测仍走这个 5 参形式；RRF/final 在后续 wither 里补。</p>
     */
    public RetrievalCandidate(String id, String text, Map<String, Object> metadata,
                              double rawScore, Double bgeScore) {
        this(id, text, metadata, rawScore, null, bgeScore, null);
    }

    /**
     * parent 分组键：有 {@code parent_chunk_id} 用 parent，否则用自身 chunk ID。
     *
     * <p>图片/表格 standalone 没有父片，会以自身 ID 成组，不会和正文 child 挤在一起。</p>
     */
    public String groupingKey() {
        Object parent = metadata.get(SegmentMetadataKeys.PARENT_CHUNK_ID);
        String parentId = parent == null ? null : parent.toString().trim();
        return parentId == null || parentId.isBlank() ? id : parentId;
    }

    /**
     * 写入引用结构的融合分：优先 RRF，单通道降级或尚未融合时回退 raw。
     *
     * <p>对外 {@link RagReference#score()} 契约不变，避免改聊天落库 JSON。</p>
     */
    public double citationScore() {
        return rrfScore != null ? rrfScore : rawScore;
    }

    public RetrievalCandidate withRrfScore(double value) {
        return new RetrievalCandidate(id, text, metadata, rawScore, value, bgeScore, finalScore);
    }

    public RetrievalCandidate withBgeScore(double value) {
        return new RetrievalCandidate(id, text, metadata, rawScore, rrfScore, value, finalScore);
    }

    public RetrievalCandidate withFinalScore(double value) {
        return new RetrievalCandidate(id, text, metadata, rawScore, rrfScore, bgeScore, value);
    }

    /** parent 展开时替换 id/正文/metadata，保留已经算好的各阶段分数。 */
    public RetrievalCandidate withIdentity(String newId, String newText,
                                           Map<String, Object> newMetadata) {
        return new RetrievalCandidate(
                newId, newText, newMetadata, rawScore, rrfScore, bgeScore, finalScore);
    }
}

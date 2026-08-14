package com.llmstudy.rag.module.rag.retrieval;

import com.llmstudy.rag.entity.KnowledgeSegment;
import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import com.llmstudy.rag.module.knowledge.model.SegmentMetadataMaps;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将正文 child 展开为 parent。
 *
 * <p>图片/表格 standalone 没有 {@code parent_chunk_id}，不会进入展开。
 * 多个 child 指向同一 parent 时只保留排名更靠前的一条。
 * 最终 Top-N 的补位循环使用 {@link #expandOne}，以便去重后继续往后取。</p>
 */
@Component
public class ParentChunkExpander {

    private static final Logger log = LoggerFactory.getLogger(ParentChunkExpander.class);

    private final ParentChunkResolver parentResolver;
    private final JsonMapper jsonMapper;

    public ParentChunkExpander(ParentChunkResolver parentResolver, JsonMapper jsonMapper) {
        this.parentResolver = parentResolver;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 按输入顺序展开并按输出 id 去重。
     *
     * @param candidates 已按最终排序的候选（通常含 child/standalone）
     * @return 展开后的列表；回查失败时保留原 child
     */
    public List<RetrievalCandidate> expand(List<RetrievalCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, KnowledgeSegment> requestCache = new HashMap<>();
        Set<String> emitted = new HashSet<>();
        List<RetrievalCandidate> expanded = new ArrayList<>(candidates.size());
        for (RetrievalCandidate candidate : candidates) {
            RetrievalCandidate output = expandOne(candidate, requestCache);
            if (emitted.add(output.id())) {
                expanded.add(output);
            }
        }
        return expanded;
    }

    /**
     * 展开单个候选，不去重。
     *
     * <p>请求级 {@code cache} 避免同一 parent 被多个 child 命中时重复打 Redis/MySQL。
     * 调用方负责按输出 id 去重和补位。</p>
     *
     * @param candidate 代表 child 或 standalone
     * @param cache     本次请求内的 parent 缓存，可跨多次 expandOne 复用
     * @return parent 候选，或回查失败/无父片时的原 candidate
     */
    public RetrievalCandidate expandOne(RetrievalCandidate candidate,
                                        Map<String, KnowledgeSegment> cache) {
        Map<String, Object> metadata = new LinkedHashMap<>(candidate.metadata());
        Object parentValue = metadata.get(SegmentMetadataKeys.PARENT_CHUNK_ID);
        String parentId = parentValue == null ? null : parentValue.toString().trim();
        if (parentId == null || parentId.isBlank()) {
            return candidate;
        }
        KnowledgeSegment parent = parentResolver.resolve(parentId, cache);
        if (parent == null || parent.getText() == null || parent.getText().isBlank()) {
            log.warn("父分片回查失败，保留 child: chunkId={}, parentChunkId={}",
                    candidate.id(), parentId);
            return candidate;
        }
        Map<String, Object> parentMetadata =
                SegmentMetadataMaps.parse(parent.getMetadata(), jsonMapper);
        Map<String, Object> merged = new LinkedHashMap<>();
        // 版本/来源以检索候选（ES）为准，避免被 parent 行上的历史空值覆盖。
        SegmentMetadataMaps.copyString(metadata, merged, SegmentMetadataKeys.DOC_ID);
        SegmentMetadataMaps.copyString(metadata, merged, SegmentMetadataKeys.VERSION_ID);
        SegmentMetadataMaps.copyString(metadata, merged, SegmentMetadataKeys.SOURCE_URL);
        SegmentMetadataMaps.copyString(metadata, merged, SegmentMetadataKeys.LANGUAGE);
        if (parent.getDocId() != null && !merged.containsKey(SegmentMetadataKeys.DOC_ID)) {
            merged.put(SegmentMetadataKeys.DOC_ID, parent.getDocId());
        }
        // 章节与页码改用 parent 全章范围。
        SegmentMetadataMaps.copyString(parentMetadata, merged, SegmentMetadataKeys.HEADER_PATH);
        SegmentMetadataMaps.copyPositiveInt(
                parentMetadata, merged, SegmentMetadataKeys.PAGE_START);
        SegmentMetadataMaps.copyPositiveInt(
                parentMetadata, merged, SegmentMetadataKeys.PAGE_END);
        return candidate.withIdentity(parent.getChunkId(), parent.getText(), merged);
    }
}

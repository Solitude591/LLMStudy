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
 * 在融合与重排之后，将正文 child 统一展开为 parent。
 *
 * <p>图片/表格 standalone 没有 {@code parent_chunk_id}，不会进入展开。
 * 多个 child 指向同一 parent 时只保留排名更靠前的一条。</p>
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
     * @param candidates 已按最终排序的候选（通常含 child/standalone）
     * @return 展开并按 id 去重后的列表；回查失败时保留原 child
     */
    public List<RetrievalCandidate> expand(List<RetrievalCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        // 请求级缓存：同一 parent 被多个 child 命中时只查一次 Redis/MySQL。
        Map<String, KnowledgeSegment> requestCache = new HashMap<>();
        Set<String> emitted = new HashSet<>();
        List<RetrievalCandidate> expanded = new ArrayList<>(candidates.size());

        for (RetrievalCandidate candidate : candidates) {
            Map<String, Object> metadata = new LinkedHashMap<>(candidate.metadata());
            Object parentValue = metadata.get(SegmentMetadataKeys.PARENT_CHUNK_ID);
            String parentId = parentValue == null ? null : parentValue.toString().trim();

            // standalone / 无父片：原样保留，仅按候选 id 去重。
            if (parentId == null || parentId.isBlank()) {
                if (emitted.add(candidate.id())) {
                    expanded.add(candidate);
                }
                continue;
            }

            KnowledgeSegment parent = parentResolver.resolve(parentId, requestCache);
            if (parent == null || parent.getText() == null || parent.getText().isBlank()) {
                log.warn("父分片回查失败，保留 child: chunkId={}, parentChunkId={}",
                        candidate.id(), parentId);
                if (emitted.add(candidate.id())) {
                    expanded.add(candidate);
                }
                continue;
            }
            // 已展开过同一 parent：丢弃后续更低分 child，避免 Prompt 重复大段。
            if (!emitted.add(parent.getChunkId())) {
                continue;
            }

            Map<String, Object> parentMetadata =
                    SegmentMetadataMaps.parse(parent.getMetadata(), jsonMapper);
            Map<String, Object> merged = new LinkedHashMap<>();
            // 版本/来源以检索候选（ES）为准，避免被 parent 行上的历史空值覆盖。
            SegmentMetadataMaps.copyString(metadata, merged, SegmentMetadataKeys.DOC_ID);
            SegmentMetadataMaps.copyString(metadata, merged, SegmentMetadataKeys.VERSION_ID);
            SegmentMetadataMaps.copyString(metadata, merged, SegmentMetadataKeys.SOURCE_URL);
            if (parent.getDocId() != null && !merged.containsKey(SegmentMetadataKeys.DOC_ID)) {
                merged.put(SegmentMetadataKeys.DOC_ID, parent.getDocId());
            }
            // 章节与页码改用 parent 全章范围。
            SegmentMetadataMaps.copyString(parentMetadata, merged, SegmentMetadataKeys.HEADER_PATH);
            SegmentMetadataMaps.copyPositiveInt(
                    parentMetadata, merged, SegmentMetadataKeys.PAGE_START);
            SegmentMetadataMaps.copyPositiveInt(
                    parentMetadata, merged, SegmentMetadataKeys.PAGE_END);

            expanded.add(new RetrievalCandidate(
                    parent.getChunkId(),
                    parent.getText(),
                    merged,
                    candidate.score(),
                    candidate.rerankedScore()));
        }
        return expanded;
    }
}

package com.llmstudy.rag.module.knowledge.ingestion.chunk;

import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 构建精简后的 MySQL segment metadata。
 *
 * <p>契约只允许 parent_chunk_id / header_path / page_start / page_end；
 * 缺省字段不写空串，便于序列化为 {@code {}}。</p>
 */
final class ChunkMetadataFactory {

    private ChunkMetadataFactory() {
    }

    /**
     * @param parentChunkId 仅 child 传入；standalone/parent 传 null
     * @param headerPath    无标题时传空或 null，将省略该键
     * @param pageStart     与 pageEnd 同时为正才写入；AST fallback 通常为 null
     */
    static Map<String, Object> create(String parentChunkId,
                                      String headerPath,
                                      Integer pageStart,
                                      Integer pageEnd) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (parentChunkId != null && !parentChunkId.isBlank()) {
            metadata.put(SegmentMetadataKeys.PARENT_CHUNK_ID, parentChunkId);
        }
        if (headerPath != null && !headerPath.isBlank()) {
            metadata.put(SegmentMetadataKeys.HEADER_PATH, headerPath);
        }
        // 页码成对出现，避免只写一端导致引用展示歧义。
        if (pageStart != null && pageEnd != null && pageStart > 0 && pageEnd > 0) {
            metadata.put(SegmentMetadataKeys.PAGE_START, pageStart);
            metadata.put(SegmentMetadataKeys.PAGE_END, pageEnd);
        }
        return metadata;
    }

    /**
     * MinerU {@code page_idx} 为 0-based；对外引用使用 1-based 页码。
     *
     * @return 非法输入返回 null，调用方应省略页码字段而非写 0
     */
    static Integer toUserPage(Integer pageIdx) {
        if (pageIdx == null || pageIdx < 0) {
            return null;
        }
        return pageIdx + 1;
    }
}

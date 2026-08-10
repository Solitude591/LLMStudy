package com.llmstudy.rag.module.knowledge.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Segment metadata Map 读写的公共小工具。
 *
 * <p>Indexer 与 Parent 展开都需要「有值才拷贝 / 解析 JSON」，集中在此处避免两套拷贝逻辑漂移。</p>
 */
public final class SegmentMetadataMaps {

    private static final Logger log = LoggerFactory.getLogger(SegmentMetadataMaps.class);

    private SegmentMetadataMaps() {
    }

    /**
     * 将 MySQL 中的 metadata JSON 解析为可变 Map。
     *
     * @param json       可能为空或损坏的 JSON
     * @param jsonMapper 项目统一的 Jackson mapper
     * @return 永不为 null；解析失败时返回空 Map，由调用方降级处理
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String json, JsonMapper jsonMapper) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(jsonMapper.readValue(json, Map.class));
        } catch (Exception e) {
            log.warn("metadata JSON 解析失败，使用空 metadata", e);
            return new LinkedHashMap<>();
        }
    }

    /**
     * 将非空白字符串值拷贝到目标 Map（统一 toString，兼容历史脏类型）。
     */
    public static void copyString(Map<String, Object> source,
                                  Map<String, Object> target,
                                  String key) {
        Object value = source.get(key);
        if (value != null && !value.toString().isBlank()) {
            target.put(key, value.toString());
        }
    }

    /**
     * 将正整数页码拷贝到目标 Map；非法或非正数直接跳过，不阻断主流程。
     */
    public static void copyPositiveInt(Map<String, Object> source,
                                       Map<String, Object> target,
                                       String key) {
        Object value = source.get(key);
        if (value instanceof Number number) {
            int page = number.intValue();
            if (page > 0) {
                target.put(key, page);
            }
            return;
        }
        if (value == null || value.toString().isBlank()) {
            return;
        }
        try {
            int page = Integer.parseInt(value.toString().trim());
            if (page > 0) {
                target.put(key, page);
            }
        } catch (NumberFormatException ignored) {
            // 历史脏数据忽略
        }
    }
}

package com.llmstudy.rag.enums;

import java.util.Arrays;

/**
 * knowledge_segment.status 对应的分片处理状态。
 */
public enum SegmentStatus {

    /** 分片已经保存到 MySQL，尚未生成向量。 */
    INIT("INIT"),

    /** 分片已经完成向量化并写入向量存储。 */
    VECTOR_STORED("VECTOR_STORED");

    private final String value;

    SegmentStatus(String value) {
        this.value = value;
    }

    /**
     * 获取持久化到 MySQL 的状态值。
     */
    public String value() {
        return value;
    }

    public static SegmentStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知分片状态: " + value));
    }
}

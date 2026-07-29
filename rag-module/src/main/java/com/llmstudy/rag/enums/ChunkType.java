package com.llmstudy.rag.enums;

import java.util.Arrays;

/**
 * 父子分片中的节点类型。
 */
public enum ChunkType {

    /** 未超过大小限制，可以直接向量化的独立分片。 */
    STANDALONE("standalone", false),

    /** 保存完整章节上下文的父分片，本身不参与向量化。 */
    PARENT("parent", true),

    /** 超限父分片二次切割出的检索分片。 */
    CHILD("child", false);

    private final String value;
    private final boolean skipEmbedding;

    ChunkType(String value, boolean skipEmbedding) {
        this.value = value;
        this.skipEmbedding = skipEmbedding;
    }

    /**
     * 获取写入 metadata/数据库 JSON 的稳定字符串值。
     */
    public String value() {
        return value;
    }

    public boolean shouldSkipEmbedding() {
        return skipEmbedding;
    }

    public static ChunkType fromValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知分片类型: " + value));
    }
}

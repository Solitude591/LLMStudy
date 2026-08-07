package com.llmstudy.rag.enums;

import java.util.Arrays;

/**
 * knowledge_document_version.processing_status 对应的文档处理生命周期状态。
 *
 * <p>枚举 value 与数据库统一使用大写字符串。</p>
 */
public enum DocumentStatus {

    INIT("INIT"),
    UPLOADED("UPLOADED"),
    IMPORTING("IMPORTING"),
    IMPORTED("IMPORTED"),
    CONVERTING("CONVERTING"),
    CONVERTED("CONVERTED"),
    SPLITTING("SPLITTING"),
    CHUNKED("CHUNKED"),
    VECTORING("VECTORING"),
    VECTOR_STORED("VECTOR_STORED");

    private final String value;

    DocumentStatus(String value) {
        this.value = value;
    }

    /**
     * 获取持久化到 MySQL 的状态值。
     */
    public String value() {
        return value;
    }

    /**
     * 兼容数据库实体和接口 DTO 暂时仍以 String 暴露状态的场景。
     */
    public boolean matches(String statusValue) {
        return statusValue != null && value.equalsIgnoreCase(statusValue);
    }

    public static DocumentStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知文档状态: " + value));
    }
}

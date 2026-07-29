package com.llmstudy.rag.enums;

import java.util.Arrays;

/**
 * knowledge_document.doc_status 对应的文档生命周期状态。
 *
 * <p>枚举常量使用 Java 大写命名，value 保持数据库现有的小写字符串，
 * 因此引入枚举不需要修改表结构或迁移历史数据。</p>
 */
public enum DocumentStatus {

    INIT("init"),
    UPLOADED("uploaded"),
    CONVERTING("converting"),
    CONVERTED("converted"),
    CHUNKED("chunked"),
    VECTOR_STORED("vector_stored");

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

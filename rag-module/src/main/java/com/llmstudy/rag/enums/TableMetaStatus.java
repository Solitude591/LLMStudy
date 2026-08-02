package com.llmstudy.rag.enums;

import java.util.Arrays;

/**
 * table_meta.status 对应的 Excel 表元数据状态。
 *
 * <p>枚举 value 与数据库统一使用大写字符串。</p>
 */
public enum TableMetaStatus {

    /** 表名已预留，物理表创建或数据导入尚未完成。 */
    CREATING("CREATING"),

    /** Sheet 数据已全部导入物理表，元数据状态落定。 */
    IMPORTED("IMPORTED");

    private final String value;

    TableMetaStatus(String value) {
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

    public static TableMetaStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知表元数据状态: " + value));
    }
}

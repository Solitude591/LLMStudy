package com.llmstudy.rag.enums;

import java.util.Locale;

/** 文档物理版本的发布生命周期。 */
public enum DocumentReleaseStatus {

    PREPARING,
    READY,
    PUBLISHING,
    PUBLISHED,
    ARCHIVED;

    public String value() {
        return name();
    }

    public static DocumentReleaseStatus fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("文档版本发布状态不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的文档版本发布状态: " + value, e);
        }
    }
}

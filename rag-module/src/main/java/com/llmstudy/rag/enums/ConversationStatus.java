package com.llmstudy.rag.enums;

import java.util.Arrays;

/**
 * chat_conversation.status 对应的会话状态。
 */
public enum ConversationStatus {

    /** 正在使用的会话。 */
    ACTIVE("ACTIVE"),

    /** 用户已归档的会话。 */
    ARCHIVED("ARCHIVED"),

    /** 已逻辑删除的会话。 */
    DELETED("DELETED");

    private final String value;

    ConversationStatus(String value) {
        this.value = value;
    }

    /**
     * 获取持久化到 MySQL 的状态值。
     */
    public String value() {
        return value;
    }

    public boolean matches(String statusValue) {
        return statusValue != null && value.equalsIgnoreCase(statusValue);
    }

    public static ConversationStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知会话状态: " + value));
    }
}

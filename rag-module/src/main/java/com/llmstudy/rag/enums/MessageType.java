package com.llmstudy.rag.enums;

import java.util.Arrays;

/**
 * chat_message.type 对应的消息类型。
 */
public enum MessageType {

    /** 系统指令消息。 */
    SYSTEM("SYSTEM"),

    /** 用户输入消息。 */
    USER("USER"),

    /** AI 助手回复消息。 */
    ASSISTANT("ASSISTANT"),

    /** 工具调用或工具返回消息。 */
    TOOL("TOOL");

    private final String value;

    MessageType(String value) {
        this.value = value;
    }

    /**
     * 获取持久化到 MySQL 的类型值。
     */
    public String value() {
        return value;
    }

    public boolean matches(String typeValue) {
        return typeValue != null && value.equalsIgnoreCase(typeValue);
    }

    public static MessageType fromValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知消息类型: " + value));
    }
}

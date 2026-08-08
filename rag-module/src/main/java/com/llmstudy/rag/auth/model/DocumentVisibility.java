package com.llmstudy.rag.auth.model;

import java.util.Locale;

/**
 * 文档可见范围。
 *
 * <p>该枚举只描述文档的共享范围，最终读写权限由 {@code DocumentAccessPolicy}
 * 结合当前用户身份统一判断。</p>
 */
public enum DocumentVisibility {
    /** 仅文档所有者和系统管理员可访问。 */
    PRIVATE,
    /** 同组织用户可读，所有者、本组织管理员和系统管理员可写。 */
    ORGANIZATION,
    /** 所有已登录用户可读，所有者和系统管理员可写。 */
    PUBLIC;

    /**
     * 将 HTTP 参数或旧版可见范围值转换为标准枚举。
     *
     * @param value 前端传入的可见范围；空值默认按私有文档处理
     * @return 规范化后的可见范围
     * @throws IllegalArgumentException 值不属于支持的三种范围时抛出
     */
    public static DocumentVisibility from(String value) {
        if (value == null || value.isBlank()) {
            // 默认私有可以避免调用方漏传参数时意外扩大文档可见范围。
            return PRIVATE;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("INTERNAL".equals(normalized)) {
            // 兼容旧页面曾使用的 INTERNAL 值，统一迁移为组织可见。
            normalized = "ORGANIZATION";
        }
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "visibility 只能是 PRIVATE、ORGANIZATION 或 PUBLIC");
        }
    }
}

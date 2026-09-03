package com.llmstudy.rag.module.rag.query;

import java.util.Locale;

/**
 * 改写模型为当前问题选择的检索策略。
 *
 * <p>策略只决定<b>额外</b>生成哪种检索文本，中英文独立查询在四种策略下都会产出，
 * 因此任何一种策略失效都不会让检索退化为空。</p>
 */
public enum QueryRewriteStrategy {

    /** 问题已经自足，只用中英文独立查询。 */
    DIRECT,

    /** 短查询或表述单一：补几个同义改写，扩大词面覆盖。 */
    MULTI_QUERY,

    /** 事实/数值类问题：生成一段假设答案，用答案而不是问句去匹配正文。 */
    HYDE,

    /** 多跳或跨论文比较：拆成可独立检索的子问题。 */
    DECOMPOSE;

    /**
     * 解析模型返回的策略名。
     *
     * <p>模型输出不是信任边界：空值、未知名称一律退回 {@link #DIRECT}，
     * 而不是让整次改写失败。</p>
     */
    public static QueryRewriteStrategy fromValue(String value) {
        if (value == null || value.isBlank()) {
            return DIRECT;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return DIRECT;
        }
    }
}

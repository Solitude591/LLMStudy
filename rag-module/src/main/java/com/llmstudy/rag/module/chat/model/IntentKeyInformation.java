package com.llmstudy.rag.module.chat.model;

import java.util.List;

/**
 * 意图识别阶段从用户问题与会话上下文中抽取的论文检索关键信息。
 */
public record IntentKeyInformation(
        List<String> paperTitles,
        List<String> authors,
        List<String> researchTopics,
        List<String> methodsOrModels,
        List<String> datasets,
        List<String> metrics,
        List<String> otherConstraints) {

    /** 返回所有集合都非 null 的结果，便于序列化和下游使用。 */
    public IntentKeyInformation normalized() {
        return new IntentKeyInformation(
                nonNull(paperTitles),
                nonNull(authors),
                nonNull(researchTopics),
                nonNull(methodsOrModels),
                nonNull(datasets),
                nonNull(metrics),
                nonNull(otherConstraints));
    }

    /** @return 不包含任何检索约束的空信息对象 */
    public static IntentKeyInformation empty() {
        return new IntentKeyInformation(
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
    }

    private static List<String> nonNull(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}

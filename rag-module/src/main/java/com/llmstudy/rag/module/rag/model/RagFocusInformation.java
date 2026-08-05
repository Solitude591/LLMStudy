package com.llmstudy.rag.module.rag.model;

import java.util.List;

/** 意图识别阶段抽取的结构化检索焦点。 */
public record RagFocusInformation(
        List<String> paperTitles,
        List<String> authors,
        List<String> researchTopics,
        List<String> methodsOrModels,
        List<String> datasets,
        List<String> metrics,
        List<String> otherConstraints) {

    public RagFocusInformation {
        paperTitles = immutable(paperTitles);
        authors = immutable(authors);
        researchTopics = immutable(researchTopics);
        methodsOrModels = immutable(methodsOrModels);
        datasets = immutable(datasets);
        metrics = immutable(metrics);
        otherConstraints = immutable(otherConstraints);
    }

    /** @return 所有检索焦点均为空集合的安全对象 */
    public static RagFocusInformation empty() {
        return new RagFocusInformation(List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
    }

    /** @return 是否未抽取到任何可注入 Prompt 的检索焦点 */
    public boolean isEmpty() {
        return paperTitles.isEmpty() && authors.isEmpty() && researchTopics.isEmpty()
                && methodsOrModels.isEmpty() && datasets.isEmpty() && metrics.isEmpty()
                && otherConstraints.isEmpty();
    }

    /** 过滤 null/空白值并返回不可变列表，保证 Pipeline 中的数据稳定。 */
    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }
}

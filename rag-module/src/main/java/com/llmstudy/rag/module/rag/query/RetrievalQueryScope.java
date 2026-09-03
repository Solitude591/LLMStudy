package com.llmstudy.rag.module.rag.query;

import com.llmstudy.rag.module.rag.model.RetrievalQueryPlan;

import java.util.regex.Pattern;

/**
 * 用可解释的保守规则识别需要更大证据覆盖面的查询。
 *
 * <p>只把明确的跨论文/多任务比较和总结流程问题标为综合查询，避免仅因问题里
 * 出现“分别”或多个指标就扩大普通事实题的上下文。</p>
 */
public record RetrievalQueryScope(boolean comprehensive, boolean crossDocument) {

    private static final Pattern CROSS_DOCUMENT_ZH = Pattern.compile(
            "(?:两|三|四|五|多|[2-9])\\s*(?:篇|份)\\s*(?:论文|文献|材料)");
    private static final Pattern MULTI_TASK_ZH = Pattern.compile(
            "(?:两|三|四|五|多|[2-9])\\s*(?:类|种)\\s*(?:任务|实验|数据集|方法)");
    private static final Pattern SYNTHESIS_ZH = Pattern.compile(
            "概括|总结|综述|系统(?:说明|分析)|完整(?:流程|过程|链路)");
    private static final Pattern CROSS_DOCUMENT_EN = Pattern.compile(
            "(?i)\\b(?:two|three|four|five|multiple|[2-9])\\s+"
                    + "(?:papers|documents|studies|articles)\\b");
    private static final Pattern SYNTHESIS_EN = Pattern.compile(
            "(?i)\\b(?:compare|contrast|summari[sz]e|overview|complete workflow|"
                    + "end-to-end process)\\b");

    public static RetrievalQueryScope from(RetrievalQueryPlan plan) {
        return from(plan, 0);
    }

    /** Entity resolution uses accessible, published documents, never model-invented names. */
    public static RetrievalQueryScope from(RetrievalQueryPlan plan, int mentionedDocumentCount) {
        String original = plan == null || plan.originalQuestion() == null
                ? "" : plan.originalQuestion();
        String english = plan == null || plan.standaloneEn() == null
                ? "" : plan.standaloneEn();
        boolean crossDocument = mentionedDocumentCount >= 2
                || CROSS_DOCUMENT_ZH.matcher(original).find()
                || CROSS_DOCUMENT_EN.matcher(original).find();
        boolean comprehensive = crossDocument
                || MULTI_TASK_ZH.matcher(original).find()
                || SYNTHESIS_ZH.matcher(original).find()
                || SYNTHESIS_EN.matcher(original).find()
                || SYNTHESIS_EN.matcher(english).find();
        return new RetrievalQueryScope(comprehensive, crossDocument);
    }
}

package com.llmstudy.rag.module.rag.query;

import com.llmstudy.rag.module.knowledge.model.SegmentMetadataKeys;
import com.llmstudy.rag.module.rag.model.RetrievalCandidate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从用户原问题里抽出显式页码，供召回加宽和 RRF 后置顶重叠页候选。
 *
 * <p>评测里大量 fact/table 题已经点名了论文和页码，但词面检索仍被标题页占满。
 * 页码字段在 ES 中不可检索，所以只在召回结果上做稳定重排，不另写一条检索链路。</p>
 */
public final class QueryPageHint {

    private static final Pattern ZH_PAGE = Pattern.compile("第\\s*(\\d+)\\s*页");
    private static final Pattern EN_PAGE = Pattern.compile("(?i)\\bpage\\s*(\\d+)\\b");

    /** 点名页码时每路召回条数下限，让后页表格有机会进入 RRF 池。 */
    public static final int RECALL_TOP_K = 40;

    /** 点名页码时 RRF 融合上限，与加宽后的两路召回对齐。 */
    public static final int FUSION_CANDIDATE_COUNT = 80;

    private QueryPageHint() {
    }

    public static Set<Integer> pages(String question) {
        LinkedHashSet<Integer> pages = new LinkedHashSet<>();
        if (question == null || question.isBlank()) {
            return Set.of();
        }
        collect(pages, ZH_PAGE.matcher(question));
        collect(pages, EN_PAGE.matcher(question));
        return pages.isEmpty() ? Set.of() : Set.copyOf(pages);
    }

    /**
     * 把页码重叠的候选稳定地提到前面，未点名页码或无人重叠时保持原序。
     */
    public static List<RetrievalCandidate> promote(
            List<RetrievalCandidate> ranked, Set<Integer> pages) {
        if (ranked == null || ranked.isEmpty() || pages == null || pages.isEmpty()) {
            return ranked == null ? List.of() : ranked;
        }
        List<RetrievalCandidate> matched = new ArrayList<>();
        List<RetrievalCandidate> rest = new ArrayList<>();
        for (RetrievalCandidate candidate : ranked) {
            if (overlaps(candidate, pages)) {
                matched.add(candidate);
            } else {
                rest.add(candidate);
            }
        }
        if (matched.isEmpty()) {
            return ranked;
        }
        matched.addAll(rest);
        return List.copyOf(matched);
    }

    static boolean overlaps(RetrievalCandidate candidate, Set<Integer> pages) {
        Integer start = positiveInt(candidate.metadata().get(SegmentMetadataKeys.PAGE_START));
        Integer end = positiveInt(candidate.metadata().get(SegmentMetadataKeys.PAGE_END));
        if (start == null) {
            return false;
        }
        if (end == null) {
            end = start;
        }
        int lo = Math.min(start, end);
        int hi = Math.max(start, end);
        for (int page : pages) {
            if (lo <= page && page <= hi) {
                return true;
            }
        }
        return false;
    }

    private static void collect(Set<Integer> pages, Matcher matcher) {
        while (matcher.find()) {
            int page = Integer.parseInt(matcher.group(1));
            if (page > 0) {
                pages.add(page);
            }
        }
    }

    private static Integer positiveInt(Object value) {
        if (value instanceof Number number) {
            int parsed = number.intValue();
            return parsed > 0 ? parsed : null;
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.toString().trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}

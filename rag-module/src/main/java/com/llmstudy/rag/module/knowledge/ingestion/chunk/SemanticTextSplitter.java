package com.llmstudy.rag.module.knowledge.ingestion.chunk;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * 无空行长段的多级语义切分器。
 *
 * <p>按边界级别逐级查找：句子 → 从句标点 → 逗号 → 空白 → Unicode code point 硬切。
 * 不会把低优先级边界（如空格）抢在句号之前选用。child 均为原文连续子串。</p>
 */
public class SemanticTextSplitter {

    private static final String CLAUSE_DELIMITERS = "；;：:";
    private static final String COMMA_DELIMITERS = "，,";

    private final int chunkSize;
    private final int chunkOverlap;

    /**
     * @param chunkSize    单个 child 的最大 Unicode code point 数
     * @param chunkOverlap 相邻 child 目标重叠 code point 数，必须 &lt; chunkSize
     */
    public SemanticTextSplitter(int chunkSize, int chunkOverlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0");
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap 必须大于等于 0 且小于 chunkSize");
        }
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    /**
     * 将超限正文切成多个 child。
     *
     * @return 按原文顺序的子串列表；空输入返回空列表
     */
    public List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (codePointCount(text) <= chunkSize) {
            return List.of(text);
        }
        return sliceByBoundaries(text, collectBoundaryTiers(text));
    }

    /**
     * 在受保护区间约束下切分：保护区整段保留；仅两侧纯文本再递归 {@link #split}。
     *
     * @param text            与 protectedRanges 同一 UTF-16 坐标系
     * @param protectedRanges 半开区间 [start, end)
     */
    public List<String> splitRespectingProtectedRanges(String text,
                                                       List<IntRange> protectedRanges) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (codePointCount(text) <= chunkSize) {
            return List.of(text);
        }
        if (protectedRanges == null || protectedRanges.isEmpty()) {
            return split(text);
        }

        List<String> result = new ArrayList<>();
        int cursor = 0;
        List<IntRange> sorted = protectedRanges.stream()
                .filter(range -> range.start() < range.end()
                        && range.start() >= 0
                        && range.end() <= text.length())
                .sorted((left, right) -> Integer.compare(left.start(), right.start()))
                .toList();

        for (IntRange range : sorted) {
            if (range.start() < cursor) {
                continue;
            }
            if (range.start() > cursor) {
                result.addAll(split(text.substring(cursor, range.start())));
            }
            // 保护区即使超过 chunkSize 也不内切（公式/代码/图片语法）。
            String protectedText = text.substring(range.start(), range.end());
            if (!protectedText.isBlank()) {
                result.add(protectedText);
            }
            cursor = range.end();
        }
        if (cursor < text.length()) {
            result.addAll(split(text.substring(cursor)));
        }
        return result.isEmpty() ? List.of(text) : result;
    }

    /**
     * 按优先级分层收集边界。索引越小优先级越高。
     */
    private List<TreeSet<Integer>> collectBoundaryTiers(String text) {
        TreeSet<Integer> sentences = new TreeSet<>();
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ROOT);
        iterator.setText(text);
        for (int end = iterator.next(); end != BreakIterator.DONE; end = iterator.next()) {
            sentences.add(end);
        }

        TreeSet<Integer> clauses = new TreeSet<>();
        addDelimiterBoundaries(text, CLAUSE_DELIMITERS, clauses);

        TreeSet<Integer> commas = new TreeSet<>();
        addDelimiterBoundaries(text, COMMA_DELIMITERS, commas);

        TreeSet<Integer> whitespaces = new TreeSet<>();
        addWhitespaceBoundaries(text, whitespaces);

        return List.of(sentences, clauses, commas, whitespaces);
    }

    private void addDelimiterBoundaries(String text, String delimiters, TreeSet<Integer> boundaries) {
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            int charCount = Character.charCount(codePoint);
            // 切在分隔符之后，使标点留在前一段末尾。
            if (delimiters.indexOf(codePoint) >= 0) {
                boundaries.add(offset + charCount);
            }
            offset += charCount;
        }
    }

    private void addWhitespaceBoundaries(String text, TreeSet<Integer> boundaries) {
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            int charCount = Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                boundaries.add(offset);
                boundaries.add(offset + charCount);
            }
            offset += charCount;
        }
    }

    private List<String> sliceByBoundaries(String text, List<TreeSet<Integer>> tiers) {
        List<String> children = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int idealEnd = endOffsetForCodePoints(text, start, chunkSize);
            // 先句子、再从句、再逗号、再空白；都没有才硬切。
            int end = findEndByTier(tiers, start, idealEnd);
            if (end <= start) {
                end = idealEnd > start ? idealEnd : nextCodePointEnd(text, start);
                if (end <= start) {
                    end = text.length();
                }
            }
            children.add(text.substring(start, end));
            if (end >= text.length()) {
                break;
            }
            int overlapStart = resolveOverlapStart(text, tiers, start, end);
            // overlap 必须严格前进，否则重复窗口会死循环。
            start = overlapStart > start && overlapStart < end ? overlapStart : end;
        }
        return children;
    }

    /**
     * 在 (start, idealEnd] 内按层级找最大可用边界；高优先级层有命中则不再看低层。
     */
    private static int findEndByTier(List<TreeSet<Integer>> tiers, int start, int idealEnd) {
        for (TreeSet<Integer> tier : tiers) {
            int chosen = largestBoundaryAtMost(tier, idealEnd, start);
            if (chosen > start) {
                return chosen;
            }
        }
        return start;
    }

    private int resolveOverlapStart(String text,
                                    List<TreeSet<Integer>> tiers,
                                    int windowStart,
                                    int windowEnd) {
        if (chunkOverlap <= 0) {
            return windowEnd;
        }
        int idealOverlapStart = startOffsetForTrailingCodePoints(text, windowEnd, chunkOverlap);
        if (idealOverlapStart <= windowStart) {
            return windowEnd;
        }
        // overlap 同样按句子→从句→… 优先对齐，找不到则允许短于目标的字符 overlap。
        for (TreeSet<Integer> tier : tiers) {
            int boundary = smallestBoundaryAtLeast(tier, idealOverlapStart, windowEnd);
            if (boundary < windowEnd && boundary > windowStart) {
                return boundary;
            }
        }
        return idealOverlapStart > windowStart ? idealOverlapStart : windowEnd;
    }

    private static int largestBoundaryAtMost(TreeSet<Integer> points, int idealEnd, int start) {
        Integer floor = points.floor(idealEnd);
        if (floor == null || floor <= start) {
            return start;
        }
        return floor;
    }

    private static int smallestBoundaryAtLeast(TreeSet<Integer> points, int idealStart, int end) {
        Integer ceiling = points.ceiling(idealStart);
        if (ceiling == null || ceiling >= end) {
            return end;
        }
        return ceiling;
    }

    /** 从 start 起前进最多 maxCodePoints 个 code point 后的 UTF-16 下标。 */
    static int endOffsetForCodePoints(String text, int start, int maxCodePoints) {
        int offset = start;
        int count = 0;
        while (offset < text.length() && count < maxCodePoints) {
            offset += Character.charCount(text.codePointAt(offset));
            count++;
        }
        return offset;
    }

    /** 从 end 往回取最多 maxCodePoints 个 code point 的起点。 */
    static int startOffsetForTrailingCodePoints(String text, int end, int maxCodePoints) {
        int offset = end;
        int count = 0;
        while (offset > 0 && count < maxCodePoints) {
            offset = text.offsetByCodePoints(offset, -1);
            count++;
        }
        return offset;
    }

    static int nextCodePointEnd(String text, int start) {
        if (start >= text.length()) {
            return text.length();
        }
        return start + Character.charCount(text.codePointAt(start));
    }

    static int codePointCount(CharSequence text) {
        return text.toString().codePointCount(0, text.length());
    }

    /** UTF-16 下标半开区间。 */
    public record IntRange(int start, int end) {
    }
}

package com.llmstudy.rag.module.knowledge.ingestion.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Markdown / content_list 共用的标题路径栈。
 *
 * <p>遇到同级或更高级标题时弹出栈顶，保证 {@link #path()} 始终是
 * 「祖先 > … > 当前」形式，与旧 MarkdownHeaderChunker 的层级语义一致。</p>
 */
final class HeaderPathStack {

    /**
     * 识别阿拉伯数字章节编号：支持「2 Method」、「2. Method」和「2.1. Problem」等形式。
     * 编号后必须有空白与标题正文，避免将「3D Segmentation」误判为第 3 章。
     */
    private static final Pattern NUMBERED_HEADING = Pattern.compile(
            "^(\\d+)((?:\\.\\d+)*)(?:\\.)?\\s+\\S.*$");

    private final List<Header> stack = new ArrayList<>();
    /** 当前活动的一级章节编号，例如「2. Methodology」中的 2。 */
    private String numberedRoot;
    /** 当前一级章节的 MinerU 原始标题层级，用作子章节推导基准。 */
    private Integer numberedBaseLevel;

    /**
     * 压入标题；level/text 非法时忽略，避免空标题污染 header_path。
     */
    void push(Integer level, String text) {
        if (level == null || level <= 0 || text == null || text.isBlank()) {
            return;
        }
        String normalizedText = text.strip();
        int effectiveLevel = effectiveLevel(level, normalizedText);
        // 同级或更深标题先弹出，再压入当前标题，形成正确祖先链。
        while (!stack.isEmpty() && stack.getLast().level() >= effectiveLevel) {
            stack.removeLast();
        }
        stack.add(new Header(effectiveLevel, normalizedText));
    }

    /**
     * 根据标题编号深度修正 MinerU 的扁平层级。
     *
     * <p>例如「2. Methodology」和「2.1. Problem Setting」都被标为 H2 时，
     * 先记住第 2 章的基准层级 H2，再将 2.1 推导为 H3。无法可靠推导时
     * 保留原始层级，避免破坏已经正确的 Markdown 结构。</p>
     *
     * @param rawLevel MinerU 或 CommonMark 给出的原始标题层级
     * @param text     已去除首尾空白的标题文本
     * @return 用于标题栈入栈的有效层级
     */
    private int effectiveLevel(int rawLevel, String text) {
        Matcher matcher = NUMBERED_HEADING.matcher(text);
        if (!matcher.matches()) {
            // 同级或更高的未编号标题已离开当前数字章节，清理推导上下文。
            if (numberedBaseLevel != null && rawLevel <= numberedBaseLevel) {
                numberedRoot = null;
                numberedBaseLevel = null;
            }
            return rawLevel;
        }

        String root = matcher.group(1);
        // 编号中每多一个点就深一级：2 为 1 级，2.1 为 2 级，2.1.1 为 3 级。
        int depth = 1 + (int) matcher.group(2).chars().filter(ch -> ch == '.').count();
        if (depth == 1) {
            // 新的一级编号标题建立后续子章节的层级基准。
            numberedRoot = root;
            numberedBaseLevel = rawLevel;
            return rawLevel;
        }
        if (root.equals(numberedRoot)) {
            int inferredLevel = numberedBaseLevel + depth - 1;
            // Markdown 标题最深为 H6；超出时保留原层级，不强行截断。
            if (inferredLevel <= 6) {
                return inferredLevel;
            }
        }
        return rawLevel;
    }

    /** 无标题时返回空串，调用方据此省略 metadata 中的 header_path。 */
    String path() {
        if (stack.isEmpty()) {
            return "";
        }
        return stack.stream().map(Header::text).collect(Collectors.joining(" > "));
    }

    private record Header(int level, String text) {
    }
}

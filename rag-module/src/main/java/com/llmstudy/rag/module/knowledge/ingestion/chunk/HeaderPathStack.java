package com.llmstudy.rag.module.knowledge.ingestion.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Markdown / content_list 共用的标题路径栈。
 *
 * <p>遇到同级或更高级标题时弹出栈顶，保证 {@link #path()} 始终是
 * 「祖先 > … > 当前」形式，与旧 MarkdownHeaderChunker 的层级语义一致。</p>
 */
final class HeaderPathStack {

    private final List<Header> stack = new ArrayList<>();

    /**
     * 压入标题；level/text 非法时忽略，避免空标题污染 header_path。
     */
    void push(Integer level, String text) {
        if (level == null || level <= 0 || text == null || text.isBlank()) {
            return;
        }
        // 同级或更深标题先弹出，再压入当前标题，形成正确祖先链。
        while (!stack.isEmpty() && stack.getLast().level() >= level) {
            stack.removeLast();
        }
        stack.add(new Header(level, text.strip()));
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

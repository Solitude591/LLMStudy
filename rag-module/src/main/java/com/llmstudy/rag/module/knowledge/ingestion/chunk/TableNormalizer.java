package com.llmstudy.rag.module.knowledge.ingestion.chunk;

import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 HTML / GFM 表格压成管道分隔文本，供 BM25、Embedding、BGE 和生成共用。
 *
 * <p>解析失败时回退可见文本，避免空 chunk。</p>
 */
public final class TableNormalizer {

    private static final Pattern TABLE_OPEN = Pattern.compile("(?i)<table\\b");
    private static final Pattern TABLE_CLOSE = Pattern.compile("(?i)</table\\s*>");
    private static final Pattern ROW = Pattern.compile("(?is)<tr\\b([^>]*)>(.*?)</tr\\s*>");
    private static final Pattern CELL = Pattern.compile("(?is)<(t[dh])\\b([^>]*)>(.*?)</\\1\\s*>");
    private static final Pattern OPEN_CELL = Pattern.compile("(?is)<(t[dh])\\b([^>]*)>");
    private static final Pattern CAPTION = Pattern.compile("(?is)<caption\\b[^>]*>(.*?)</caption\\s*>");
    private static final Pattern SPAN = Pattern.compile("(?i)\\b(row|col)span\\s*=\\s*[\"']?(\\d+)");
    private static final Pattern TAG = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern BR = Pattern.compile("(?is)<br\\s*/?>");
    private static final Pattern WS = Pattern.compile("[\\s\\u00A0]+");

    private TableNormalizer() {
    }

    /** HTML 表格 → 管道行；失败则可见文本；仍空则退回原文 strip。 */
    public static String normalizeHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        try {
            String rendered = renderAllTables(html);
            if (!rendered.isBlank()) {
                return rendered;
            }
        } catch (RuntimeException ignored) {
            // 畸形 HTML 走可见文本
        }
        String visible = visibleText(html);
        if (!visible.isBlank()) {
            return visible;
        }
        return html.strip();
    }

    /** GFM 管道表只做空白折叠与 \| 反转义，保留管道结构。 */
    public static String normalizeGfm(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String stripped = line.strip();
            if (stripped.isEmpty()) {
                continue;
            }
            if (stripped.indexOf('|') < 0) {
                out.add(cellText(stripped));
                continue;
            }
            boolean leading = stripped.startsWith("|");
            boolean trailing = stripped.endsWith("|")
                    && (stripped.length() < 2 || stripped.charAt(stripped.length() - 2) != '\\');
            String joined = String.join(" | ", splitGfmCells(stripped));
            if (leading) {
                joined = "| " + joined;
            }
            if (trailing) {
                joined = joined + " |";
            }
            out.add(joined);
        }
        return String.join("\n", out);
    }

    static String visibleText(String html) {
        return cellText(html);
    }

    private static String renderAllTables(String html) {
        List<String> parts = new ArrayList<>();
        int cursor = 0;
        Matcher open = TABLE_OPEN.matcher(html);
        while (open.find(cursor)) {
            int start = open.start();
            if (start > cursor) {
                String prefix = visibleText(html.substring(cursor, start));
                if (!prefix.isBlank()) {
                    parts.add(prefix);
                }
            }
            int end = findTableEnd(html, open.end());
            String rendered = renderOneTable(html.substring(start, end));
            if (!rendered.isBlank()) {
                parts.add(rendered);
            }
            cursor = end;
        }
        if (cursor == 0) {
            return "";
        }
        if (cursor < html.length()) {
            String suffix = visibleText(html.substring(cursor));
            if (!suffix.isBlank()) {
                parts.add(suffix);
            }
        }
        return String.join("\n", parts);
    }

    private static int findTableEnd(String html, int from) {
        int depth = 1;
        int i = from;
        while (i < html.length() && depth > 0) {
            Matcher nextOpen = TABLE_OPEN.matcher(html);
            Matcher nextClose = TABLE_CLOSE.matcher(html);
            boolean hasOpen = nextOpen.find(i);
            boolean hasClose = nextClose.find(i);
            if (!hasClose) {
                return html.length();
            }
            if (hasOpen && nextOpen.start() < nextClose.start()) {
                depth++;
                i = nextOpen.end();
            } else {
                depth--;
                i = nextClose.end();
            }
        }
        return i;
    }

    private static String renderOneTable(String tableHtml) {
        String caption = firstMatch(CAPTION, tableHtml);
        Grid grid = new Grid();
        int rowIndex = 0;
        Matcher rows = ROW.matcher(tableHtml);
        while (rows.find()) {
            placeRow(grid, rowIndex, rows.group(2));
            rowIndex++;
        }
        if (grid.isEmpty()) {
            return fallbackCells(caption, tableHtml);
        }
        List<String> lines = new ArrayList<>();
        if (caption != null && !caption.isBlank()) {
            lines.add(caption);
        }
        lines.addAll(grid.lines());
        return String.join("\n", lines);
    }

    private static void placeRow(Grid grid, int rowIndex, String rowHtml) {
        Matcher cells = CELL.matcher(rowHtml);
        int col = 0;
        boolean found = false;
        while (cells.find()) {
            found = true;
            while (grid.occupied(rowIndex, col)) {
                col++;
            }
            String text = cellText(cells.group(3));
            int rowspan = span(cells.group(2), "row");
            int colspan = span(cells.group(2), "col");
            grid.fill(rowIndex, col, rowspan, colspan, text);
            col += colspan;
        }
        if (found) {
            return;
        }
        Matcher openCells = OPEN_CELL.matcher(rowHtml);
        while (openCells.find()) {
            while (grid.occupied(rowIndex, col)) {
                col++;
            }
            int contentStart = openCells.end();
            int contentEnd = rowHtml.length();
            Matcher next = OPEN_CELL.matcher(rowHtml);
            if (next.find(contentStart)) {
                contentEnd = next.start();
            }
            String text = cellText(rowHtml.substring(contentStart, contentEnd));
            int rowspan = span(openCells.group(2), "row");
            int colspan = span(openCells.group(2), "col");
            grid.fill(rowIndex, col, rowspan, colspan, text);
            col += colspan;
        }
    }

    private static String fallbackCells(String caption, String tableHtml) {
        List<String> cells = new ArrayList<>();
        Matcher matcher = CELL.matcher(tableHtml);
        while (matcher.find()) {
            cells.add(cellText(matcher.group(3)));
        }
        if (cells.isEmpty()) {
            String visible = visibleText(tableHtml);
            if (caption == null || caption.isBlank()) {
                return visible;
            }
            return visible.isBlank() ? caption : caption + "\n" + visible;
        }
        String row = String.join(" | ", cells);
        return caption == null || caption.isBlank() ? row : caption + "\n" + row;
    }

    private static int span(String attrs, String which) {
        if (attrs == null) {
            return 1;
        }
        Matcher matcher = SPAN.matcher(attrs);
        while (matcher.find()) {
            if (matcher.group(1).toLowerCase(Locale.ROOT).startsWith(which)) {
                return Math.max(1, Integer.parseInt(matcher.group(2)));
            }
        }
        return 1;
    }

    private static String firstMatch(Pattern pattern, String html) {
        Matcher matcher = pattern.matcher(html);
        return matcher.find() ? cellText(matcher.group(1)) : null;
    }

    static String cellText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = BR.matcher(html).replaceAll(" ");
        text = TAG.matcher(text).replaceAll(" ");
        text = text.replaceAll("(?i)&nbsp;", " ");
        text = HtmlUtils.htmlUnescape(text).replace('\u00A0', ' ');
        return WS.matcher(text).replaceAll(" ").strip();
    }

    private static List<String> splitGfmCells(String line) {
        String body = line.strip();
        if (body.startsWith("|")) {
            body = body.substring(1);
        }
        if (body.endsWith("|") && (body.length() < 2 || body.charAt(body.length() - 2) != '\\')) {
            body = body.substring(0, body.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '\\' && i + 1 < body.length() && body.charAt(i + 1) == '|') {
                current.append('|');
                i++;
            } else if (ch == '|') {
                cells.add(cellText(current.toString()));
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        cells.add(cellText(current.toString()));
        return cells;
    }

    /** 稀疏网格：rowspan/colspan 填入同一文本，保证每行自包含。 */
    private static final class Grid {
        private final List<List<String>> cells = new ArrayList<>();

        private void fill(int row, int col, int rowspan, int colspan, String text) {
            for (int dr = 0; dr < rowspan; dr++) {
                for (int dc = 0; dc < colspan; dc++) {
                    put(row + dr, col + dc, text);
                }
            }
        }

        private void put(int row, int col, String text) {
            while (cells.size() <= row) {
                cells.add(new ArrayList<>());
            }
            List<String> line = cells.get(row);
            while (line.size() <= col) {
                line.add(null);
            }
            if (line.get(col) == null) {
                line.set(col, text);
            }
        }

        private boolean occupied(int row, int col) {
            return row < cells.size()
                    && col < cells.get(row).size()
                    && cells.get(row).get(col) != null;
        }

        private boolean isEmpty() {
            return cells.isEmpty();
        }

        private List<String> lines() {
            List<String> lines = new ArrayList<>();
            for (List<String> row : cells) {
                int last = row.size() - 1;
                while (last >= 0 && (row.get(last) == null || row.get(last).isBlank())) {
                    last--;
                }
                if (last < 0) {
                    continue;
                }
                List<String> rendered = new ArrayList<>(last + 1);
                for (int i = 0; i <= last; i++) {
                    String cell = row.get(i);
                    rendered.add(cell == null ? "" : cell);
                }
                lines.add(String.join(" | ", rendered));
            }
            return lines;
        }
    }
}

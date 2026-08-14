package com.llmstudy.rag.module.knowledge.ingestion.chunk;

import com.llmstudy.rag.dto.MineruContentElement;
import com.llmstudy.rag.enums.DocumentLanguage;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 版本级语言：标题+正文，排除图注、表格、代码、参考文献。 */
public final class DocumentLanguageDetector {

    private static final int MIN_CHARS = 100;
    private static final double ZH_RATIO = 0.20;
    private static final Pattern FENCE = Pattern.compile("(?ms)^```.*?^```\\s*");
    private static final Pattern HTML_TABLE = Pattern.compile("(?is)<table\\b.*?</table>");
    private static final Pattern GFM_ROW = Pattern.compile("(?m)^\\s*\\|.*\\|\\s*$");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");
    private static final Pattern REFS = Pattern.compile(
            "(?ims)^\\s{0,3}#{1,6}\\s*"
                    + "(?:\\d+(?:\\.\\d+)*[.)、]?\\s*)?"
                    + "(?:references?|bibliography|works\\s+cited|literature\\s+cited|"
                    + "参考文献|参考资料|参考书目)\\s*[:：]?\\s*#*\\s*$.*\\z");

    private DocumentLanguageDetector() {
    }

    public static DocumentLanguage fromContentList(List<MineruContentElement> elements) {
        if (elements == null || elements.isEmpty()) {
            return DocumentLanguage.UNKNOWN;
        }
        StringBuilder text = new StringBuilder();
        Integer referenceLevel = null;
        for (MineruContentElement element : elements) {
            if (element == null || element.getType() == null) {
                continue;
            }
            String type = element.getType().toLowerCase(Locale.ROOT);
            if (referenceLevel != null) {
                boolean nextSection = element.isHeading()
                        && element.getTextLevel() <= referenceLevel
                        && !ReferenceSectionMatcher.isReferenceHeading(element.getText());
                if (!nextSection) {
                    continue;
                }
                referenceLevel = null;
            }
            if ("ref_text".equals(type)) {
                continue;
            }
            if ("text".equals(type)
                    && ReferenceSectionMatcher.isReferenceHeading(element.getText())) {
                referenceLevel = element.isHeading()
                        ? element.getTextLevel() : Integer.MAX_VALUE;
                continue;
            }
            if ("text".equals(type) && element.getText() != null && !element.getText().isBlank()) {
                text.append(element.getText()).append('\n');
            }
        }
        return detect(text.toString());
    }

    public static DocumentLanguage fromMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return DocumentLanguage.UNKNOWN;
        }
        String text = markdown.replace("\r\n", "\n").replace('\r', '\n');
        text = FENCE.matcher(text).replaceAll("\n");
        text = HTML_TABLE.matcher(text).replaceAll("\n");
        text = GFM_ROW.matcher(text).replaceAll("");
        text = IMAGE.matcher(text).replaceAll(" ");
        text = REFS.matcher(text).replaceAll("");
        return detect(TableNormalizer.visibleText(text));
    }

    static DocumentLanguage detect(String text) {
        int han = 0;
        int latin = 0;
        if (text != null) {
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                    han++;
                } else if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z')) {
                    latin++;
                }
            }
        }
        int total = han + latin;
        if (han >= MIN_CHARS && total > 0 && (double) han / total >= ZH_RATIO) {
            return DocumentLanguage.ZH;
        }
        if (latin >= MIN_CHARS) {
            return DocumentLanguage.EN;
        }
        return DocumentLanguage.UNKNOWN;
    }
}

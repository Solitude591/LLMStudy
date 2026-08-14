package com.llmstudy.rag.module.knowledge.ingestion.chunk;

import java.util.regex.Pattern;

/** 识别论文中不需要进入检索库的参考文献章节。 */
final class ReferenceSectionMatcher {

    private static final Pattern TITLE = Pattern.compile(
            "^(?:\\d+(?:\\.\\d+)*[.)、]?\\s*)?"
                    + "(?:references?|bibliography|works\\s+cited|literature\\s+cited|"
                    + "参考文献|参考资料|参考书目)\\s*[:：]?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ReferenceSectionMatcher() {
    }

    static boolean isReferenceHeading(String text) {
        return text != null && TITLE.matcher(text.strip()).matches();
    }
}

package com.llmstudy.rag.enums;

import java.util.Arrays;
import java.util.Locale;

/** knowledge_document_version.language 与 ES metadata.language。 */
public enum DocumentLanguage {
    ZH("ZH"),
    EN("EN"),
    UNKNOWN("UNKNOWN");

    private final String value;

    DocumentLanguage(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static DocumentLanguage fromValue(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.value.equals(normalized))
                .findFirst()
                .orElse(UNKNOWN);
    }
}

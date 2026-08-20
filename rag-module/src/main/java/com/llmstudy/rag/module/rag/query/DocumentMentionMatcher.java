package com.llmstudy.rag.module.rag.query;

import com.llmstudy.rag.entity.KnowledgeDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从问题中的显式论文/方法名称匹配知识库文档版本。 */
public final class DocumentMentionMatcher {

    private static final Pattern YEAR_PREFIX = Pattern.compile("^\\d{4}[_\\s-]+");
    private static final Pattern PDF_SUFFIX = Pattern.compile("(?i)\\.pdf$");
    private static final Pattern LAYOUT_SUFFIX = Pattern.compile(
            "(?i)^(?:单栏|双栏|single[-_ ]?column|double[-_ ]?column)$");

    private DocumentMentionMatcher() {
    }

    /**
     * 长名称优先并从问题中移除已匹配片段，防止“Attention U-Net”再次误中
     * 较短的“U-Net”；若问题还单独写了 U-Net，则剩余片段仍能命中。
     */
    public static List<String> mentionedVersionIds(
            String question, List<KnowledgeDocument> documents) {
        if (question == null || question.isBlank() || documents == null) {
            return List.of();
        }
        List<DocumentAlias> aliases = documents.stream()
                .filter(document -> document != null
                        && document.getCurrentVersionId() != null
                        && !document.getCurrentVersionId().isBlank())
                .map(document -> new DocumentAlias(
                        alias(document.getDocTitle()), document.getCurrentVersionId()))
                .filter(alias -> alias.name().replaceAll("[-_\\s]", "").length() >= 4)
                .sorted(Comparator.comparingInt(
                        (DocumentAlias alias) -> alias.name().length()).reversed())
                .toList();
        String remaining = question;
        List<String> versions = new ArrayList<>();
        for (DocumentAlias alias : aliases) {
            Matcher matcher = pattern(alias.name()).matcher(remaining);
            if (matcher.find()) {
                versions.add(alias.versionId());
                remaining = matcher.replaceAll(" ");
            }
        }
        return List.copyOf(versions);
    }

    /** 删除显式文档名，供已按版本过滤的补充检索突出真正的问题主题。 */
    public static String withoutDocumentMentions(
            String query, List<KnowledgeDocument> documents) {
        if (query == null || query.isBlank() || documents == null) {
            return query;
        }
        String focused = query;
        List<String> aliases = documents.stream()
                .filter(document -> document != null)
                .map(document -> alias(document.getDocTitle()))
                .filter(name -> name.replaceAll("[-_\\s]", "").length() >= 4)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        for (String alias : aliases) {
            focused = pattern(alias).matcher(focused).replaceAll(" ");
        }
        String compact = focused.replaceAll("\\s+", " ").trim();
        return compact.isBlank() ? query : compact;
    }

    static String alias(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return "";
        }
        String title = PDF_SUFFIX.matcher(rawTitle.trim()).replaceFirst("");
        title = YEAR_PREFIX.matcher(title).replaceFirst("");
        String[] parts = title.split("_+");
        for (int index = parts.length - 1; index >= 0; index--) {
            String part = parts[index].trim();
            if (!part.isEmpty() && !LAYOUT_SUFFIX.matcher(part).matches()) {
                return part;
            }
        }
        return title;
    }

    private static Pattern pattern(String alias) {
        String[] terms = alias.trim().split("[-_\\s]+");
        StringBuilder body = new StringBuilder();
        for (String term : terms) {
            if (term.isBlank()) {
                continue;
            }
            if (!body.isEmpty()) {
                body.append("[-_\\s]*");
            }
            body.append(Pattern.quote(term.toLowerCase(Locale.ROOT)));
        }
        return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + body
                + "(?![\\p{L}\\p{N}])");
    }

    private record DocumentAlias(String name, String versionId) {
    }
}

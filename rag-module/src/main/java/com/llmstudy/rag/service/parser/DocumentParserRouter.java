package com.llmstudy.rag.service.parser;

import com.llmstudy.rag.dto.DocumentParseResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 根据文件类型动态选择文档解析策略。
 */
@Component
public class DocumentParserRouter {

    private final Map<String, DocumentParserStrategy> strategies;

    public DocumentParserRouter(List<DocumentParserStrategy> parserStrategies) {
        Map<String, DocumentParserStrategy> routingTable = new LinkedHashMap<>();
        for (DocumentParserStrategy strategy : parserStrategies) {
            for (String fileType : strategy.supportedFileTypes()) {
                String normalizedType = normalize(fileType);
                DocumentParserStrategy existing = routingTable.putIfAbsent(normalizedType, strategy);
                if (existing != null) {
                    throw new IllegalStateException(
                            "文档解析策略重复注册: fileType=" + normalizedType
                                    + ", strategies=" + existing.getClass().getSimpleName()
                                    + "/" + strategy.getClass().getSimpleName());
                }
            }
        }
        this.strategies = Map.copyOf(routingTable);
    }

    public DocumentParseResult parse(DocumentParseContext context) {
        String fileType = normalize(context.document().getFileType());
        DocumentParserStrategy strategy = strategies.get(fileType);
        if (strategy == null) {
            throw new IllegalArgumentException("暂不支持解析该文件类型: ." + fileType);
        }
        return strategy.parse(context);
    }

    public boolean supports(String fileType) {
        return strategies.containsKey(normalize(fileType));
    }

    private String normalize(String fileType) {
        if (fileType == null || fileType.isBlank()) {
            return "";
        }
        String normalized = fileType.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith(".") ? normalized.substring(1) : normalized;
    }
}

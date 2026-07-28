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
        // Spring 会注入所有策略实现；启动时将其展开为“扩展名 → 策略”的只读路由表。
        Map<String, DocumentParserStrategy> routingTable = new LinkedHashMap<>();
        for (DocumentParserStrategy strategy : parserStrategies) {
            for (String fileType : strategy.supportedFileTypes()) {
                String normalizedType = normalize(fileType);
                // 同一扩展名只能由一个策略处理，冲突时直接阻止应用启动，避免运行时随机路由。
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
        // 数据库存储的扩展名仍统一规范化，以兼容历史数据中可能存在的点号或大小写差异。
        String fileType = normalize(context.document().getFileType());
        DocumentParserStrategy strategy = strategies.get(fileType);
        if (strategy == null) {
            throw new IllegalArgumentException("暂不支持解析该文件类型: ." + fileType);
        }
        // 不在路由层处理格式细节，所有策略最终都转换成统一的 DocumentParseResult。
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

package com.llmstudy.rag.service.parser;

import com.llmstudy.rag.dto.DocumentParseResult;

import java.util.Set;

/**
 * 文档解析策略。新增格式时实现该接口并注册为 Spring Bean 即可。
 */
public interface DocumentParserStrategy {

    /**
     * 当前策略支持的文件扩展名，使用不带点号的小写形式。
     */
    Set<String> supportedFileTypes();

    /**
     * 将原始文档解析为统一结果。
     */
    DocumentParseResult parse(DocumentParseContext context);
}

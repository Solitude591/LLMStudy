package com.llmstudy.rag.service.parser;

import com.llmstudy.rag.entity.KnowledgeDocument;
import org.springframework.core.io.InputStreamSource;

/**
 * 解析策略的输入上下文。
 *
 * @param document   文档数据库记录；PDF 策略会使用其中的公网 docUrl
 * @param sourceFile 当前上传请求中的原始文件流；本地解析策略直接消费该流
 */
public record DocumentParseContext(KnowledgeDocument document, InputStreamSource sourceFile) {

    public DocumentParseContext {
        // 在统一入口校验上下文，具体解析策略可以直接使用而无需重复判空。
        if (document == null) {
            throw new IllegalArgumentException("待解析文档不能为空");
        }
        if (sourceFile == null) {
            throw new IllegalArgumentException("待解析文件流不能为空");
        }
    }
}

package com.llmstudy.rag.service.parser;

import com.llmstudy.rag.entity.KnowledgeDocument;

/**
 * 解析策略的输入上下文。
 *
 * @param document         文档数据库记录
 * @param sourceObjectKey  原始文件在 MinIO 中的对象键
 */
public record DocumentParseContext(KnowledgeDocument document, String sourceObjectKey) {

    public DocumentParseContext {
        if (document == null) {
            throw new IllegalArgumentException("待解析文档不能为空");
        }
        if (sourceObjectKey == null || sourceObjectKey.isBlank()) {
            throw new IllegalArgumentException("原始文件对象键不能为空");
        }
    }
}

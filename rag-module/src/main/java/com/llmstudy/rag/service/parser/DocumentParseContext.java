package com.llmstudy.rag.service.parser;

import com.llmstudy.rag.entity.KnowledgeDocument;

/**
 * 解析策略的输入上下文。
 *
 * @param document 文档数据库记录；MinerU 策略会使用其中的公网 docUrl。
 */
public record DocumentParseContext(KnowledgeDocument document) {

    public DocumentParseContext {
        if (document == null) {
            throw new IllegalArgumentException("待解析文档不能为空");
        }
    }
}

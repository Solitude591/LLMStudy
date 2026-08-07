package com.llmstudy.rag.module.knowledge.ingestion.parser;

import com.llmstudy.rag.entity.KnowledgeDocumentVersion;

/**
 * 解析策略的输入上下文。
 *
 * @param version 物理版本记录；文件类型、公网 docUrl 等快照信息均来自版本。
 */
public record DocumentParseContext(KnowledgeDocumentVersion version) {

    public DocumentParseContext {
        if (version == null) {
            throw new IllegalArgumentException("待解析版本不能为空");
        }
    }
}

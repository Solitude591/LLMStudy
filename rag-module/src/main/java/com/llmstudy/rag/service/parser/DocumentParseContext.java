package com.llmstudy.rag.service.parser;

import com.llmstudy.rag.entity.KnowledgeDocument;
import org.springframework.core.io.InputStreamSource;

/**
 * 解析策略的输入上下文。
 *
 * @param document   文档数据库记录；PDF 策略会使用其中的公网 docUrl。
 *                   TXT 策略异步执行时可以从 docUrl 下载原始文件。
 * @param sourceFile 当前上传请求中的原始文件流；本地解析策略优先使用此流，
 *                   为 null 时表示异步触发，策略应从 MinIO 下载原始文件。
 */
public record DocumentParseContext(KnowledgeDocument document, InputStreamSource sourceFile) {

    public DocumentParseContext {
        if (document == null) {
            throw new IllegalArgumentException("待解析文档不能为空");
        }
        // sourceFile 允许为 null —— 异步事件驱动流程中不再持有上传请求的临时文件，
        // 此时策略需要从 document.docUrl 自行下载原始文件内容。
    }
}

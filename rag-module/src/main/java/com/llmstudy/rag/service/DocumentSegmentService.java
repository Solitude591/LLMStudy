package com.llmstudy.rag.service;

import com.llmstudy.rag.dto.DocumentSplitResult;

/**
 * 文档分片及分片持久化服务。
 */
public interface DocumentSegmentService {

    /**
     * 根据 docId 读取转换后的 Markdown，执行父子分片并保存到数据库。
     *
     * @param docId 文档业务 ID
     * @return 分片数量及文档最新状态
     */
    DocumentSplitResult splitDocument(String docId);
}

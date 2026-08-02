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

    /**
     * 将文档下所有待向量化的 segment 批量 embedding 并写入 ES。
     *
     * <p>只处理 status='INIT' 且 skip_embedding=0 的 segment。
     * 单次调用完成整篇文档的向量化，失败时回滚 segment 状态。</p>
     *
     * @param docId 文档业务 ID
     * @return 成功向量化的 segment 数量
     */
    int embedSegments(String docId);
}

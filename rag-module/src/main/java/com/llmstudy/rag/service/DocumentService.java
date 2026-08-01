package com.llmstudy.rag.service;

import com.llmstudy.rag.dto.DocumentVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档管理服务接口
 */
public interface DocumentService {

    /**
     * 上传文件并创建文档记录。
     *
     * <p>上传完成后发布 {@code DocumentUploadedEvent}，由异步监听器接管后续
     * 解析、分片、向量化流程，本方法立即返回，不阻塞 HTTP 响应。</p>
     *
     * @param file       上传的文件
     * @param docTitle   文档标题（可选，默认取原始文件名）
     * @param uploader   上传者
     * @param visibility 可见范围
     * @param tableName  Excel 目标 MySQL 基础表名；非 Excel 可为空
     * @return 文档响应对象（status=uploaded）
     */
    DocumentVO uploadDocument(MultipartFile file,
                              String docTitle,
                              String uploader,
                              String visibility,
                              String tableName);

    /**
     * 根据 doc_id 查询文档元数据。
     *
     * @param docId 文档业务 ID
     * @return 文档响应对象；不存在时返回 null
     */
    DocumentVO getDocument(String docId);

    /**
     * 异步处理上传文档（供事件监听器调用）。
     *
     * <p>Excel 按 Sheet 导入 MySQL 并终止流水线；PDF/Word 交给
     * MinerU 解析为 Markdown，继续后续分片与向量化。</p>
     *
     * <p>MinerU 使用文档的公网 docUrl 拉取原始文件。</p>
     *
     * @param docId 文档业务 ID
     * @return 本次处理结果，决定监听器是否发布后续 RAG 事件
     */
    DocumentProcessingOutcome processDocument(String docId);
}

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
     * @return 文档响应对象（status=uploaded）
     */
    DocumentVO uploadDocument(MultipartFile file, String docTitle, String uploader, String visibility);

    /**
     * 根据 doc_id 查询文档元数据。
     *
     * @param docId 文档业务 ID
     * @return 文档响应对象；不存在时返回 null
     */
    DocumentVO getDocument(String docId);

    /**
     * 异步解析文档（供事件监听器调用）。
     *
     * <p>从数据库读取文档记录，通过解析路由器按文件类型分发到具体策略。
     * 解析产物（Markdown、图片、content_list.json）上传到 MinIO，
     * 完成后更新状态为 converted 并回填 converted_doc_url。</p>
     *
     * <p>与上传时的同步解析不同，此方法不持有 MultipartFile 文件流，
     * TXT 等本地策略会从 MinIO 下载原始文件。</p>
     *
     * @param docId 文档业务 ID
     * @return true 表示本次调用完成了解析；false 表示文档已经离开 uploaded 状态，
     *         当前事件属于重复或迟到事件
     */
    boolean parseDocument(String docId);
}

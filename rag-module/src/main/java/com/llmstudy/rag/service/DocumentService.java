package com.llmstudy.rag.service;

import com.llmstudy.rag.dto.DocumentVO;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档管理服务接口
 */
public interface DocumentService {

    /**
     * 上传文件、创建文档记录并按文件类型自动解析。
     *
     * 流程：
     * 1. 生成 doc_id（UUID 去横线）
     * 2. 上传文件到 MinIO，得到 doc_url
     * 3. 写入 knowledge_document 表（status=uploaded）
     * 4. 已注册解析策略时自动解析（PDF 使用 MinerU，TXT 本地读取）
     * 5. 返回包含最新状态和 convertedDocUrl 的 DocumentVO
     *
     * @param file       上传的文件
     * @param docTitle   文档标题（可选，默认取原始文件名）
     * @param uploader   上传者
     * @param visibility 可见范围
     * @return 文档响应对象
     */
    DocumentVO uploadDocument(MultipartFile file, String docTitle, String uploader, String visibility);

    /**
     * 根据 doc_id 查询文档元数据
     */
    DocumentVO getDocument(String docId);

}

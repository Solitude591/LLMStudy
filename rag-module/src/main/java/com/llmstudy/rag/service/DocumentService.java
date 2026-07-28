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
     * 流程：
     * 1. 生成 doc_id（UUID 去横线）
     * 2. 上传文件到 MinIO，得到 doc_url
     * 3. 写入 knowledge_document 表（status=uploaded）
     * 4. 返回 DocumentVO
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

    /**
     * 提交文档到 MinerU 解析并等待完成。
     *
     * 流程：
     * 1. 查 knowledge_document 获取 docUrl，状态 → converting
     * 2. 调用 MinerU 提交 + 轮询，下载结果 ZIP
     * 3. 从 ZIP 提取 full.md、content_list.json 和图片
     * 4. 被引用的图片上传到 {docId}/converted/images/
     * 5. 生成图片描述（PDF 原文图注 + 视觉模型），改写 Markdown 与 content_list
     * 6. Markdown 和 content_list.json 上传到 {docId}/converted/
     * 7. 更新 converted_doc_url，状态 → converted
     *
     * 失败时回滚状态为 uploaded，并清理本次已上传的 MinIO 对象。
     *
     * @param docId 文档唯一标识
     * @return 转换后 Markdown 的 MinIO URL
     */
    String parseDocument(String docId);
}

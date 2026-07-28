package com.llmstudy.rag.controller;

import com.llmstudy.rag.dto.ApiResult;
import com.llmstudy.rag.dto.DocumentVO;
import com.llmstudy.rag.service.DocumentService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库文档管理接口
 */
@RestController
@RequestMapping("/document")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * POST /document/upload
     *
     * 上传文件到 MinIO 并创建文档元数据记录。
     * 请求格式：multipart/form-data
     *
     * 参数：
     * - file:       必填，上传的文件
     * - docTitle:   选填，文档标题（不填则取原始文件名）
     * - uploader:   必填，上传者标识
     * - visibility: 选填，可见范围（private / internal / public），默认 private
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<DocumentVO> upload(
            // MultipartFile 只代表本次 HTTP 请求中的临时文件，真正的持久化由 Service 写入 MinIO。
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "docTitle", required = false) String docTitle,
            @RequestParam("uploader") String uploader,
            @RequestParam(value = "visibility", defaultValue = "private") String visibility) {

        // Controller 只负责接收请求和包装响应；格式校验、去重、存储及元数据落库均由 Service 完成。
        DocumentVO vo = documentService.uploadDocument(file, docTitle, uploader, visibility);
        // 重复上传会复用已有文档记录，因此仍返回成功响应，但通过提示语和 duplicate 字段告知前端。
        return ApiResult.ok(vo.isDuplicate() ? "文件已上传过" : "上传成功", vo);
    }

    /**
     * GET /document/{docId}
     *
     * 根据文档 ID 查询文档元数据。
     */
    @GetMapping("/{docId}")
    public ApiResult getDocument(@PathVariable String docId) {
        DocumentVO vo = documentService.getDocument(docId);
        if (vo == null) {
            return ApiResult.fail(404, "文档不存在");
        }
        return ApiResult.ok(vo);
    }

    /**
     * GET /document/list
     *
     * 按上传者查询文档列表（待完善分页）。
     */
    @GetMapping("/list")
    public ApiResult<?> listByUploader(@RequestParam String uploader) {
        // TODO: 调用 service 分页查询
        return ApiResult.ok(null);
    }

    /**
     * POST /document/{docId}/parse
     *
     * 调用 MinerU 解析文档，会阻塞等待解析完成。
     * 流程：提交解析任务 → 轮询等待 → markdown 上传 MinIO → 更新状态为 converted
     */
    @PostMapping("/{docId}/parse")
    public ApiResult<String> parse(@PathVariable String docId) {
        // 当前解析接口为同步调用：方法返回时，解析产物已上传且文档状态已更新为 converted。
        String convertedDocUrl = documentService.parseDocument(docId);
        return ApiResult.ok("解析完成", convertedDocUrl);
    }
}

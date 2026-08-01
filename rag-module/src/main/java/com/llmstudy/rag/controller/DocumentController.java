package com.llmstudy.rag.controller;

import com.llmstudy.rag.dto.ApiResult;
import com.llmstudy.rag.dto.DocumentSplitResult;
import com.llmstudy.rag.dto.DocumentVO;
import com.llmstudy.rag.enums.DocumentStatus;
import com.llmstudy.rag.service.DocumentSegmentService;
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
    private final DocumentSegmentService documentSegmentService;

    public DocumentController(DocumentService documentService,
                              DocumentSegmentService documentSegmentService) {
        this.documentService = documentService;
        this.documentSegmentService = documentSegmentService;
    }

    /**
     * POST /document/upload
     *
     * 上传文件到 MinIO、创建文档元数据记录，并按文件类型自动处理。
     * PDF/Word 使用 MinerU；Excel 按 Sheet 创建 MySQL 表并导入数据。
     * 请求格式：multipart/form-data
     *
     * 参数：
     * - file:       必填，上传的文件
     * - docTitle:   选填，文档标题（不填则取原始文件名）
     * - uploader:   必填，上传者标识
     * - visibility: 选填，可见范围（private / internal / public），默认 private
     * - tableName:  Excel 必填，目标 MySQL 基础表名
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<DocumentVO> upload(
            // MultipartFile 只代表本次 HTTP 请求中的临时文件，真正的持久化由 Service 写入 MinIO。
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "docTitle", required = false) String docTitle,
            @RequestParam("uploader") String uploader,
            @RequestParam(value = "visibility", defaultValue = "private") String visibility,
            @RequestParam(value = "tableName", required = false) String tableName) {

        // Controller 只负责接收请求和包装响应；校验、去重、存储、落库及自动解析均由 Service 完成。
        DocumentVO vo = documentService.uploadDocument(
                file, docTitle, uploader, visibility, tableName);
        // 重复上传会复用已有文档记录，因此仍返回成功响应，但通过提示语和 duplicate 字段告知前端。
        String message;
        if (vo.isDuplicate()) {
            message = "文件已上传过";
        } else {
            message = "上传成功，文档处理中";
        }
        return ApiResult.ok(message, vo);
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
     * POST /document/{docId}/split
     *
     * 根据解析后的 Markdown 生成父子分片并保存到 knowledge_segment。
     * 当前接口只执行分片入库，segment.status 初始化为 init，不触发向量化。
     */
    @PostMapping("/{docId}/split")
    public ApiResult<DocumentSplitResult> splitDocument(
            @PathVariable String docId) {
        // Service 内部会校验文档状态、读取 converted_doc_url，并保证重复请求不会重复入库。
        DocumentSplitResult result =
                documentSegmentService.splitDocument(docId);
        String message = result.isAlreadySplit()
                ? "文档已经完成分片"
                : "文档分片完成";
        return ApiResult.ok(message, result);
    }

    /**
     * POST /document/{docId}/embed
     *
     * 将已分片的 segment 批量向量化并写入 Elasticsearch。
     * 只处理 status='init' 且 skip_embedding=0 的 segment。
     * 文档状态会流转为 vectoring → vector_stored。
     */
    @PostMapping("/{docId}/embed")
    public ApiResult<Integer> embedSegments(@PathVariable String docId) {
        int count = documentSegmentService.embedSegments(docId);
        return ApiResult.ok("向量化完成", count);
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

}

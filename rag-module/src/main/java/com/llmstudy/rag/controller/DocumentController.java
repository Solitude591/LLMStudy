package com.llmstudy.rag.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.llmstudy.rag.auth.dto.DocumentVisibilityRequest;
import com.llmstudy.rag.auth.model.AccessContext;
import com.llmstudy.rag.auth.model.DocumentVisibility;
import com.llmstudy.rag.auth.service.CurrentUserProvider;
import com.llmstudy.rag.dto.ApiResult;
import com.llmstudy.rag.dto.DocumentSplitResult;
import com.llmstudy.rag.dto.DocumentVO;
import com.llmstudy.rag.dto.DocumentVersionVO;
import com.llmstudy.rag.dto.PublishVersionRequest;
import com.llmstudy.rag.dto.VersionPublishResult;
import com.llmstudy.rag.enums.DocumentStatus;
import com.llmstudy.rag.module.knowledge.document.KnowledgeDocumentService;
import com.llmstudy.rag.module.knowledge.document.DocumentVersionPublicationService;
import com.llmstudy.rag.module.knowledge.ingestion.chunk.DocumentChunkingService;
import com.llmstudy.rag.module.knowledge.ingestion.embedding.SegmentEmbeddingService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库文档管理接口
 */
@RestController
@RequestMapping("/document")
public class DocumentController {

    private final KnowledgeDocumentService documentService;
    private final DocumentChunkingService chunkingService;
    private final SegmentEmbeddingService embeddingService;
    private final DocumentVersionPublicationService publicationService;
    private final CurrentUserProvider currentUserProvider;

    public DocumentController(KnowledgeDocumentService documentService,
                              DocumentChunkingService chunkingService,
                              SegmentEmbeddingService embeddingService,
                              DocumentVersionPublicationService publicationService,
                              CurrentUserProvider currentUserProvider) {
        this.documentService = documentService;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.publicationService = publicationService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * POST /document/upload
     *
     * 上传文件到 MinIO，创建逻辑文档和版本 1，并异步执行 RAG 处理。
     * 请求格式：multipart/form-data
     *
     * 参数：
     * - file:       必填，上传的文件
     * - docTitle:   选填，文档标题（不填则取原始文件名）
     * - visibility: 选填，可见范围（PRIVATE / ORGANIZATION / PUBLIC），默认 PRIVATE
     * - tableName:  预留参数，当前版本化 RAG 上传不处理 Excel
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<DocumentVO> upload(
            // MultipartFile 只代表本次 HTTP 请求中的临时文件，真正的持久化由 Service 写入 MinIO。
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "docTitle", required = false) String docTitle,
            @RequestParam(value = "visibility", defaultValue = "PRIVATE") String visibility,
            @RequestParam(value = "tableName", required = false) String tableName) {

        // Controller 只负责接收请求和包装响应；校验、去重、存储、落库及自动解析均由 Service 完成。
        // ownerUserId、uploadedBy 和组织归属都由该身份派生，不接收同名请求参数。
        AccessContext actor = currentUserProvider.requireAccessContext();
        DocumentVO vo = documentService.uploadDocument(
                file, docTitle, actor, DocumentVisibility.from(visibility), tableName);
        return ApiResult.ok("上传成功，文档处理中", vo);
    }

    /**
     * POST /document/{docId}/versions
     *
     * 为已有逻辑文档创建新版本。新版本完成向量化后进入 READY，
     * 显式调用 publish 接口前不会影响当前在线版本。
     */
    @PostMapping(value = "/{docId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResult<DocumentVO> uploadNewVersion(
            @PathVariable String docId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "changeSummary", required = false) String changeSummary) {
        AccessContext actor = currentUserProvider.requireAccessContext();
        DocumentVO vo = documentService.uploadNewVersion(
                docId, file, actor, changeSummary);
        return ApiResult.ok("新版本上传成功，处理中", vo);
    }

    /**
     * GET /document/{docId}
     *
     * 根据文档 ID 查询文档元数据。
     */
    @GetMapping("/{docId}")
    public ApiResult getDocument(@PathVariable String docId) {
        DocumentVO vo = documentService.getDocument(
                docId, currentUserProvider.requireAccessContext());
        if (vo == null) {
            return ApiResult.fail(404, "文档不存在");
        }
        return ApiResult.ok(vo);
    }

    /** 查询文档全部版本，供版本管理页面展示。 */
    @GetMapping("/{docId}/versions")
    public ApiResult<?> listVersions(@PathVariable String docId) {
        List<DocumentVersionVO> versions = documentService.listVersions(
                docId, currentUserProvider.requireAccessContext());
        if (versions == null) {
            return ApiResult.fail(404, "文档不存在");
        }
        return ApiResult.ok(versions);
    }

    /** 查询指定版本，前端可轮询 processingStatus/releaseStatus。 */
    @GetMapping("/{docId}/versions/{versionId}")
    public ApiResult<?> getVersion(
            @PathVariable String docId,
            @PathVariable String versionId) {
        DocumentVersionVO version = documentService.getVersion(
                docId, versionId, currentUserProvider.requireAccessContext());
        if (version == null) {
            return ApiResult.fail(404, "文档版本不存在");
        }
        return ApiResult.ok(version);
    }

    /**
     * POST /document/{versionId}/split
     *
     * 根据解析后的 Markdown 生成父子分片并保存到 knowledge_segment。
     * 当前接口只执行分片入库，segment.status 初始化为 init，不触发向量化。
     */
    @PostMapping("/{versionId}/split")
    @SaCheckRole("SYS_ADMIN")
    public ApiResult<DocumentSplitResult> splitDocument(
            @PathVariable String versionId) {
        // Service 内部会校验版本状态、读取 converted_doc_url，并保证重复请求不会重复入库。
        DocumentSplitResult result =
                chunkingService.splitDocument(versionId);
        String message = result.isAlreadySplit()
                ? "文档已经完成分片"
                : "文档分片完成";
        return ApiResult.ok(message, result);
    }

    /**
     * POST /document/{versionId}/embed
     *
     * 将已分片的 segment 批量向量化并写入 Elasticsearch。
     * 只处理 status='INIT' 且 skip_embedding=0 的 segment。
     * 版本状态会流转为 vectoring → vector_stored（READY）。
     */
    @PostMapping("/{versionId}/embed")
    @SaCheckRole("SYS_ADMIN")
    public ApiResult<Integer> embedSegments(@PathVariable String versionId) {
        int count = embeddingService.embedSegments(versionId);
        return ApiResult.ok("向量化完成", count);
    }

    /**
     * 发布 READY 版本；目标为 ARCHIVED 时表示回滚到该历史版本。
     */
    @PostMapping("/{docId}/versions/{versionId}/publish")
    public ApiResult<VersionPublishResult> publishVersion(
            @PathVariable String docId,
            @PathVariable String versionId,
            @RequestBody(required = false) PublishVersionRequest request) {
        String expectedCurrentVersionId = request == null
                ? null : request.expectedCurrentVersionId();
        VersionPublishResult result = publicationService.publishVersion(
                docId, versionId, expectedCurrentVersionId,
                currentUserProvider.requireAccessContext());
        return ApiResult.ok(result.switched() ? "版本发布成功" : "版本已经是当前版本", result);
    }

    /**
     * GET /document/list
     *
     * 返回当前用户可读的文档列表，不接收 userId 或 uploader 查询参数。
     */
    @GetMapping("/list")
    public ApiResult<?> listAccessibleDocuments() {
        return ApiResult.ok(documentService.listAccessibleDocuments(
                currentUserProvider.requireAccessContext()));
    }

    /**
     * 修改文档共享范围；请求只包含 visibility，组织 ID 由服务端推导。
     */
    @PatchMapping("/{docId}/visibility")
    public ApiResult<?> updateVisibility(@PathVariable String docId,
                                         @RequestBody DocumentVisibilityRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        DocumentVO document = documentService.updateVisibility(
                docId, DocumentVisibility.from(request.visibility()),
                currentUserProvider.requireAccessContext());
        return document == null
                ? ApiResult.fail(404, "文档不存在") : ApiResult.ok(document);
    }

}

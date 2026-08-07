package com.llmstudy.rag.module.knowledge.document;

import com.llmstudy.rag.client.VisionClient;
import com.llmstudy.rag.config.MinioProperties;
import com.llmstudy.rag.dto.DocumentParseResult;
import com.llmstudy.rag.dto.DocumentVO;
import com.llmstudy.rag.dto.DocumentVersionVO;
import com.llmstudy.rag.dto.MineruContentElement;
import com.llmstudy.rag.entity.KnowledgeDocument;
import com.llmstudy.rag.entity.KnowledgeDocumentVersion;
import com.llmstudy.rag.enums.DocumentReleaseStatus;
import com.llmstudy.rag.enums.DocumentStatus;
import com.llmstudy.rag.module.knowledge.ingestion.event.DocumentUploadedEvent;
import com.llmstudy.rag.mapper.KnowledgeDocumentMapper;
import com.llmstudy.rag.mapper.KnowledgeDocumentVersionMapper;
import com.llmstudy.rag.module.knowledge.ingestion.DocumentStageAlreadyRunningException;
import com.llmstudy.rag.module.knowledge.model.DocumentProcessingOutcome;
import com.llmstudy.rag.module.knowledge.ingestion.image.MarkdownImageProcessor;
import com.llmstudy.rag.module.knowledge.ingestion.parser.DocumentParseContext;
import com.llmstudy.rag.module.knowledge.ingestion.parser.DocumentParserRouter;
import com.llmstudy.rag.util.SnowflakeIdGenerator;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 知识文档默认实现，以物理版本为主键协调 MinIO、文档解析器与图片处理流水线。
 *
 * <p>状态迁移均使用 Mapper CAS，外部调用失败时仅回退自己抢占的阶段，
 * 避免覆盖并发任务已推进的状态。</p>
 */
@Service
public class DefaultKnowledgeDocumentService implements KnowledgeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(DefaultKnowledgeDocumentService.class);

    /** MinIO 原始文件目录 */
    private static final String RAW_DIR = "raw";
    /** MinIO 解析后 markdown 目录 */
    private static final String CONVERTED_DIR = "converted";
    /** MinIO 解析后图片子目录 */
    private static final String IMAGES_DIR = "images";

    /** 允许上传的文件类型 */
    private static final Set<String> ALLOWED_FILE_TYPES = Set.of(
            "pdf", "doc", "docx", "csv",
            "xls", "xlsx", "ppt", "pptx"
    );

    private static final Set<String> EXCEL_FILE_TYPES = Set.of("xls", "xlsx");

    /** 最大文件大小：50MB */
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;

    /** 负责原文件、解析后 Markdown、content_list 和图片的对象存储操作。 */
    private final MinioClient minioClient;

    /** 提供 MinIO 桶名和访问端点，用于选择存储位置并拼接文档访问 URL。 */
    private final MinioProperties minioProperties;

    /** 负责逻辑文档元数据查询与版本指针的原子更新。 */
    private final KnowledgeDocumentMapper documentMapper;

    /** 负责物理版本快照、处理进度与发布状态的原子更新。 */
    private final KnowledgeDocumentVersionMapper versionMapper;

    /** 根据文件扩展名把统一解析请求分发给 PDF、Word 等解析器。 */
    private final DocumentParserRouter parserRouter;

    /** 对 MinerU 提取出的图片生成语义描述，供 Markdown 和后续分片使用。 */
    private final VisionClient visionClient;

    /** 负责识别并改写 Markdown/content_list 中的本地图片引用。 */
    private final MarkdownImageProcessor imageProcessor;

    /** 将结构化 content_list 序列化为 JSON 后保存到 MinIO。 */
    private final JsonMapper objectMapper;

    /** Spring 事件发布器；上传完成后发布 DocumentUploadedEvent 触发异步流水线。 */
    private final ApplicationEventPublisher eventPublisher;

    /** 生成文档唯一 ID（雪花算法），替代随机 UUID 以获得对 MySQL 索引友好的有序 ID。 */
    private final SnowflakeIdGenerator idGenerator;

    /** 在数据库事务内创建逻辑文档与物理版本记录，隔离 MinIO 等网络操作。 */
    private final DocumentVersionPersistenceService versionPersistenceService;

    public DefaultKnowledgeDocumentService(MinioClient minioClient,
                               MinioProperties minioProperties,
                               KnowledgeDocumentMapper documentMapper,
                               KnowledgeDocumentVersionMapper versionMapper,
                               DocumentParserRouter parserRouter,
                               VisionClient visionClient,
                               MarkdownImageProcessor imageProcessor,
                               JsonMapper objectMapper,
                               ApplicationEventPublisher eventPublisher,
                               SnowflakeIdGenerator idGenerator,
                               DocumentVersionPersistenceService versionPersistenceService) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
        this.documentMapper = documentMapper;
        this.versionMapper = versionMapper;
        this.parserRouter = parserRouter;
        this.visionClient = visionClient;
        this.imageProcessor = imageProcessor;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.idGenerator = idGenerator;
        this.versionPersistenceService = versionPersistenceService;
    }

    /** {@inheritDoc} */
    @Override
    public DocumentVO uploadDocument(MultipartFile file,
                                     String docTitle,
                                     String uploader,
                                     String visibility,
                                     String tableName) {
        // 1. 参数校验
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        // todo 这里的上传者后面根据登录用户来获取（暂无实现登录功能）
        if (uploader == null || uploader.isBlank()) {
            throw new IllegalArgumentException("上传者不能为空");
        }

        // 原始文件名用于提取扩展名及保留展示信息；文件大小用于上传限制和 MinIO 流式写入。
        String originalName = file.getOriginalFilename();
        String fileType = extractFileType(originalName);
        long fileSize = file.getSize();

        // 先在进入存储层之前拒绝不支持的格式，避免产生无法解析或存在安全风险的对象。
        if (!ALLOWED_FILE_TYPES.contains(fileType)) {
            throw new IllegalArgumentException(
                    "不支持的文件类型: ." + fileType + "，仅支持: " + ALLOWED_FILE_TYPES);
        }
        // Excel 依赖旧 knowledge_document 的文件级字段，新版本模型暂无法承载，
        // 本期先把它从版本流水线中隔离，待 Excel 迁移后再放行。
        if (EXCEL_FILE_TYPES.contains(fileType)) {
            throw new IllegalArgumentException(
                    "Excel 导入暂未迁移到版本模型，本期仅支持 PDF/Word 等 RAG 文档");
        }
        // 上传入口与解析器注册表必须保持一致，避免文件已经落库后才发现没有处理策略，
        // 导致版本永久停留在 UPLOADED。
        if (!parserRouter.supports(fileType)) {
            throw new IllegalArgumentException("暂不支持解析该文件类型: ." + fileType);
        }
        // 限制上传体积，防止单次请求过度占用应用内存、网络带宽和对象存储空间。
        if (fileSize > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                    "文件过大: " + (fileSize / 1024 / 1024) + "MB，最大允许 50MB");
        }

        // 计算文件内容 SHA-256，作为物理版本的内容哈希（uk_document_version_hash 唯一索引依赖它）。
        String contentHash = calculateFileSha256(file);

        // 标题与可见范围在 Service 再做一次兜底，避免非 HTTP 调用绕过 Controller 默认值。
        if (docTitle == null || docTitle.isBlank()) {
            docTitle = stripExtension(originalName);
        }
        // todo 权限后面再做；accessible_by 本期只保存、不参与鉴权。
        if (visibility == null || visibility.isBlank()) {
            visibility = "private";
        }

        // 2. 生成 doc_id 与 version_id（雪花算法，本地生成、趋势递增，对 MySQL 聚簇索引友好）
        String docId = String.valueOf(idGenerator.nextId());
        String versionId = String.valueOf(idGenerator.nextId());

        // 3. 上传原始文件到 MinIO（documents/{docId}/versions/{versionId}/raw/{originalName}）
        // 对象键按「文档 + 版本」双层隔离，后续解析产物落在同一版本前缀下，避免覆盖其他版本。
        String objectKey = buildRawObjectKey(docId, versionId, originalName);
        try {
            // 直接使用上传流写入 MinIO，避免先把整个文件读取到 JVM 堆内存。
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(objectKey)
                    .stream(file.getInputStream(), fileSize, -1)
                    .contentType(file.getContentType())
                    .build());
            log.info("原始文件上传 MinIO 成功: bucket={}, object={}", minioProperties.getBucketName(), objectKey);
        } catch (Exception e) {
            log.error("原始文件上传 MinIO 失败: bucket={}, object={}", minioProperties.getBucketName(), objectKey, e);
            throw new RuntimeException("文件存储失败，请稍后重试", e);
        }

        // 持久化可访问 URL，MinerU 解析器会让服务端主动拉取原始文件。
        String docUrl = buildDocUrl(objectKey);

        // 4. 数据库事务内创建逻辑文档与版本 1。
        // 只有 MinIO 上传成功后才落库，保证库中的 docUrl 至少对应一个已写入对象。
        // 事务只覆盖本地数据库操作，不包含任何网络调用，避免长事务占用连接。
        try {
            versionPersistenceService.createInitialVersion(
                    docId,
                    versionId,
                    docTitle,
                    visibility,
                    contentHash,
                    fileType,
                    uploader,
                    docUrl,
                    objectKey);
        } catch (RuntimeException e) {
            // 落库失败时清理刚上传的 MinIO 对象，避免留下孤儿文件。
            removeObjectQuietly(objectKey);
            throw e;
        }

        // 5. 上传完成后发布事件，由异步监听器接管后续解析、分片、向量化流程。
        // 接口立即返回，不阻塞。事件携带版本 ID，整条流水线以版本为主键驱动。
        log.info("文档记录创建成功: docId={}, versionId={}, title={}", docId, versionId, docTitle);
        eventPublisher.publishEvent(new DocumentUploadedEvent(this, versionId));

        return buildUploadedVO(docId, versionId, 1, docTitle, originalName, fileType,
                fileSize, contentHash, uploader, docUrl, visibility);
    }

    /** {@inheritDoc} */
    @Override
    public DocumentVO uploadNewVersion(String docId,
                                       MultipartFile file,
                                       String uploader,
                                       String changeSummary) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId 不能为空");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (uploader == null || uploader.isBlank()) {
            throw new IllegalArgumentException("上传者不能为空");
        }
        if (changeSummary != null && changeSummary.strip().length() > 512) {
            throw new IllegalArgumentException("版本变更说明不能超过 512 个字符");
        }

        KnowledgeDocument document = documentMapper.findByDocId(docId);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在: " + docId);
        }

        String originalName = file.getOriginalFilename();
        String fileType = extractFileType(originalName);
        long fileSize = file.getSize();
        if (!ALLOWED_FILE_TYPES.contains(fileType)) {
            throw new IllegalArgumentException(
                    "不支持的文件类型: ." + fileType + "，仅支持: " + ALLOWED_FILE_TYPES);
        }
        if (EXCEL_FILE_TYPES.contains(fileType)) {
            throw new IllegalArgumentException(
                    "Excel 导入暂未迁移到版本模型，本期仅支持 PDF/Word 等 RAG 文档");
        }
        if (!parserRouter.supports(fileType)) {
            throw new IllegalArgumentException("暂不支持解析该文件类型: ." + fileType);
        }
        if (fileSize > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                    "文件过大: " + (fileSize / 1024 / 1024) + "MB，最大允许 50MB");
        }

        String contentHash = calculateFileSha256(file);
        // 事务外预检查用于避免无意义上传；事务内还会在文档行锁下再次检查，保证并发安全。
        KnowledgeDocumentVersion duplicate =
                versionMapper.findByDocIdAndContentHash(docId, contentHash);
        if (duplicate != null) {
            throw new DocumentVersionConflictException(
                    "相同内容的版本已经存在: versionId=" + duplicate.getVersionId());
        }

        String versionId = String.valueOf(idGenerator.nextId());
        String objectKey = buildRawObjectKey(docId, versionId, originalName);
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(objectKey)
                    .stream(file.getInputStream(), fileSize, -1)
                    .contentType(file.getContentType())
                    .build());
            log.info("新版本原始文件上传成功: docId={}, versionId={}, object={}",
                    docId, versionId, objectKey);
        } catch (Exception e) {
            log.error("新版本原始文件上传失败: docId={}, versionId={}, object={}",
                    docId, versionId, objectKey, e);
            throw new RuntimeException("文件存储失败，请稍后重试", e);
        }

        String docUrl = buildDocUrl(objectKey);
        KnowledgeDocumentVersion version;
        try {
            version = versionPersistenceService.createNextVersion(
                    docId,
                    versionId,
                    contentHash,
                    fileType,
                    uploader,
                    docUrl,
                    objectKey,
                    changeSummary);
        } catch (RuntimeException e) {
            removeObjectQuietly(objectKey);
            throw e;
        }

        // 只启动新版本自己的离线处理流程，不更新 current_version_id。
        eventPublisher.publishEvent(new DocumentUploadedEvent(this, versionId));
        log.info("文档新版本创建成功: docId={}, versionId={}, versionNo={}, currentVersionId={}",
                docId, versionId, version.getVersionNo(), document.getCurrentVersionId());

        return buildUploadedVO(
                docId,
                versionId,
                version.getVersionNo(),
                document.getDocTitle(),
                originalName,
                fileType,
                fileSize,
                contentHash,
                uploader,
                docUrl,
                document.getAccessibleBy());
    }

    /** {@inheritDoc} */
    @Override
    public DocumentVO getDocument(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId 不能为空");
        }

        KnowledgeDocument doc = documentMapper.findByDocId(docId);
        if (doc == null) {
            return null;
        }
        // 已发布后默认返回当前生效快照；首次发布前才回退到最新的准备中版本。
        KnowledgeDocumentVersion visibleVersion = doc.getCurrentVersionId() == null
                ? versionMapper.findLatestByDocId(docId)
                : versionMapper.findByDocIdAndVersionId(docId, doc.getCurrentVersionId());
        return toVO(doc, visibleVersion);
    }

    /** {@inheritDoc} */
    @Override
    public List<DocumentVersionVO> listVersions(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId 不能为空");
        }
        KnowledgeDocument document = documentMapper.findByDocId(docId);
        if (document == null) {
            return null;
        }
        return versionMapper.findByDocId(docId).stream()
                .map(version -> toVersionVO(document, version))
                .toList();
    }

    /** {@inheritDoc} */
    @Override
    public DocumentVersionVO getVersion(String docId, String versionId) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId 不能为空");
        }
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("versionId 不能为空");
        }
        KnowledgeDocument document = documentMapper.findByDocId(docId);
        if (document == null) {
            return null;
        }
        KnowledgeDocumentVersion version =
                versionMapper.findByDocIdAndVersionId(docId, versionId);
        return version == null ? null : toVersionVO(document, version);
    }

    /** {@inheritDoc} */
    @Override
    public DocumentProcessingOutcome processDocument(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("versionId 不能为空");
        }
        // 从数据库恢复版本记录：此时可能在上传后几秒才被异步线程调度到，
        // 版本状态可能已被并发操作改变，parseUploadedDocument 内部会做 CAS 校验。
        KnowledgeDocumentVersion version = versionMapper.findByVersionId(versionId);
        if (version == null) {
            throw new IllegalArgumentException("版本不存在: " + versionId);
        }
        // 重复上传或迟到的 DocumentUploadedEvent 不应让已经推进的版本重新解析，
        // 更不能在监听器的失败分支中把状态回退到 uploaded。
        if (version.getDocumentStatus() != DocumentStatus.UPLOADED) {
            log.info("忽略重复上传事件，版本已经离开 uploaded 状态: versionId={}, status={}",
                    versionId, version.getProcessingStatus());
            return DocumentProcessingOutcome.SKIPPED;
        }
        // 未注册解析策略的格式保持 uploaded 状态，等待后续扩展新策略即可自动纳入。
        if (!parserRouter.supports(version.getFileType())) {
            log.info("文件类型未注册解析策略，版本保持 uploaded 状态: versionId={}, type={}",
                    versionId, version.getFileType());
            return DocumentProcessingOutcome.SKIPPED;
        }
        parseUploadedDocument(version);
        return DocumentProcessingOutcome.RAG_PARSED;
    }

    /**
     * 执行版本解析。MinerU 根据版本记录中的公网 docUrl 拉取原始文件。
     */
    private void parseUploadedDocument(KnowledgeDocumentVersion version) {
        String versionId = version.getVersionId();
        String docId = version.getDocId();

        if (version.getDocumentStatus() != DocumentStatus.UPLOADED) {
            throw new IllegalStateException("版本状态不正确，当前状态: " + version.getProcessingStatus());
        }

        // 原子抢占解析权，防止并发事件触发两次 MinerU 或重复写入产物。
        if (versionMapper.compareAndSetProcessingStatus(
                versionId, DocumentStatus.CONVERTING, DocumentStatus.UPLOADED) != 1) {
            throw new DocumentStageAlreadyRunningException(
                    "版本解析阶段已经被其他线程抢占: " + versionId);
        }
        log.info("开始解析版本: versionId={}, docId={}", versionId, docId);

        // 本次已写入 MinIO 的对象，失败时统一回收，避免留下孤儿文件
        List<String> uploadedKeys = new ArrayList<>();

        try {
            DocumentParseContext parseContext = new DocumentParseContext(version);

            // 路由器依据扩展名选择策略，所有策略统一返回 DocumentParseResult。
            DocumentParseResult parseResult = parserRouter.parse(parseContext);
            String markdown = parseResult.getMarkdown();
            if (markdown == null || markdown.isBlank()) {
                throw new IllegalStateException("文档解析结果为空");
            }
            log.info("文档解析产物获取完成: versionId={}, type={}, markdown={}字符, 图片={}张, contentList={}项",
                    versionId, version.getFileType(), markdown.length(), parseResult.getImages().size(),
                    parseResult.getContentList().size());

            // 3. 上传 Markdown 中实际引用到的图片
            // 映射的 key 保持 Markdown 中的原始相对路径，value 是图片上传后的可访问 URL。
            Map<String, String> urlMapping = uploadReferencedImages(docId, versionId, parseResult, uploadedKeys);

            // 4. 生成图片描述：视觉模型优先，失败回落到 PDF 原文图注
            // 只描述正文实际引用的图片，跳过 MinerU ZIP 中未被使用的中间图片。
            Map<String, String> descriptions = buildImageDescriptions(parseResult, urlMapping.keySet());

            // 5. 改写 Markdown 与 content_list 中的图片链接
            // 改写后 Markdown 不再依赖临时 ZIP 内的相对路径，可独立从 MinIO 读取和展示。
            String rewrittenMarkdown = imageProcessor.rewriteImages(markdown, urlMapping, descriptions);
            imageProcessor.rewriteContentList(parseResult.getContentList(), urlMapping, descriptions);

            // 6. 上传 Markdown（按版本隔离）
            String mdObjectKey = buildConvertedObjectKey(docId, versionId);
            putObject(mdObjectKey, rewrittenMarkdown.getBytes(StandardCharsets.UTF_8),
                    "text/markdown; charset=utf-8");
            uploadedKeys.add(mdObjectKey);

            // 7. 上传 content_list.json，供后续「基于标题的父子分段」直接消费
            if (parseResult.hasContentList()) {
                String contentListKey = buildContentListObjectKey(docId, versionId);
                // content_list 保留标题、页码和图片描述，后续分片无需重新解析 Markdown 猜测结构。
                byte[] contentListBytes = objectMapper
                        .writeValueAsString(parseResult.getContentList())
                        .getBytes(StandardCharsets.UTF_8);
                putObject(contentListKey, contentListBytes, "application/json; charset=utf-8");
                uploadedKeys.add(contentListKey);
            }

            // 8. 全部成功后才写回版本记录
            // 先完成全部对象上传，再将状态切到 converted，避免其他流程读到不完整的解析产物。
            String convertedDocUrl = buildDocUrl(mdObjectKey);
            int updated = versionMapper.updateConverted(
                    versionId,
                    convertedDocUrl,
                    DocumentStatus.CONVERTED,
                    DocumentStatus.CONVERTING);
            if (updated != 1) {
                throw new IllegalStateException("更新版本解析结果失败: versionId=" + versionId);
            }

            log.info("文档解析流程完成: versionId={}, 图片={}张, 描述={}条, convertedUrl={}",
                    versionId, urlMapping.size(), descriptions.size(), convertedDocUrl);
        } catch (Exception e) {
            log.error("文档解析失败，回滚状态并清理产物: versionId={}", versionId, e);
            // 只删除本次解析新写入的对象，原始文件仍保留；重新触发即可重试。
            uploadedKeys.forEach(this::removeObjectQuietly);
            // 仅允许 converting 回退到 uploaded，防止迟到的失败结果覆盖更新状态。
            versionMapper.compareAndSetProcessingStatusWithError(
                    versionId,
                    DocumentStatus.UPLOADED,
                    DocumentStatus.CONVERTING,
                    truncateError("解析失败: " + e.getMessage()));
            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        }
    }

    private String truncateError(String message) {
        if (message == null) {
            return "未知错误";
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000) + "...";
    }

    /**
     * 上传 Markdown 中真正引用到的图片到 MinIO。
     *
     * <p>只上传被引用的图片：MinerU 的 ZIP 可能包含中间产物图片，全量上传会浪费存储。
     * 同一图片被多次引用时只上传一次。</p>
     *
     * @return 图片相对路径 → MinIO 公网 URL
     */
    private Map<String, String> uploadReferencedImages(String docId,
                                                       String versionId,
                                                       DocumentParseResult parseResult,
                                                       List<String> uploadedKeys) {
        // 从语法树而非正则表达式提取图片，避免误识别代码块或普通文本中的类似字符串。
        Set<String> referenced = imageProcessor.extractLocalImagePaths(parseResult.getMarkdown());
        if (referenced.isEmpty()) {
            return Map.of();
        }

        Map<String, String> urlMapping = new LinkedHashMap<>();
        for (String rawPath : referenced) {
            // 统一斜杠并移除 ./ 前缀，保证 Markdown 路径可以和 ZIP 条目名稳定匹配。
            String path = imageProcessor.normalizePath(rawPath);
            if (path == null) {
                continue;
            }

            DocumentParseResult.ImageResource image = resolveImage(parseResult, path);
            if (image == null) {
                // 继续生成 converted Markdown 会留下永久失效的相对链接，整次解析应当回滚。
                throw new IllegalStateException(
                        "解析产物缺少 Markdown 引用的图片: " + rawPath);
            }

            // 图片对象名使用路径哈希，既避免特殊字符问题，也防止清洗后同名图片互相覆盖。
            String objectKey = buildImageObjectKey(docId, versionId, path);
            putObject(objectKey, image.getData(), image.getContentType());
            uploadedKeys.add(objectKey);
            // 用原始引用路径作 key，替换阶段才能与 Markdown 中的字面量对齐
            urlMapping.put(rawPath, buildDocUrl(objectKey));
        }

        log.info("图片上传完成: docId={}, 成功={}张", docId, urlMapping.size());
        return urlMapping;
    }

    /**
     * 在 ZIP 图片表中定位图片，兼容 ZIP 条目带顶层目录前缀的情况。
     */
    private DocumentParseResult.ImageResource resolveImage(DocumentParseResult parseResult, String path) {
        Map<String, DocumentParseResult.ImageResource> images = parseResult.getImages();

        DocumentParseResult.ImageResource direct = images.get(path);
        if (direct != null) {
            return direct;
        }
        // ZIP 条目可能是 {taskId}/images/xxx.jpg，而 Markdown 里写的是 images/xxx.jpg
        for (Map.Entry<String, DocumentParseResult.ImageResource> entry : images.entrySet()) {
            if (entry.getKey().endsWith("/" + path)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 生成图片描述。
     *
     * <p>合并两个来源：PDF 原文图注（来自 content_list.json 的 image_caption）
     * 与视觉模型生成的描述。原文图注由文档作者撰写，可信度高于模型推测，
     * 因此拼在描述前面；视觉模型不可用或调用失败时，仅用图注也能保证图片可被检索。</p>
     */
    private Map<String, String> buildImageDescriptions(DocumentParseResult parseResult,
                                                       Set<String> referencedPaths) {
        // 先收集原文图注：content_list 的 img_path → caption
        Map<String, String> captions = new LinkedHashMap<>();
        for (MineruContentElement element : parseResult.getContentList()) {
            if (!element.isVisualElement()) {
                continue;
            }
            String path = imageProcessor.normalizePath(element.getImgPath());
            String caption = element.firstCaption();
            if (path != null && caption != null && !caption.isBlank()) {
                captions.put(path, caption.trim());
            }
        }

        // 只为真正被引用且已上传的图片调用视觉模型
        Map<String, DocumentParseResult.ImageResource> toDescribe = new LinkedHashMap<>();
        for (String rawPath : referencedPaths) {
            String path = imageProcessor.normalizePath(rawPath);
            DocumentParseResult.ImageResource image = resolveImage(parseResult, path);
            if (image != null) {
                toDescribe.put(rawPath, image);
            }
        }

        Map<String, String> visionResults = visionClient.describeAll(toDescribe);

        // 合并：图注在前，模型描述在后
        Map<String, String> merged = new LinkedHashMap<>();
        for (String rawPath : referencedPaths) {
            String path = imageProcessor.normalizePath(rawPath);
            String caption = captions.get(path);
            String vision = visionResults.get(rawPath);

            String description;
            if (caption != null && vision != null) {
                description = caption + "。" + vision;
            } else if (caption != null) {
                description = caption;
            } else {
                description = vision;
            }

            if (description != null && !description.isBlank()) {
                merged.put(rawPath, description);
            }
        }
        return merged;
    }

    /**
     * 上传字节内容到 MinIO。
     */
    private void putObject(String objectKey, byte[] content, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .contentType(contentType)
                    .build());
            log.debug("MinIO 上传成功: object={}, size={}B", objectKey, content.length);
        } catch (Exception e) {
            throw new RuntimeException("MinIO 上传失败: " + objectKey, e);
        }
    }

    // ========== 私有辅助方法 ==========

    /**
     * 构造首次上传的响应 VO。
     *
     * <p>新模型下文件级字段保存在版本表，逻辑文档表不再冗余存储；
     * 这里用上传时的本地数据直接构建，避免再查一次数据库。</p>
     */
    private DocumentVO buildUploadedVO(String docId,
                                       String versionId,
                                       int versionNo,
                                       String docTitle,
                                       String originalName,
                                       String fileType,
                                       long fileSize,
                                       String contentHash,
                                       String uploader,
                                       String docUrl,
                                       String visibility) {
        return DocumentVO.builder()
                .docId(docId)
                .versionId(versionId)
                .versionNo(versionNo)
                .contentHash(contentHash)
                .docTitle(docTitle)
                .originalName(originalName)
                .fileType(fileType)
                .fileSize(fileSize)
                .uploader(uploader)
                .docUrl(docUrl)
                .docStatus(DocumentStatus.UPLOADED.value())
                .processingStatus(DocumentStatus.UPLOADED.value())
                .releaseStatus(DocumentReleaseStatus.PREPARING.value())
                .visibility(visibility)
                .duplicate(false)
                .build();
    }

    /**
     * Entity → VO 转换：逻辑文档 + 最新版本内容快照。
     */
    private DocumentVO toVO(KnowledgeDocument doc, KnowledgeDocumentVersion latest) {
        DocumentVO.Builder builder = DocumentVO.builder()
                .docId(doc.getDocId())
                .docTitle(doc.getDocTitle())
                .visibility(doc.getAccessibleBy())
                .createdAt(doc.getCreatedAt())
                .duplicate(false);
        if (latest != null) {
            builder.versionId(latest.getVersionId())
                    .versionNo(latest.getVersionNo())
                    .contentHash(latest.getContentHash())
                    .fileType(latest.getFileType())
                    .uploader(latest.getUploadedBy())
                    .docUrl(latest.getDocUrl())
                    .docStatus(latest.getProcessingStatus())
                    .processingStatus(latest.getProcessingStatus())
                    .releaseStatus(latest.getReleaseStatus())
                    .convertedDocUrl(latest.getConvertedDocUrl());
        }
        return builder.build();
    }

    private DocumentVersionVO toVersionVO(KnowledgeDocument document,
                                          KnowledgeDocumentVersion version) {
        return new DocumentVersionVO(
                version.getDocId(),
                version.getVersionId(),
                version.getVersionNo(),
                version.getContentHash(),
                version.getFileType(),
                version.getUploadedBy(),
                version.getDocUrl(),
                version.getConvertedDocUrl(),
                version.getProcessingStatus(),
                version.getReleaseStatus(),
                version.getErrorMessage(),
                version.getRetryCount(),
                version.getChangeSummary(),
                version.getReadyAt(),
                version.getPublishedAt(),
                version.getCreatedAt(),
                version.getUpdatedAt(),
                version.getVersionId().equals(document.getCurrentVersionId()));
    }

    private String calculateFileSha256(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 分块读取避免一次性加载整个上传文件；SHA-256 用作物理版本的内容哈希。
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                digest.update(buffer, 0, length);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new RuntimeException("计算文件 SHA-256 失败", e);
        }
    }

    private void removeObjectQuietly(String objectKey) {
        try {
            // 清理属于补偿操作，即使删除失败也不能覆盖最初的业务异常。
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(objectKey)
                    .build());
        } catch (Exception cleanupException) {
            log.warn("清理 MinIO 孤儿对象失败: bucket={}, object={}, error={}",
                    minioProperties.getBucketName(), objectKey, cleanupException.getMessage());
        }
    }

    /**
     * 构建原始文件 MinIO 存储路径：documents/{docId}/versions/{versionId}/raw/{safeFilename}
     *
     * <p>按「文档 + 版本」双层隔离，不同版本的同名原始文件互不覆盖。
     * 文件名仅保留 ASCII 安全字符，避免 MinIO 路径和 URL 编码问题。</p>
     */
    private String buildRawObjectKey(String docId, String versionId, String originalName) {
        String safeName = originalName != null ? sanitizeFilename(originalName) : "unknown";
        return "documents/" + docId + "/versions/" + versionId + "/" + RAW_DIR + "/" + safeName;
    }

    /**
     * 构建解析后 markdown 文件 MinIO 存储路径：
     * documents/{docId}/versions/{versionId}/converted/document.md
     */
    private String buildConvertedObjectKey(String docId, String versionId) {
        return "documents/" + docId + "/versions/" + versionId + "/" + CONVERTED_DIR + "/document.md";
    }

    /**
     * 构建 content_list.json 存储路径：
     * documents/{docId}/versions/{versionId}/converted/content_list.json
     *
     * <p>该文件是分块阶段的输入，保留了标题层级、图片描述和页码等结构化信息。</p>
     */
    private String buildContentListObjectKey(String docId, String versionId) {
        return "documents/" + docId + "/versions/" + versionId + "/" + CONVERTED_DIR + "/content_list.json";
    }

    /**
     * 构建图片存储路径：
     * documents/{docId}/versions/{versionId}/images/{路径SHA-256}.{ext}
     *
     * <p>直接“清洗”原始路径会让 a b.png 与 ab.png 等不同文件落到同一对象名。
     * 使用归一化路径的 SHA-256 可稳定去重并避免碰撞，同时保留安全的扩展名。</p>
     */
    private String buildImageObjectKey(String docId, String versionId, String imagePath) {
        String normalized = imageProcessor.normalizePath(imagePath);
        if (normalized == null) {
            throw new IllegalArgumentException("图片路径不能为空");
        }
        return "documents/" + docId + "/versions/" + versionId + "/" + IMAGES_DIR + "/"
                + sha256Hex(normalized) + safeImageExtension(normalized);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算图片路径哈希失败", e);
        }
    }

    private String safeImageExtension(String path) {
        int slashIndex = path.lastIndexOf('/');
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex <= slashIndex || dotIndex == path.length() - 1) {
            return "";
        }
        String extension = path.substring(dotIndex).toLowerCase(Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,10}") ? extension : "";
    }

    /**
     * 构造可访问的文件 URL，使用 URI 自动编码特殊字符，避免 MinerU 校验失败。
     */
    private String buildDocUrl(String objectKey) {
        try {
            String path = "/" + minioProperties.getBucketName() + "/" + objectKey;
            return new java.net.URI(minioProperties.getEndpoint().replace("://", "://") + path)
                    .normalize().toASCIIString();
        } catch (Exception e) {
            // 兜底：若 URI 构造失败，用原始拼接
            return String.format("%s/%s/%s", minioProperties.getEndpoint(),
                    minioProperties.getBucketName(), objectKey);
        }
    }

    /**
     * 文件名安全化：保留扩展名，主体部分只保留字母数字和下划线。
     */
    private String sanitizeFilename(String filename) {
        if (filename == null) {
            return "unknown";
        }
        int dotIdx = filename.lastIndexOf('.');
        String name = dotIdx > 0 ? filename.substring(0, dotIdx) : filename;
        String ext = dotIdx > 0 ? filename.substring(dotIdx) : "";

        // 非 ASCII 字符替换为 hash 后缀，保证唯一性和可读性
        String safe = name.replaceAll("[^a-zA-Z0-9_\\-]", "");
        if (safe.isEmpty()) {
            safe = "file_" + Math.abs(name.hashCode());
        }
        return safe + ext;
    }

    /**
     * 从原始文件名提取扩展名（小写）
     */
    private String extractFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    /**
     * 去掉扩展名，用作默认标题
     */
    private String stripExtension(String filename) {
        if (filename == null) {
            return "未命名文档";
        }
        if (!filename.contains(".")) {
            return filename;
        }
        return filename.substring(0, filename.lastIndexOf("."));
    }
}

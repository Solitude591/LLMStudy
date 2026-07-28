package com.llmstudy.rag.service.impl;

import com.llmstudy.rag.client.VisionClient;
import com.llmstudy.rag.config.MinioProperties;
import com.llmstudy.rag.dto.DocumentParseResult;
import com.llmstudy.rag.dto.DocumentVO;
import com.llmstudy.rag.dto.MineruContentElement;
import com.llmstudy.rag.entity.KnowledgeDocument;
import com.llmstudy.rag.mapper.KnowledgeDocumentMapper;
import com.llmstudy.rag.service.DocumentService;
import com.llmstudy.rag.service.MarkdownImageProcessor;
import com.llmstudy.rag.service.parser.DocumentParseContext;
import com.llmstudy.rag.service.parser.DocumentParserRouter;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.UUID;

@Service
public class DocumentServiceImpl implements DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);

    /** MinIO 原始文件目录 */
    private static final String RAW_DIR = "raw";
    /** MinIO 解析后 markdown 目录 */
    private static final String CONVERTED_DIR = "converted";
    /** MinIO 解析后图片子目录 */
    private static final String IMAGES_DIR = "images";

    /** 允许上传的文件类型 */
    private static final Set<String> ALLOWED_FILE_TYPES = Set.of(
            "pdf", "doc", "docx", "txt", "md", "csv",
            "xls", "xlsx", "ppt", "pptx"
    );

    /** 最大文件大小：50MB */
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final KnowledgeDocumentMapper documentMapper;
    private final DocumentParserRouter parserRouter;
    private final VisionClient visionClient;
    private final MarkdownImageProcessor imageProcessor;
    private final JsonMapper objectMapper;

    public DocumentServiceImpl(MinioClient minioClient,
                               MinioProperties minioProperties,
                               KnowledgeDocumentMapper documentMapper,
                               DocumentParserRouter parserRouter,
                               VisionClient visionClient,
                               MarkdownImageProcessor imageProcessor,
                               JsonMapper objectMapper) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
        this.documentMapper = documentMapper;
        this.parserRouter = parserRouter;
        this.visionClient = visionClient;
        this.imageProcessor = imageProcessor;
        this.objectMapper = objectMapper;
    }

    @Override
    public DocumentVO uploadDocument(MultipartFile file, String docTitle, String uploader, String visibility) {
        // 1. 参数校验
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        // todo 这里的上传者后面根据登录用户来获取（暂无实现登录功能）
        if (uploader == null || uploader.isBlank()) {
            throw new IllegalArgumentException("上传者不能为空");
        }

        String originalName = file.getOriginalFilename();
        String fileType = extractFileType(originalName);
        long fileSize = file.getSize();

        if (!ALLOWED_FILE_TYPES.contains(fileType)) {
            throw new IllegalArgumentException(
                    "不支持的文件类型: ." + fileType + "，仅支持: " + ALLOWED_FILE_TYPES);
        }
        if (fileSize > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                    "文件过大: " + (fileSize / 1024 / 1024) + "MB，最大允许 50MB");
        }

        // 按“上传者 + 文件内容 MD5”去重，避免把其他用户的私有文档直接返回。
        String fileMd5 = calculateFileMd5(file);
        KnowledgeDocument existing = documentMapper.findByUploaderAndFileMd5(uploader, fileMd5);
        if (existing != null) {
            log.info("检测到重复文件，跳过 MinIO 上传: uploader={}, fileMd5={}, docId={}",
                    uploader, fileMd5, existing.getDocId());
            return toVO(existing, true);
        }

        if (docTitle == null || docTitle.isBlank()) {
            docTitle = stripExtension(originalName);
        }
        if (visibility == null || visibility.isBlank()) {
            visibility = "private";
        }

        // 2. 生成 doc_id
        String docId = UUID.randomUUID().toString().replace("-", "");

        // 3. 上传原始文件到 MinIO（{docId}/raw/{originalName}）
        String objectKey = buildRawObjectKey(docId, originalName);
        try {
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

        String docUrl = buildDocUrl(objectKey);

        // 4. 写 MySQL：knowledge_document 表
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setDocId(docId);
        doc.setDocTitle(docTitle);
        doc.setOriginalName(originalName);
        doc.setFileType(fileType);
        doc.setFileSize(fileSize);
        doc.setFileMd5(fileMd5);
        doc.setUploader(uploader);
        doc.setDocUrl(docUrl);
        doc.setDocStatus("uploaded");
        doc.setVisibility(visibility);
        try {
            documentMapper.insert(doc);
        } catch (DuplicateKeyException e) {
            // 并发上传相同内容时，唯一索引只允许一个请求成功；清理另一个请求的孤儿对象。
            removeObjectQuietly(objectKey);
            KnowledgeDocument concurrentExisting =
                    documentMapper.findByUploaderAndFileMd5(uploader, fileMd5);
            if (concurrentExisting != null) {
                log.info("并发重复上传已拦截: uploader={}, fileMd5={}, docId={}",
                        uploader, fileMd5, concurrentExisting.getDocId());
                return toVO(concurrentExisting, true);
            }
            throw e;
        } catch (RuntimeException e) {
            removeObjectQuietly(objectKey);
            throw e;
        }

        log.info("文档记录创建成功: docId={}, title={}", docId, docTitle);

        // 5. 构造返回值
        return DocumentVO.builder()
                .docId(docId)
                .docTitle(docTitle)
                .originalName(originalName)
                .fileType(fileType)
                .fileSize(fileSize)
                .fileMd5(fileMd5)
                .uploader(uploader)
                .docUrl(docUrl)
                .docStatus("uploaded")
                .visibility(visibility)
                .duplicate(false)
                .build();
    }

    @Override
    public DocumentVO getDocument(String docId) {
        if (docId == null || docId.isBlank()) {
            throw new IllegalArgumentException("docId 不能为空");
        }

        KnowledgeDocument doc = documentMapper.findByDocId(docId);
        if (doc == null) {
            return null;
        }
        return toVO(doc);
    }

    @Override
    public String parseDocument(String docId) {
        KnowledgeDocument doc = documentMapper.findByDocId(docId);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在: " + docId);
        }
        if (!"uploaded".equals(doc.getDocStatus())) {
            throw new IllegalStateException("文档状态不正确，当前状态: " + doc.getDocStatus());
        }

        // 1. 原子抢占解析权，避免并发请求重复调用 MinerU、互相覆盖解析产物
        if (documentMapper.markConverting(docId) != 1) {
            throw new IllegalStateException("文档正在解析或状态已发生变化，请勿重复提交: " + docId);
        }
        log.info("开始解析文档: docId={}", docId);

        // 本次已写入 MinIO 的对象，失败时统一回收，避免留下孤儿文件
        List<String> uploadedKeys = new ArrayList<>();

        try {
            // 2. 根据文件类型动态路由到对应解析策略
            DocumentParseContext parseContext =
                    new DocumentParseContext(doc, buildRawObjectKey(docId, doc.getOriginalName()));
            DocumentParseResult parseResult = parserRouter.parse(parseContext);
            String markdown = parseResult.getMarkdown();
            if (markdown == null || markdown.isBlank()) {
                throw new IllegalStateException("文档解析结果为空");
            }
            log.info("文档解析产物获取完成: docId={}, type={}, markdown={}字符, 图片={}张, contentList={}项",
                    docId, doc.getFileType(), markdown.length(), parseResult.getImages().size(),
                    parseResult.getContentList().size());

            // 3. 上传 Markdown 中实际引用到的图片
            Map<String, String> urlMapping = uploadReferencedImages(docId, parseResult, uploadedKeys);

            // 4. 生成图片描述：视觉模型优先，失败回落到 PDF 原文图注
            Map<String, String> descriptions = buildImageDescriptions(parseResult, urlMapping.keySet());

            // 5. 改写 Markdown 与 content_list 中的图片链接
            String rewrittenMarkdown = imageProcessor.rewriteImages(markdown, urlMapping, descriptions);
            imageProcessor.rewriteContentList(parseResult.getContentList(), urlMapping, descriptions);

            // 6. 上传 Markdown
            String mdObjectKey = buildConvertedObjectKey(docId);
            putObject(mdObjectKey, rewrittenMarkdown.getBytes(StandardCharsets.UTF_8),
                    "text/markdown; charset=utf-8");
            uploadedKeys.add(mdObjectKey);

            // 7. 上传 content_list.json，供后续「基于标题的父子分段」直接消费
            if (parseResult.hasContentList()) {
                String contentListKey = buildContentListObjectKey(docId);
                byte[] contentListBytes = objectMapper
                        .writeValueAsString(parseResult.getContentList())
                        .getBytes(StandardCharsets.UTF_8);
                putObject(contentListKey, contentListBytes, "application/json; charset=utf-8");
                uploadedKeys.add(contentListKey);
            }

            // 8. 全部成功后才写回文档记录
            String convertedDocUrl = buildDocUrl(mdObjectKey);
            int updated = documentMapper.updateConverted(docId, convertedDocUrl, "converted");
            if (updated != 1) {
                throw new IllegalStateException("更新文档解析结果失败: docId=" + docId);
            }

            log.info("文档解析流程完成: docId={}, 图片={}张, 描述={}条, convertedUrl={}",
                    docId, urlMapping.size(), descriptions.size(), convertedDocUrl);
            return convertedDocUrl;
        } catch (Exception e) {
            log.error("文档解析失败，回滚状态并清理产物: docId={}", docId, e);
            uploadedKeys.forEach(this::removeObjectQuietly);
            documentMapper.resetConverting(docId);
            throw new RuntimeException("文档解析失败: " + e.getMessage(), e);
        }
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
                                                       DocumentParseResult parseResult,
                                                       List<String> uploadedKeys) {
        Set<String> referenced = imageProcessor.extractLocalImagePaths(parseResult.getMarkdown());
        if (referenced.isEmpty()) {
            return Map.of();
        }

        Map<String, String> urlMapping = new LinkedHashMap<>();
        for (String rawPath : referenced) {
            String path = imageProcessor.normalizePath(rawPath);
            if (path == null) {
                continue;
            }

            DocumentParseResult.ImageResource image = resolveImage(parseResult, path);
            if (image == null) {
                // 继续生成 converted Markdown 会留下永久失效的相对链接，整次解析应当回滚。
                throw new IllegalStateException(
                        "MinerU 产物缺少 Markdown 引用的图片: " + rawPath);
            }

            String objectKey = buildImageObjectKey(docId, path);
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
     * Entity → VO 转换
     */
    private DocumentVO toVO(KnowledgeDocument doc) {
        return toVO(doc, false);
    }

    private DocumentVO toVO(KnowledgeDocument doc, boolean duplicate) {
        return DocumentVO.builder()
                .docId(doc.getDocId())
                .docTitle(doc.getDocTitle())
                .originalName(doc.getOriginalName())
                .fileType(doc.getFileType())
                .fileSize(doc.getFileSize())
                .fileMd5(doc.getFileMd5())
                .uploader(doc.getUploader())
                .docUrl(doc.getDocUrl())
                .docStatus(doc.getDocStatus())
                .convertedDocUrl(doc.getConvertedDocUrl())
                .visibility(doc.getVisibility())
                .createdAt(doc.getCreatedAt())
                .duplicate(duplicate)
                .build();
    }

    private String calculateFileMd5(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                digest.update(buffer, 0, length);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new RuntimeException("计算文件 MD5 失败", e);
        }
    }

    private void removeObjectQuietly(String objectKey) {
        try {
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
     * 构建原始文件 MinIO 存储路径：{docId}/raw/{safeFilename}
     * 文件名仅保留 ASCII 安全字符，避免 MinIO 路径和 URL 编码问题。
     */
    private String buildRawObjectKey(String docId, String originalName) {
        String safeName = originalName != null ? sanitizeFilename(originalName) : "unknown";
        return docId + "/" + RAW_DIR + "/" + safeName;
    }

    /**
     * 构建解析后 markdown 文件 MinIO 存储路径：{docId}/converted/{docId}.md
     */
    private String buildConvertedObjectKey(String docId) {
        return docId + "/" + CONVERTED_DIR + "/" + docId + ".md";
    }

    /**
     * 构建 content_list.json 存储路径：{docId}/converted/{docId}_content_list.json
     *
     * <p>该文件是分块阶段的输入，保留了标题层级、图片描述和页码等结构化信息。</p>
     */
    private String buildContentListObjectKey(String docId) {
        return docId + "/" + CONVERTED_DIR + "/" + docId + "_content_list.json";
    }

    /**
     * 构建图片存储路径：{docId}/converted/images/{路径SHA-256}.{ext}
     *
     * <p>直接“清洗”原始路径会让 a b.png 与 ab.png 等不同文件落到同一对象名。
     * 使用归一化路径的 SHA-256 可稳定去重并避免碰撞，同时保留安全的扩展名。</p>
     */
    private String buildImageObjectKey(String docId, String imagePath) {
        String normalized = imageProcessor.normalizePath(imagePath);
        if (normalized == null) {
            throw new IllegalArgumentException("图片路径不能为空");
        }
        return docId + "/" + CONVERTED_DIR + "/" + IMAGES_DIR + "/"
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

package com.llmstudy.rag.module.knowledge.document;

import com.llmstudy.rag.entity.KnowledgeDocument;
import com.llmstudy.rag.entity.KnowledgeDocumentVersion;
import com.llmstudy.rag.enums.DocumentReleaseStatus;
import com.llmstudy.rag.enums.DocumentStatus;
import com.llmstudy.rag.mapper.KnowledgeDocumentMapper;
import com.llmstudy.rag.mapper.KnowledgeDocumentVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 逻辑文档与物理版本的事务持久化服务。
 *
 * <p>只负责数据库写入，不涉及 MinIO 等外部网络调用，保证事务只覆盖本地数据库操作。
 * MinIO 上传成功后再调用本服务，避免「事务 + 网络请求」混在一起导致长事务或回滚了
 * 已写入的外部文件。</p>
 */
@Service
public class DocumentVersionPersistenceService {

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentVersionMapper versionMapper;

    public DocumentVersionPersistenceService(
            KnowledgeDocumentMapper documentMapper,
            KnowledgeDocumentVersionMapper versionMapper) {
        this.documentMapper = documentMapper;
        this.versionMapper = versionMapper;
    }

    /**
     * 原始文件已经成功写入 MinIO 后，创建逻辑文档和版本 1。
     */
    @Transactional
    public void createInitialVersion(
            String docId,
            String versionId,
            String docTitle,
            String accessibleBy,
            String contentHash,
            String fileType,
            String uploadedBy,
            String docUrl,
            String rawObjectKey) {

        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocId(docId);
        document.setDocTitle(docTitle);
        document.setAccessibleBy(accessibleBy);
        document.setCurrentVersionId(null);

        if (documentMapper.insert(document) != 1) {
            throw new IllegalStateException("创建逻辑文档失败: " + docId);
        }

        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setVersionId(versionId);
        version.setDocId(docId);
        version.setVersionNo(1);
        version.setContentHash(contentHash);
        version.setFileType(fileType);
        version.setUploadedBy(uploadedBy);
        version.setDocUrl(docUrl);
        version.setRawObjectKey(rawObjectKey);
        version.setConvertedDocUrl("");
        version.setDocumentStatus(DocumentStatus.UPLOADED);
        version.setDocumentReleaseStatus(DocumentReleaseStatus.PREPARING);
        version.setRetryCount(0);

        if (versionMapper.insert(version) != 1) {
            throw new IllegalStateException("创建文档版本失败: " + versionId);
        }
    }

    /**
     * 为已有逻辑文档创建下一个物理版本。
     *
     * <p>逻辑文档行锁同时保护内容去重检查和 version_no 分配。即使多个实例并发上传，
     * 同一文档也不会创建相同版本号或相同内容的两个版本。</p>
     */
    @Transactional
    public KnowledgeDocumentVersion createNextVersion(
            String docId,
            String versionId,
            String contentHash,
            String fileType,
            String uploadedBy,
            String docUrl,
            String rawObjectKey,
            String changeSummary) {

        KnowledgeDocument document = documentMapper.findByDocIdForUpdate(docId);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在: " + docId);
        }

        KnowledgeDocumentVersion duplicate =
                versionMapper.findByDocIdAndContentHash(docId, contentHash);
        if (duplicate != null) {
            throw new DocumentVersionConflictException(
                    "相同内容的版本已经存在: versionId=" + duplicate.getVersionId());
        }

        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setVersionId(versionId);
        version.setDocId(docId);
        version.setVersionNo(versionMapper.findNextVersionNo(docId));
        version.setContentHash(contentHash);
        version.setFileType(fileType);
        version.setUploadedBy(uploadedBy);
        version.setDocUrl(docUrl);
        version.setRawObjectKey(rawObjectKey);
        version.setConvertedDocUrl("");
        version.setDocumentStatus(DocumentStatus.UPLOADED);
        version.setDocumentReleaseStatus(DocumentReleaseStatus.PREPARING);
        version.setRetryCount(0);
        version.setChangeSummary(normalizeChangeSummary(changeSummary));

        if (versionMapper.insert(version) != 1) {
            throw new IllegalStateException("创建文档版本失败: " + versionId);
        }
        return version;
    }

    private static String normalizeChangeSummary(String changeSummary) {
        if (changeSummary == null || changeSummary.isBlank()) {
            return null;
        }
        String normalized = changeSummary.strip();
        if (normalized.length() > 512) {
            throw new IllegalArgumentException("版本变更说明不能超过 512 个字符");
        }
        return normalized;
    }
}

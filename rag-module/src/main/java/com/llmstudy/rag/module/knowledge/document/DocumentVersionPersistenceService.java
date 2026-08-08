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
     *
     * <p>所有者、上传者、可见范围和组织均由上层根据当前身份计算完成，本方法只在
     * 同一个数据库事务中持久化这些可信字段。</p>
     */
    @Transactional
    public void createInitialVersion(
            String docId,
            String versionId,
            String docTitle,
            String ownerUserId,
            String visibility,
            String organizationId,
            String contentHash,
            String fileType,
            String uploadedBy,
            String docUrl,
            String rawObjectKey) {

        // 先创建稳定的逻辑文档身份；首次发布前 current_version_id 必须保持为空。
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocId(docId);
        document.setDocTitle(docTitle);
        document.setOwnerUserId(ownerUserId);
        document.setVisibility(visibility);
        document.setOrganizationId(organizationId);
        document.setCurrentVersionId(null);

        if (documentMapper.insert(document) != 1) {
            throw new IllegalStateException("创建逻辑文档失败: " + docId);
        }

        // 版本上传者与 owner 可以不同，例如组织管理员为本组织文档上传新版本。
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

        // 去重和版本号分配都处于逻辑文档行锁保护范围内，避免并发绕过。
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

    /** 规范化可选的版本说明，并在持久化前限制数据库字段长度。 */
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

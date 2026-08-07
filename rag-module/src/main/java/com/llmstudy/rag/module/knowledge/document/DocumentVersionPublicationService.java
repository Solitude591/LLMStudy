package com.llmstudy.rag.module.knowledge.document;

import com.llmstudy.rag.dto.VersionPublishResult;
import com.llmstudy.rag.entity.KnowledgeDocument;
import com.llmstudy.rag.entity.KnowledgeDocumentVersion;
import com.llmstudy.rag.enums.DocumentReleaseStatus;
import com.llmstudy.rag.enums.DocumentStatus;
import com.llmstudy.rag.mapper.KnowledgeDocumentMapper;
import com.llmstudy.rag.mapper.KnowledgeDocumentVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 在单个数据库事务内发布新版本或切回已归档版本。
 *
 * <p>事务提交是线上可见性的唯一切换点：目标版本、逻辑文档指针和旧版本状态
 * 要么一起成功，要么一起回滚。</p>
 */
@Service
public class DocumentVersionPublicationService {

    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeDocumentVersionMapper versionMapper;

    public DocumentVersionPublicationService(KnowledgeDocumentMapper documentMapper,
                                             KnowledgeDocumentVersionMapper versionMapper) {
        this.documentMapper = documentMapper;
        this.versionMapper = versionMapper;
    }

    /**
     * 发布 READY 版本，或将 ARCHIVED 版本重新发布以完成回滚。
     */
    @Transactional
    public VersionPublishResult publishVersion(String docId,
                                               String targetVersionId,
                                               String expectedCurrentVersionId) {
        requireId(docId, "docId");
        requireId(targetVersionId, "versionId");

        // 对逻辑文档加行锁，使同一文档的发布、回滚和后续版本号分配串行化。
        KnowledgeDocument document = documentMapper.findByDocIdForUpdate(docId);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在: " + docId);
        }

        String currentVersionId = document.getCurrentVersionId();
        if (!Objects.equals(currentVersionId, expectedCurrentVersionId)) {
            throw new DocumentVersionConflictException(
                    "当前版本已经变化，expected=" + expectedCurrentVersionId
                            + ", actual=" + currentVersionId);
        }

        KnowledgeDocumentVersion target =
                versionMapper.findByDocIdAndVersionId(docId, targetVersionId);
        if (target == null) {
            throw new IllegalArgumentException(
                    "目标版本不存在或不属于该文档: " + targetVersionId);
        }

        // 同一个发布请求重复提交时直接返回，不再次修改 published_at。
        if (Objects.equals(currentVersionId, targetVersionId)
                && target.getDocumentReleaseStatus() == DocumentReleaseStatus.PUBLISHED) {
            return new VersionPublishResult(docId, currentVersionId, targetVersionId,
                    DocumentReleaseStatus.PUBLISHED.value(), false);
        }

        if (target.getDocumentStatus() != DocumentStatus.VECTOR_STORED) {
            throw new IllegalStateException(
                    "目标版本尚未完成向量化: versionId=" + targetVersionId
                            + ", status=" + target.getProcessingStatus());
        }

        DocumentReleaseStatus targetReleaseStatus = target.getDocumentReleaseStatus();
        if (targetReleaseStatus != DocumentReleaseStatus.READY
                && targetReleaseStatus != DocumentReleaseStatus.ARCHIVED) {
            throw new DocumentVersionConflictException(
                    "目标版本状态不允许发布: versionId=" + targetVersionId
                            + ", releaseStatus=" + target.getReleaseStatus());
        }

        if (versionMapper.compareAndSetReleaseStatus(
                targetVersionId,
                DocumentReleaseStatus.PUBLISHING,
                targetReleaseStatus) != 1) {
            throw new DocumentVersionConflictException(
                    "目标版本发布状态已被并发修改: " + targetVersionId);
        }

        if (documentMapper.compareAndSetCurrentVersion(
                docId, targetVersionId, expectedCurrentVersionId) != 1) {
            throw new DocumentVersionConflictException("当前版本指针切换失败: " + docId);
        }

        if (currentVersionId != null && !currentVersionId.equals(targetVersionId)) {
            KnowledgeDocumentVersion previous =
                    versionMapper.findByDocIdAndVersionId(docId, currentVersionId);
            if (previous == null || versionMapper.markArchived(currentVersionId) != 1) {
                throw new DocumentVersionConflictException(
                        "旧版本状态归档失败: " + currentVersionId);
            }
        }

        if (versionMapper.markPublished(targetVersionId) != 1) {
            throw new DocumentVersionConflictException(
                    "目标版本发布完成状态更新失败: " + targetVersionId);
        }

        return new VersionPublishResult(docId, currentVersionId, targetVersionId,
                DocumentReleaseStatus.PUBLISHED.value(), true);
    }

    private static void requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }
}

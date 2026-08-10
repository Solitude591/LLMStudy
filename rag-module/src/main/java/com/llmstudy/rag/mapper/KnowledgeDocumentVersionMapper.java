package com.llmstudy.rag.mapper;

import com.llmstudy.rag.entity.KnowledgeDocumentVersion;
import com.llmstudy.rag.enums.DocumentReleaseStatus;
import com.llmstudy.rag.enums.DocumentStatus;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * knowledge_document_version 物理版本 Mapper。
 *
 * 负责版本快照、处理进度和发布状态。
 */
@Mapper
public interface KnowledgeDocumentVersionMapper {

    @Select("""
            SELECT *
            FROM knowledge_document_version
            WHERE version_id = #{versionId}
            """)
    KnowledgeDocumentVersion findByVersionId(
            @Param("versionId") String versionId);

    @Select("""
            SELECT *
            FROM knowledge_document_version
            WHERE doc_id = #{docId}
              AND version_id = #{versionId}
            """)
    KnowledgeDocumentVersion findByDocIdAndVersionId(
            @Param("docId") String docId,
            @Param("versionId") String versionId);

    @Select("""
            SELECT *
            FROM knowledge_document_version
            WHERE doc_id = #{docId}
            ORDER BY version_no DESC
            """)
    List<KnowledgeDocumentVersion> findByDocId(
            @Param("docId") String docId);

    @Select("""
            SELECT *
            FROM knowledge_document_version
            WHERE doc_id = #{docId}
            ORDER BY version_no DESC
            LIMIT 1
            """)
    KnowledgeDocumentVersion findLatestByDocId(
            @Param("docId") String docId);

    @Select("""
            SELECT *
            FROM knowledge_document_version
            WHERE doc_id = #{docId}
              AND content_hash = #{contentHash}
            LIMIT 1
            """)
    KnowledgeDocumentVersion findByDocIdAndContentHash(
            @Param("docId") String docId,
            @Param("contentHash") String contentHash);

    /**
     * 调用前必须先通过 KnowledgeDocumentMapper.findByDocIdForUpdate()
     * 锁定逻辑文档，否则并发创建版本时可能拿到相同版本号。
     */
    @Select("""
            SELECT COALESCE(MAX(version_no), 0) + 1
            FROM knowledge_document_version
            WHERE doc_id = #{docId}
            """)
    int findNextVersionNo(@Param("docId") String docId);

    @Insert("""
            INSERT INTO knowledge_document_version
            (
                version_id,
                doc_id,
                version_no,
                content_hash,
                file_type,
                uploaded_by,
                doc_url,
                raw_object_key,
                converted_doc_url,
                content_list_url,
                processing_status,
                release_status,
                error_message,
                retry_count,
                change_summary,
                ready_at,
                published_at
            )
            VALUES
            (
                #{versionId},
                #{docId},
                #{versionNo},
                #{contentHash},
                #{fileType},
                #{uploadedBy},
                #{docUrl},
                #{rawObjectKey},
                #{convertedDocUrl},
                #{contentListUrl},
                #{processingStatus},
                #{releaseStatus},
                #{errorMessage},
                #{retryCount},
                #{changeSummary},
                #{readyAt},
                #{publishedAt}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeDocumentVersion version);

    /**
     * 原子抢占解析、分片或向量化阶段。
     */
    @Update("""
            UPDATE knowledge_document_version
            SET processing_status = #{targetStatus}
            WHERE version_id = #{versionId}
              AND processing_status = #{expectedStatus}
            """)
    int compareAndSetProcessingStatusValue(
            @Param("versionId") String versionId,
            @Param("targetStatus") String targetStatus,
            @Param("expectedStatus") String expectedStatus);

    default int compareAndSetProcessingStatus(
            String versionId,
            DocumentStatus targetStatus,
            DocumentStatus expectedStatus) {

        return compareAndSetProcessingStatusValue(
                versionId,
                targetStatus.value(),
                expectedStatus.value());
    }

    /**
     * 解析完成后保存 Markdown / content_list 地址并推进处理状态。
     */
    @Update("""
            UPDATE knowledge_document_version
            SET converted_doc_url = #{convertedDocUrl},
                content_list_url = #{contentListUrl},
                processing_status = #{targetStatus},
                error_message = NULL,
                retry_count = 0
            WHERE version_id = #{versionId}
              AND processing_status = #{expectedStatus}
            """)
    int updateConvertedValue(
            @Param("versionId") String versionId,
            @Param("convertedDocUrl") String convertedDocUrl,
            @Param("contentListUrl") String contentListUrl,
            @Param("targetStatus") String targetStatus,
            @Param("expectedStatus") String expectedStatus);

    default int updateConverted(
            String versionId,
            String convertedDocUrl,
            DocumentStatus targetStatus,
            DocumentStatus expectedStatus) {
        return updateConverted(versionId, convertedDocUrl, null, targetStatus, expectedStatus);
    }

    default int updateConverted(
            String versionId,
            String convertedDocUrl,
            String contentListUrl,
            DocumentStatus targetStatus,
            DocumentStatus expectedStatus) {

        return updateConvertedValue(
                versionId,
                convertedDocUrl,
                contentListUrl,
                targetStatus.value(),
                expectedStatus.value());
    }

    /**
     * 阶段成功时原子推进状态并清除本阶段之前遗留的错误与自动重试计数。
     *
     * <p>与 updateConverted 的区别：不更新 converted_doc_url，
     * 用于分片等不需要落地址的阶段成功回写（SPLITTING → CHUNKED）。</p>
     */
    @Update("""
            UPDATE knowledge_document_version
            SET processing_status = #{targetStatus},
                error_message = NULL,
                retry_count = 0
            WHERE version_id = #{versionId}
              AND processing_status = #{expectedStatus}
            """)
    int compareAndSetProcessingStatusAndClearErrorValue(
            @Param("versionId") String versionId,
            @Param("targetStatus") String targetStatus,
            @Param("expectedStatus") String expectedStatus);

    default int compareAndSetProcessingStatusAndClearError(
            String versionId,
            DocumentStatus targetStatus,
            DocumentStatus expectedStatus) {

        return compareAndSetProcessingStatusAndClearErrorValue(
                versionId,
                targetStatus.value(),
                expectedStatus.value());
    }

    /**
     * 处理失败时回到上一个稳定状态并记录错误。
     */
    @Update("""
            UPDATE knowledge_document_version
            SET processing_status = #{fallbackStatus},
                error_message = #{errorMessage}
            WHERE version_id = #{versionId}
              AND processing_status = #{executingStatus}
            """)
    int compareAndSetProcessingStatusWithErrorValue(
            @Param("versionId") String versionId,
            @Param("fallbackStatus") String fallbackStatus,
            @Param("executingStatus") String executingStatus,
            @Param("errorMessage") String errorMessage);

    default int compareAndSetProcessingStatusWithError(
            String versionId,
            DocumentStatus fallbackStatus,
            DocumentStatus executingStatus,
            String errorMessage) {

        return compareAndSetProcessingStatusWithErrorValue(
                versionId,
                fallbackStatus.value(),
                executingStatus.value(),
                errorMessage);
    }

    /**
     * 向量化成功后，版本进入 READY，等待手动发布。
     */
    @Update("""
            UPDATE knowledge_document_version
            SET processing_status = 'VECTOR_STORED',
                release_status = 'READY',
                ready_at = CURRENT_TIMESTAMP,
                error_message = NULL,
                retry_count = 0
            WHERE version_id = #{versionId}
              AND processing_status = 'VECTORING'
              AND release_status = 'PREPARING'
            """)
    int markReady(@Param("versionId") String versionId);

    /**
     * 抢占发布或回滚操作。
     */
    @Update("""
            UPDATE knowledge_document_version
            SET release_status = #{targetStatus}
            WHERE version_id = #{versionId}
              AND release_status = #{expectedStatus}
              AND processing_status = 'VECTOR_STORED'
            """)
    int compareAndSetReleaseStatusValue(
            @Param("versionId") String versionId,
            @Param("targetStatus") String targetStatus,
            @Param("expectedStatus") String expectedStatus);

    default int compareAndSetReleaseStatus(
            String versionId,
            DocumentReleaseStatus targetStatus,
            DocumentReleaseStatus expectedStatus) {

        return compareAndSetReleaseStatusValue(
                versionId,
                targetStatus.value(),
                expectedStatus.value());
    }

    @Update("""
            UPDATE knowledge_document_version
            SET release_status = 'PUBLISHED',
                published_at = CURRENT_TIMESTAMP
            WHERE version_id = #{versionId}
              AND release_status = 'PUBLISHING'
              AND processing_status = 'VECTOR_STORED'
            """)
    int markPublished(@Param("versionId") String versionId);

    @Update("""
            UPDATE knowledge_document_version
            SET release_status = 'ARCHIVED'
            WHERE version_id = #{versionId}
              AND release_status = 'PUBLISHED'
            """)
    int markArchived(@Param("versionId") String versionId);

    @Update("""
            UPDATE knowledge_document_version
            SET retry_count = retry_count + 1
            WHERE version_id = #{versionId}
              AND processing_status = #{expectedStatus}
              AND retry_count = #{expectedRetryCount}
              AND retry_count < #{maxRetryCount}
            """)
    int incrementRetryCountValue(
            @Param("versionId") String versionId,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedRetryCount") int expectedRetryCount,
            @Param("maxRetryCount") int maxRetryCount);

    default int incrementRetryCount(
            String versionId,
            DocumentStatus expectedStatus,
            int expectedRetryCount,
            int maxRetryCount) {

        return incrementRetryCountValue(
                versionId,
                expectedStatus.value(),
                expectedRetryCount,
                maxRetryCount);
    }

    /**
     * 查找失败待补偿的版本。
     *
     * <p>条件：处于失败回退后的稳定状态（uploaded/converted/chunked）、带错误信息、
     * 自动重试未达上限、且失败时间早于给定时间（冷却期，避免与原处理流程竞争）。
     * 按更新时间升序（先失败的先补偿），单轮数量由 batchSize 限制。</p>
     */
    @Select("""
            <script>
            SELECT *
            FROM knowledge_document_version
            WHERE processing_status IN
            <foreach collection='statuses' item='s' open='(' separator=',' close=')'>
                #{s}
            </foreach>
              AND error_message IS NOT NULL
              AND retry_count &lt; #{maxRetryCount}
              AND updated_at &lt; #{before}
            ORDER BY updated_at ASC
            LIMIT #{batchSize}
            </script>
            """)
    List<KnowledgeDocumentVersion> findFailedForCompensationValue(
            @Param("statuses") List<String> statuses,
            @Param("maxRetryCount") int maxRetryCount,
            @Param("before") LocalDateTime before,
            @Param("batchSize") int batchSize);

    default List<KnowledgeDocumentVersion> findFailedForCompensation(
            int maxRetryCount, LocalDateTime before, int batchSize) {
        return findFailedForCompensationValue(
                List.of(DocumentStatus.UPLOADED.value(),
                        DocumentStatus.CONVERTED.value(),
                        DocumentStatus.CHUNKED.value()),
                maxRetryCount, before, batchSize);
    }

    /**
     * 查找没有错误信息但长时间未推进的稳定态版本。
     *
     * <p>用于覆盖“数据库已提交、进程却在发布本地事件前退出”的窗口。
     * 只扫描 PREPARING 版本，已 READY/PUBLISHED 的版本不会被重复触发。</p>
     */
    @Select("""
            <script>
            SELECT *
            FROM knowledge_document_version
            WHERE processing_status IN
            <foreach collection='statuses' item='s' open='(' separator=',' close=')'>
                #{s}
            </foreach>
              AND release_status = 'PREPARING'
              AND error_message IS NULL
              AND retry_count &lt; #{maxRetryCount}
              AND updated_at &lt; #{deadline}
            ORDER BY updated_at ASC
            LIMIT #{batchSize}
            </script>
            """)
    List<KnowledgeDocumentVersion> findStalledStableValue(
            @Param("statuses") List<String> statuses,
            @Param("maxRetryCount") int maxRetryCount,
            @Param("deadline") LocalDateTime deadline,
            @Param("batchSize") int batchSize);

    default List<KnowledgeDocumentVersion> findStalledStable(
            int maxRetryCount, LocalDateTime deadline, int batchSize) {
        return findStalledStableValue(
                List.of(DocumentStatus.UPLOADED.value(),
                        DocumentStatus.CONVERTED.value(),
                        DocumentStatus.CHUNKED.value()),
                maxRetryCount, deadline, batchSize);
    }

    /**
     * 查找卡死在中间态的版本。
     *
     * <p>进程崩溃、线程池耗尽等异常会导致版本长期停留在
     * converting/splitting/vectoring 等执行态，需要回退到上一稳定状态重新触发。</p>
     */
    @Select("""
            <script>
            SELECT *
            FROM knowledge_document_version
            WHERE processing_status IN
            <foreach collection='statuses' item='s' open='(' separator=',' close=')'>
                #{s}
            </foreach>
              AND updated_at &lt; #{deadline}
            ORDER BY updated_at ASC
            LIMIT #{batchSize}
            </script>
            """)
    List<KnowledgeDocumentVersion> findStaleIntermediateValue(
            @Param("statuses") List<String> statuses,
            @Param("deadline") LocalDateTime deadline,
            @Param("batchSize") int batchSize);

    default List<KnowledgeDocumentVersion> findStaleIntermediate(
            LocalDateTime deadline, int batchSize) {
        return findStaleIntermediateValue(
                List.of(DocumentStatus.CONVERTING.value(),
                        DocumentStatus.SPLITTING.value(),
                        DocumentStatus.VECTORING.value()),
                deadline, batchSize);
    }
}

package com.llmstudy.rag.mapper;

import com.llmstudy.rag.entity.KnowledgeDocument;
import com.llmstudy.rag.enums.DocumentStatus;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * knowledge_document 表 Mapper
 */
@Mapper
public interface KnowledgeDocumentMapper {

    @Select("SELECT * FROM knowledge_document WHERE doc_id = #{docId}")
    KnowledgeDocument findByDocId(@Param("docId") String docId);

    /**
     * 分片事务中锁定文档记录，防止同一个 docId 被并发重复分片。
     */
    @Select("SELECT * FROM knowledge_document WHERE doc_id = #{docId} FOR UPDATE")
    KnowledgeDocument findByDocIdForUpdate(@Param("docId") String docId);

    @Select("""
            SELECT * FROM knowledge_document
            WHERE uploader = #{uploader}
              AND file_md5 = #{fileMd5}
              AND target_table_name = #{targetTableName}
            LIMIT 1
            """)
    KnowledgeDocument findByUploaderAndFileMd5AndTargetTableName(
            @Param("uploader") String uploader,
            @Param("fileMd5") String fileMd5,
            @Param("targetTableName") String targetTableName);

    @Select("SELECT * FROM knowledge_document WHERE uploader = #{uploader} ORDER BY created_at DESC")
    List<KnowledgeDocument> findByUploader(@Param("uploader") String uploader);

    @Select("SELECT * FROM knowledge_document WHERE doc_status = #{docStatus} ORDER BY created_at DESC")
    List<KnowledgeDocument> findByStatusValue(@Param("docStatus") String docStatus);

    default List<KnowledgeDocument> findByStatus(DocumentStatus docStatus) {
        return findByStatusValue(docStatus.value());
    }

    /**
     * 查找失败待补偿的文档。
     *
     * <p>条件：处于失败回退后的稳定状态（uploaded/converted/chunked）、带错误信息、
     * 自动重试未达上限、且失败时间早于给定时间（冷却期，避免与原处理流程竞争）。
     * 按更新时间升序（先失败的先补偿），单轮数量由 batchSize 限制。</p>
     */
    @Select("""
            SELECT * FROM knowledge_document
            WHERE doc_status IN ('uploaded', 'converted', 'chunked')
              AND error_message IS NOT NULL
              AND retry_count < #{maxRetryCount}
              AND updated_at < #{before}
            ORDER BY updated_at ASC
            LIMIT #{batchSize}
            """)
    List<KnowledgeDocument> findFailedForCompensation(
            @Param("maxRetryCount") int maxRetryCount,
            @Param("before") LocalDateTime before,
            @Param("batchSize") int batchSize);

    /**
     * 查找卡死在中间态的文档。
     *
     * <p>进程崩溃、线程池耗尽等异常会导致文档长期停留在
     * converting/splitting/vectoring 等执行态，需要回退到上一稳定状态重新触发。</p>
     */
    @Select("""
            SELECT * FROM knowledge_document
            WHERE doc_status IN ('converting', 'splitting', 'vectoring')
              AND updated_at < #{deadline}
            ORDER BY updated_at ASC
            LIMIT #{batchSize}
            """)
    List<KnowledgeDocument> findStaleIntermediate(
            @Param("deadline") LocalDateTime deadline,
            @Param("batchSize") int batchSize);

    /**
     * 抢占一次自动重试名额：CAS 校验状态未变化且重试未达上限，随后递增 retry_count。
     *
     * <p>返回 1 表示抢占成功，可安全发布补偿事件；返回 0 表示文档状态已变化、
     * 已被其他实例抢占或已达重试上限，应跳过。多实例部署时天然并发安全。</p>
     */
    @Update("""
            UPDATE knowledge_document
            SET retry_count = retry_count + 1
            WHERE doc_id = #{docId}
              AND doc_status = #{expectedStatus}
              AND retry_count < #{maxRetryCount}
            """)
    int incrementRetryCountValue(@Param("docId") String docId,
                                 @Param("expectedStatus") String expectedStatus,
                                 @Param("maxRetryCount") int maxRetryCount);

    default int incrementRetryCount(String docId,
                                    DocumentStatus expectedStatus,
                                    int maxRetryCount) {
        return incrementRetryCountValue(
                docId, expectedStatus.value(), maxRetryCount);
    }

    @Select("SELECT * FROM knowledge_document ORDER BY created_at DESC")
    List<KnowledgeDocument> findAll();

    @Insert("""
            INSERT INTO knowledge_document
            (doc_id, doc_title, original_name, file_type, file_size, file_md5,
             target_table_name, uploader, doc_url, raw_object_key, doc_status, visibility)
            VALUES
            (#{docId}, #{docTitle}, #{originalName}, #{fileType}, #{fileSize}, #{fileMd5},
             #{targetTableName}, #{uploader}, #{docUrl}, #{rawObjectKey}, #{docStatus}, #{visibility})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeDocument doc);

    @Update("""
            UPDATE knowledge_document
            SET doc_title     = #{docTitle},
                original_name = #{originalName},
                file_type     = #{fileType},
                file_size     = #{fileSize},
                target_table_name = #{targetTableName},
                doc_url       = #{docUrl},
                raw_object_key = #{rawObjectKey},
                doc_status    = #{docStatus},
                visibility    = #{visibility}
            WHERE doc_id = #{docId}
            """)
    int update(KnowledgeDocument doc);

    @Update("UPDATE knowledge_document SET doc_status = #{docStatus} WHERE doc_id = #{docId}")
    int updateStatusValue(@Param("docId") String docId,
                          @Param("docStatus") String docStatus);

    default int updateStatus(String docId, DocumentStatus docStatus) {
        return updateStatusValue(docId, docStatus.value());
    }

    /**
     * 原子比较并更新状态，防止并发请求同时推进同一文档。
     */
    @Update("""
            UPDATE knowledge_document
            SET doc_status = #{targetStatus}
            WHERE doc_id = #{docId} AND doc_status = #{expectedStatus}
            """)
    int compareAndSetStatusValue(@Param("docId") String docId,
                                 @Param("targetStatus") String targetStatus,
                                 @Param("expectedStatus") String expectedStatus);

    default int compareAndSetStatus(String docId,
                                   DocumentStatus targetStatus,
                                   DocumentStatus expectedStatus) {
        return compareAndSetStatusValue(
                docId, targetStatus.value(), expectedStatus.value());
    }

    /**
     * 仅当文档仍处于 expectedStatus 时回退状态并记录错误。
     * 防止迟到事件或并发失败处理覆盖已经推进到后续阶段的新状态。
     */
    @Update("""
            UPDATE knowledge_document
            SET doc_status = #{targetStatus}, error_message = #{errorMessage}
            WHERE doc_id = #{docId} AND doc_status = #{expectedStatus}
            """)
    int compareAndSetStatusWithErrorValue(
            @Param("docId") String docId,
            @Param("targetStatus") String targetStatus,
            @Param("expectedStatus") String expectedStatus,
            @Param("errorMessage") String errorMessage);

    default int compareAndSetStatusWithError(
            String docId,
            DocumentStatus targetStatus,
            DocumentStatus expectedStatus,
            String errorMessage) {
        return compareAndSetStatusWithErrorValue(
                docId, targetStatus.value(), expectedStatus.value(), errorMessage);
    }

    /**
     * 完成阶段时原子推进状态并清除本阶段之前遗留的错误与自动重试计数。
     */
    @Update("""
            UPDATE knowledge_document
            SET doc_status = #{targetStatus}, error_message = NULL, retry_count = 0
            WHERE doc_id = #{docId} AND doc_status = #{expectedStatus}
            """)
    int compareAndSetStatusAndClearErrorValue(
            @Param("docId") String docId,
            @Param("targetStatus") String targetStatus,
            @Param("expectedStatus") String expectedStatus);

    default int compareAndSetStatusAndClearError(
            String docId,
            DocumentStatus targetStatus,
            DocumentStatus expectedStatus) {
        return compareAndSetStatusAndClearErrorValue(
                docId, targetStatus.value(), expectedStatus.value());
    }

    /**
     * 清除文档的错误信息。
     * 用于重试成功后清理历史错误记录。
     *
     * @param docId 文档 ID
     * @return 更新的行数
     */
    @Update("UPDATE knowledge_document SET error_message = NULL WHERE doc_id = #{docId}")
    int clearErrorMessage(@Param("docId") String docId);

    @Update("""
            UPDATE knowledge_document
            SET converted_doc_url = #{convertedDocUrl},
                doc_status = #{docStatus},
                error_message = NULL,
                retry_count = 0
            WHERE doc_id = #{docId} AND doc_status = #{expectedStatus}
            """)
    int updateConvertedValue(@Param("docId") String docId,
                             @Param("convertedDocUrl") String convertedDocUrl,
                             @Param("docStatus") String docStatus,
                             @Param("expectedStatus") String expectedStatus);

    default int updateConverted(String docId,
                                String convertedDocUrl,
                                DocumentStatus docStatus,
                                DocumentStatus expectedStatus) {
        return updateConvertedValue(
                docId, convertedDocUrl, docStatus.value(), expectedStatus.value());
    }

    @Delete("DELETE FROM knowledge_document WHERE doc_id = #{docId}")
    int deleteByDocId(@Param("docId") String docId);
}

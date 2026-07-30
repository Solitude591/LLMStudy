package com.llmstudy.rag.mapper;

import com.llmstudy.rag.entity.KnowledgeDocument;
import com.llmstudy.rag.enums.DocumentStatus;
import org.apache.ibatis.annotations.*;

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
            WHERE uploader = #{uploader} AND file_md5 = #{fileMd5}
            LIMIT 1
            """)
    KnowledgeDocument findByUploaderAndFileMd5(@Param("uploader") String uploader,
                                               @Param("fileMd5") String fileMd5);

    @Select("SELECT * FROM knowledge_document WHERE uploader = #{uploader} ORDER BY created_at DESC")
    List<KnowledgeDocument> findByUploader(@Param("uploader") String uploader);

    @Select("SELECT * FROM knowledge_document WHERE doc_status = #{docStatus} ORDER BY created_at DESC")
    List<KnowledgeDocument> findByStatusValue(@Param("docStatus") String docStatus);

    default List<KnowledgeDocument> findByStatus(DocumentStatus docStatus) {
        return findByStatusValue(docStatus.value());
    }

    @Select("SELECT * FROM knowledge_document ORDER BY created_at DESC")
    List<KnowledgeDocument> findAll();

    @Insert("""
            INSERT INTO knowledge_document
            (doc_id, doc_title, original_name, file_type, file_size, file_md5,
             uploader, doc_url, doc_status, visibility)
            VALUES
            (#{docId}, #{docTitle}, #{originalName}, #{fileType}, #{fileSize}, #{fileMd5},
             #{uploader}, #{docUrl}, #{docStatus}, #{visibility})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeDocument doc);

    @Update("""
            UPDATE knowledge_document
            SET doc_title     = #{docTitle},
                original_name = #{originalName},
                file_type     = #{fileType},
                file_size     = #{fileSize},
                doc_url       = #{docUrl},
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
     * 完成阶段时原子推进状态并清除本阶段之前遗留的错误。
     */
    @Update("""
            UPDATE knowledge_document
            SET doc_status = #{targetStatus}, error_message = NULL
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
                error_message = NULL
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

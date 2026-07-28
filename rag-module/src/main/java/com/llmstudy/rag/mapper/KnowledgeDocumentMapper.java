package com.llmstudy.rag.mapper;

import com.llmstudy.rag.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * knowledge_document 表 Mapper
 */
@Mapper
public interface KnowledgeDocumentMapper {

    @Select("SELECT * FROM knowledge_document WHERE doc_id = #{docId}")
    KnowledgeDocument findByDocId(@Param("docId") String docId);

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
    List<KnowledgeDocument> findByStatus(@Param("docStatus") String docStatus);

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
    int updateStatus(@Param("docId") String docId, @Param("docStatus") String docStatus);

    /**
     * 原子抢占解析任务，防止两个请求同时解析并互相覆盖/清理产物。
     */
    @Update("""
            UPDATE knowledge_document
            SET doc_status = 'converting'
            WHERE doc_id = #{docId} AND doc_status = 'uploaded'
            """)
    int markConverting(@Param("docId") String docId);

    @Update("""
            UPDATE knowledge_document
            SET converted_doc_url = #{convertedDocUrl}, doc_status = #{docStatus}
            WHERE doc_id = #{docId} AND doc_status = 'converting'
            """)
    int updateConverted(@Param("docId") String docId,
                        @Param("convertedDocUrl") String convertedDocUrl,
                        @Param("docStatus") String docStatus);

    @Update("""
            UPDATE knowledge_document
            SET doc_status = 'uploaded'
            WHERE doc_id = #{docId} AND doc_status = 'converting'
            """)
    int resetConverting(@Param("docId") String docId);

    @Delete("DELETE FROM knowledge_document WHERE doc_id = #{docId}")
    int deleteByDocId(@Param("docId") String docId);
}

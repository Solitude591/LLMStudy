package com.llmstudy.rag.mapper;

import com.llmstudy.rag.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * knowledge_document 逻辑文档 Mapper。
 *
 * 只负责稳定身份信息和当前版本指针，不处理文件、解析和向量化状态。
 */
@Mapper
public interface KnowledgeDocumentMapper {

    @Select("""
            SELECT id, doc_id, doc_title, accessible_by,
                   current_version_id, created_at, updated_at
            FROM knowledge_document
            WHERE doc_id = #{docId}
            """)
    KnowledgeDocument findByDocId(@Param("docId") String docId);

    /**
     * 创建新版本前锁定逻辑文档，用于安全分配递增版本号。
     */
    @Select("""
            SELECT id, doc_id, doc_title, accessible_by,
                   current_version_id, created_at, updated_at
            FROM knowledge_document
            WHERE doc_id = #{docId}
            FOR UPDATE
            """)
    KnowledgeDocument findByDocIdForUpdate(@Param("docId") String docId);

    @Select("""
            SELECT id, doc_id, doc_title, accessible_by,
                   current_version_id, created_at, updated_at
            FROM knowledge_document
            ORDER BY created_at DESC
            """)
    List<KnowledgeDocument> findAll();

    /**
     * 返回一次检索请求允许访问的当前版本快照。
     */
    @Select("""
            SELECT current_version_id
            FROM knowledge_document
            WHERE current_version_id IS NOT NULL
            """)
    List<String> findAllCurrentVersionIds();

    /**
     * 创建逻辑文档时 current_version_id 必须保持 NULL：
     * 此时版本记录还不存在，写入会被 fk_document_current_version 外键约束阻止。
     * 首次发布时才通过 setInitialCurrentVersion 设置指针。
     */
    @Insert("""
            INSERT INTO knowledge_document
                (doc_id, doc_title, accessible_by)
            VALUES
                (#{docId}, #{docTitle}, #{accessibleBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeDocument document);

    @Update("""
            UPDATE knowledge_document
            SET doc_title = #{docTitle},
                accessible_by = #{accessibleBy}
            WHERE doc_id = #{docId}
            """)
    int updateMetadata(KnowledgeDocument document);

    /**
     * 首次发布：只有当前没有生效版本时才允许设置指针。
     */
    @Update("""
            UPDATE knowledge_document
            SET current_version_id = #{targetVersionId}
            WHERE doc_id = #{docId}
              AND current_version_id IS NULL
            """)
    int setInitialCurrentVersion(
            @Param("docId") String docId,
            @Param("targetVersionId") String targetVersionId);

    /**
     * 后续发布或回滚：通过旧版本 ID 做 CAS，防止并发覆盖。
     */
    @Update("""
            UPDATE knowledge_document
            SET current_version_id = #{targetVersionId}
            WHERE doc_id = #{docId}
              AND current_version_id = #{expectedCurrentVersionId}
            """)
    int switchCurrentVersion(
            @Param("docId") String docId,
            @Param("targetVersionId") String targetVersionId,
            @Param("expectedCurrentVersionId") String expectedCurrentVersionId);

    /**
     * 统一的当前版本指针 CAS 入口。
     */
    default int compareAndSetCurrentVersion(
            String docId,
            String targetVersionId,
            String expectedCurrentVersionId) {

        if (expectedCurrentVersionId == null) {
            return setInitialCurrentVersion(docId, targetVersionId);
        }

        return switchCurrentVersion(
                docId,
                targetVersionId,
                expectedCurrentVersionId);
    }
}

package com.llmstudy.rag.mapper;

import com.llmstudy.rag.entity.KnowledgeSegment;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * knowledge_segment 表 Mapper
 */
@Mapper
public interface KnowledgeSegmentMapper {

    @Select("SELECT * FROM knowledge_segment WHERE chunk_id = #{chunkId}")
    KnowledgeSegment findByChunkId(@Param("chunkId") String chunkId);

    @Select("SELECT * FROM knowledge_segment WHERE doc_id = #{docId} ORDER BY chunk_order ASC")
    List<KnowledgeSegment> findByDocId(@Param("docId") String docId);

    @Select("SELECT * FROM knowledge_segment WHERE embedding_id = #{embeddingId}")
    KnowledgeSegment findByEmbeddingId(@Param("embeddingId") String embeddingId);

    @Select("SELECT * FROM knowledge_segment WHERE doc_id = #{docId} AND status = #{status} ORDER BY chunk_order ASC")
    List<KnowledgeSegment> findByDocIdAndStatus(@Param("docId") String docId, @Param("status") String status);

    @Select("SELECT * FROM knowledge_segment WHERE doc_id = #{docId} AND status = 'init' AND skip_embedding = 0")
    List<KnowledgeSegment> findPendingByDocId(@Param("docId") String docId);

    @Select("SELECT * FROM knowledge_segment WHERE status = #{status} ORDER BY created_at DESC")
    List<KnowledgeSegment> findByStatus(@Param("status") String status);

    @Select("SELECT COUNT(*) FROM knowledge_segment WHERE doc_id = #{docId}")
    int countByDocId(@Param("docId") String docId);

    @Insert("""
            INSERT INTO knowledge_segment
            (chunk_id, text, doc_id, chunk_order, embedding_id, status, metadata, skip_embedding)
            VALUES
            (#{chunkId}, #{text}, #{docId}, #{chunkOrder}, #{embeddingId}, #{status}, #{metadata}, #{skipEmbedding})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(KnowledgeSegment segment);

    /**
     * 批量插入片段
     */
    @Insert("""
            <script>
            INSERT INTO knowledge_segment
            (chunk_id, text, doc_id, chunk_order, embedding_id, status, metadata, skip_embedding)
            VALUES
            <foreach collection="segments" item="s" separator=",">
                (#{s.chunkId}, #{s.text}, #{s.docId}, #{s.chunkOrder}, #{s.embeddingId}, #{s.status}, #{s.metadata}, #{s.skipEmbedding})
            </foreach>
            </script>
            """)
    int batchInsert(@Param("segments") List<KnowledgeSegment> segments);

    @Update("""
            UPDATE knowledge_segment
            SET text          = #{text},
                chunk_order   = #{chunkOrder},
                embedding_id  = #{embeddingId},
                status        = #{status},
                metadata      = #{metadata},
                skip_embedding = #{skipEmbedding}
            WHERE chunk_id = #{chunkId}
            """)
    int update(KnowledgeSegment segment);

    @Update("UPDATE knowledge_segment SET embedding_id = #{embeddingId}, status = #{status} WHERE chunk_id = #{chunkId}")
    int updateEmbedding(@Param("chunkId") String chunkId,
                        @Param("embeddingId") String embeddingId,
                        @Param("status") String status);

    /**
     * 批量更新 segment 的 embedding_id 和 status。
     * 使用 foreach 动态 SQL 减少数据库往返次数。
     *
     * @param chunkIds 要更新的 chunk_id 列表
     * @param status 目标状态，通常是 'vector_stored'
     * @return 实际更新的行数
     */
    @Update("""
            <script>
            UPDATE knowledge_segment
            SET embedding_id = chunk_id, status = #{status}
            WHERE chunk_id IN
            <foreach collection='chunkIds' item='id' open='(' separator=',' close=')'>
                #{id}
            </foreach>
            </script>
            """)
    int batchUpdateEmbedding(@Param("chunkIds") List<String> chunkIds,
                             @Param("status") String status);

    @Update("UPDATE knowledge_segment SET status = #{status} WHERE chunk_id = #{chunkId}")
    int updateStatus(@Param("chunkId") String chunkId, @Param("status") String status);

    @Delete("DELETE FROM knowledge_segment WHERE chunk_id = #{chunkId}")
    int deleteByChunkId(@Param("chunkId") String chunkId);

    @Delete("DELETE FROM knowledge_segment WHERE doc_id = #{docId}")
    int deleteByDocId(@Param("docId") String docId);
}

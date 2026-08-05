package com.llmstudy.rag.mapper;

import com.llmstudy.rag.entity.ChatMessage;
import com.llmstudy.rag.enums.MessageType;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * chat_message 表 Mapper。
 */
@Mapper
public interface ChatMessageMapper {

    /**
     * 根据消息业务 ID 查询消息。
     */
    @Select("SELECT * FROM chat_message WHERE message_id = #{messageId}")
    ChatMessage findByMessageId(@Param("messageId") String messageId);

    /**
     * 按创建时间正序返回会话的全部消息。
     */
    @Select("""
            SELECT * FROM chat_message
            WHERE conversation_id = #{conversationId}
            ORDER BY created_at ASC, id ASC
            """)
    List<ChatMessage> findByConversationId(
            @Param("conversationId") String conversationId);

    /**
     * 读取会话最近 limit 条消息，最终仍按时间正序返回，
     * 可直接用于组装模型上下文。
     */
    @Select("""
            SELECT *
            FROM (
                SELECT * FROM chat_message
                WHERE conversation_id = #{conversationId}
                ORDER BY created_at DESC, id DESC
                LIMIT #{limit}
            ) AS recent_messages
            ORDER BY created_at ASC, id ASC
            """)
    List<ChatMessage> findRecentByConversationId(
            @Param("conversationId") String conversationId,
            @Param("limit") int limit);

    /**
     * 按消息类型查询会话消息。
     */
    @Select("""
            SELECT * FROM chat_message
            WHERE conversation_id = #{conversationId}
              AND `type` = #{type}
            ORDER BY created_at ASC, id ASC
            """)
    List<ChatMessage> findByConversationIdAndTypeValue(
            @Param("conversationId") String conversationId,
            @Param("type") String type);

    default List<ChatMessage> findByConversationIdAndType(
            String conversationId, MessageType type) {
        if (type == null) {
            throw new IllegalArgumentException("消息类型不能为空");
        }
        return findByConversationIdAndTypeValue(conversationId, type.value());
    }

    @Select("""
            SELECT COUNT(*) FROM chat_message
            WHERE conversation_id = #{conversationId}
            """)
    long countByConversationId(
            @Param("conversationId") String conversationId);

    /**
     * 新增消息，id 由 MySQL 回填。
     */
    @Insert("""
            INSERT INTO chat_message
            (message_id, conversation_id, `type`, content, transform_content,
             token_count, model_name, rag_references, metadata)
            VALUES
            (#{messageId}, #{conversationId}, #{type}, #{content}, #{transformContent},
             #{tokenCount}, #{modelName}, #{ragReferences}, #{metadata})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ChatMessage message);

    /**
     * 更新消息的可变内容和扩展信息。
     */
    @Update("""
            UPDATE chat_message
            SET `type` = #{type},
                content = #{content},
                transform_content = #{transformContent},
                token_count = #{tokenCount},
                model_name = #{modelName},
                rag_references = #{ragReferences},
                metadata = #{metadata}
            WHERE message_id = #{messageId}
            """)
    int update(ChatMessage message);

    /**
     * 只更新问题改写结果，避免异步回写覆盖消息的其他字段。
     *
     * @param messageId        需要更新的消息业务 ID
     * @param transformContent 改写后的问题内容
     * @return 受影响的数据行数
     */
    @Update("""
            UPDATE chat_message
            SET transform_content = #{transformContent}
            WHERE message_id = #{messageId}
            """)
    int updateTransformContent(@Param("messageId") String messageId,
                               @Param("transformContent") String transformContent);

    /** 只回写扩展元数据，避免与其他异步字段更新相互覆盖。 */
    @Update("""
            UPDATE chat_message
            SET metadata = #{metadata}
            WHERE message_id = #{messageId}
            """)
    int updateMetadata(@Param("messageId") String messageId,
                       @Param("metadata") String metadata);

    @Delete("DELETE FROM chat_message WHERE message_id = #{messageId}")
    int deleteByMessageId(@Param("messageId") String messageId);

    @Delete("DELETE FROM chat_message WHERE conversation_id = #{conversationId}")
    int deleteByConversationId(
            @Param("conversationId") String conversationId);
}

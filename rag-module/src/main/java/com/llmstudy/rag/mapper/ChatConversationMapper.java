package com.llmstudy.rag.mapper;

import com.llmstudy.rag.entity.ChatConversation;
import com.llmstudy.rag.enums.ConversationStatus;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * chat_conversation 表 Mapper。
 */
@Mapper
public interface ChatConversationMapper {

    /**
     * 根据会话业务 ID 查询会话。
     */
    @Select("SELECT * FROM chat_conversation WHERE conversation_id = #{conversationId}")
    ChatConversation findByConversationId(
            @Param("conversationId") String conversationId);

    /**
     * 查询指定用户的会话，用于校验会话归属。
     */
    @Select("""
            SELECT * FROM chat_conversation
            WHERE conversation_id = #{conversationId}
              AND user_id = #{userId}
            """)
    ChatConversation findByConversationIdAndUserId(
            @Param("conversationId") String conversationId,
            @Param("userId") String userId);

    /**
     * 按最近更新时间倒序查询用户的全部会话。
     */
    @Select("""
            SELECT * FROM chat_conversation
            WHERE user_id = #{userId}
            ORDER BY updated_at DESC, id DESC
            """)
    List<ChatConversation> findByUserId(@Param("userId") String userId);

    /**
     * 按状态查询用户会话。
     */
    @Select("""
            SELECT * FROM chat_conversation
            WHERE user_id = #{userId}
              AND status = #{status}
            ORDER BY updated_at DESC, id DESC
            """)
    List<ChatConversation> findByUserIdAndStatusValue(
            @Param("userId") String userId,
            @Param("status") String status);

    default List<ChatConversation> findByUserIdAndStatus(
            String userId, ConversationStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("会话状态不能为空");
        }
        return findByUserIdAndStatusValue(userId, status.value());
    }

    /**
     * 新建会话，id 由 MySQL 回填。
     */
    @Insert("""
            INSERT INTO chat_conversation
            (conversation_id, user_id, title, status)
            VALUES
            (#{conversationId}, #{userId}, #{title}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ChatConversation conversation);

    /**
     * 更新会话的可修改字段。
     */
    @Update("""
            UPDATE chat_conversation
            SET title = #{title},
                status = #{status}
            WHERE conversation_id = #{conversationId}
            """)
    int update(ChatConversation conversation);

    @Update("""
            UPDATE chat_conversation
            SET title = #{title}
            WHERE conversation_id = #{conversationId}
            """)
    int updateTitle(@Param("conversationId") String conversationId,
                    @Param("title") String title);

    @Update("""
            UPDATE chat_conversation
            SET status = #{status}
            WHERE conversation_id = #{conversationId}
            """)
    int updateStatusValue(@Param("conversationId") String conversationId,
                          @Param("status") String status);

    default int updateStatus(
            String conversationId, ConversationStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("会话状态不能为空");
        }
        return updateStatusValue(conversationId, status.value());
    }

    /**
     * 新增消息后刷新会话时间，保证会话列表按最近聊天排序。
     */
    @Update("""
            UPDATE chat_conversation
            SET updated_at = CURRENT_TIMESTAMP
            WHERE conversation_id = #{conversationId}
            """)
    int touch(@Param("conversationId") String conversationId);

    /**
     * 物理删除会话；数据库外键会级联删除所属消息。
     */
    @Delete("DELETE FROM chat_conversation WHERE conversation_id = #{conversationId}")
    int deleteByConversationId(
            @Param("conversationId") String conversationId);
}

package com.llmstudy.rag.module.chat.conversation;

import com.llmstudy.rag.entity.ChatConversation;
import com.llmstudy.rag.entity.ChatMessage;
import com.llmstudy.rag.enums.MessageType;

import java.util.List;

/**
 * 聊天会话服务接口
 */
public interface ConversationService {

    /**
     * 创建会话。
     *
     * @param userId 用户唯一标识
     * @param title  会话标题
     * @return 创建后的会话
     */
    ChatConversation createConversation(String userId, String title);

    /**
     * 按会话 ID 查询会话。
     *
     * @param conversationId 会话唯一标识
     * @return 会话实体；不存在时返回 null
     */
    ChatConversation getConversation(String conversationId);

    /**
     * 查询指定用户的全部会话。
     *
     * @param userId 用户唯一标识
     * @return 会话列表
     */
    List<ChatConversation> listConversations(String userId);

    /**
     * 更新会话标题。
     *
     * @param conversationId 会话唯一标识
     * @param title          新标题
     */
    void updateConversationTitle(String conversationId, String title);

    /**
     * 获取会话，不存在时按指定 ID 创建新会话。
     *
     * @param conversationId 会话唯一标识（由调用方/前端传入）
     * @param userId         用户唯一标识
     * @param title          新会话标题（仅创建时生效）
     * @return 会话实体
     */
    ChatConversation getOrCreateConversation(String conversationId, String userId, String title);

    /**
     * 删除会话（建议逻辑删除）。
     *
     * @param conversationId 会话唯一标识
     */
    void deleteConversation(String conversationId);

    /**
     * 保存一条聊天消息。
     *
     * @param conversationId 所属会话标识
     * @param type           消息类型
     * @param content        消息内容
     * @param modelName      模型名称，未知时传 null
     * @return 保存后的消息
     */
    ChatMessage saveMessage(String conversationId, MessageType type, String content, String modelName);

    /**
     * 保存一条带模型用量信息的聊天消息。
     *
     * @param conversationId 所属会话标识
     * @param type           消息类型
     * @param content        消息内容
     * @param tokenCount     Token 总数，未获取到时传 null
     * @param modelName      模型名称，未获取到时传 null
     * @return 保存后的消息
     */
    ChatMessage saveMessage(String conversationId,
                            MessageType type,
                            String content,
                            Integer tokenCount,
                            String modelName);

    /**
     * 保存带 RAG 引用和扩展元数据的完整消息。
     *
     * @param conversationId 所属会话标识
     * @param type           消息类型
     * @param content        消息内容
     * @param tokenCount     Token 总数，未获取到时传 null
     * @param modelName      模型名称，未获取到时传 null
     * @param ragReferences  序列化后的 RAG 引用 JSON
     * @param metadata       消息扩展 metadata JSON
     * @return 保存后的消息
     */
    ChatMessage saveMessage(String conversationId,
                            MessageType type,
                            String content,
                            Integer tokenCount,
                            String modelName,
                            String ragReferences,
                            String metadata);

    /**
     * 按消息 ID 查询消息。
     *
     * @param messageId 消息唯一标识
     * @return 消息实体；不存在时返回 null
     */
    ChatMessage getMessage(String messageId);

    /**
     * 回写用户问题的改写结果。
     *
     * @param messageId        消息唯一标识
     * @param transformContent 改写后的内容
     */
    void updateMessageTransformContent(String messageId, String transformContent);

    /**
     * 只更新消息扩展元数据，不覆盖问题改写或回答内容。
     *
     * @param messageId 消息唯一标识
     * @param metadata  新的 metadata JSON
     */
    void updateMessageMetadata(String messageId, String metadata);

    /**
     * 查询某会话下的全部消息（按创建时间升序）。
     *
     * @param conversationId 会话唯一标识
     * @return 消息列表
     */
    List<ChatMessage> listMessages(String conversationId);

    /**
     * 查询某会话下最近 limit 条消息（按创建时间升序）。
     *
     * @param conversationId 会话唯一标识
     * @param limit          返回条数上限
     * @return 消息列表
     */
    List<ChatMessage> listRecentMessages(String conversationId, int limit);
}

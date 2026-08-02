package com.llmstudy.rag.service.impl;

import com.llmstudy.rag.entity.ChatConversation;
import com.llmstudy.rag.entity.ChatMessage;
import com.llmstudy.rag.enums.ConversationStatus;
import com.llmstudy.rag.enums.MessageType;
import com.llmstudy.rag.mapper.ChatConversationMapper;
import com.llmstudy.rag.mapper.ChatMessageMapper;
import com.llmstudy.rag.service.ChatService;
import com.llmstudy.rag.util.SnowflakeIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 聊天会话服务实现
 */
@Service
@Transactional(readOnly = true)
public class ChatServiceImpl implements ChatService {

    /** 与 chat_conversation.title 的 VARCHAR(255) 长度保持一致。 */
    private static final int DATABASE_TITLE_MAX_LENGTH = 255;

    /** 与 chat_message.model_name 的 VARCHAR(128) 长度保持一致。 */
    private static final int DATABASE_MODEL_NAME_MAX_LENGTH = 128;

    /** 会话表数据访问对象。 */
    private final ChatConversationMapper chatConversationMapper;

    /** 消息表数据访问对象。 */
    private final ChatMessageMapper chatMessageMapper;

    /** 生成趋势递增的消息业务 ID；会话 ID 单独使用 UUID。 */
    private final SnowflakeIdGenerator idGenerator;

    public ChatServiceImpl(ChatConversationMapper chatConversationMapper,
                           ChatMessageMapper chatMessageMapper,
                           SnowflakeIdGenerator idGenerator) {
        this.chatConversationMapper = chatConversationMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 创建新会话。会话 ID 使用 UUID，自增主键和审计时间交给 MySQL 生成。
     */
    @Override
    @Transactional
    public ChatConversation createConversation(String userId, String title) {
        // user_id 是表中必填字段，在进入 Mapper 前尽早拒绝无效参数。
        requireText(userId, "userId 不能为空");

        // 仅设置需要由业务生成的字段，id/created_at/updated_at 由数据库回填或维护。
        ChatConversation chatConversation = new ChatConversation();
        chatConversation.setConversationId(UUID.randomUUID().toString());
        chatConversation.setTitle(normalizeTitle(title));

        // todo userid从当前登录用户的上下文里拿到，暂未实现登陆逻辑
        chatConversation.setUserId(userId.trim());

        // 新会话统一从 ACTIVE 状态开始，MyBatis 将枚举名称持久化为大写字符串。
        chatConversation.setConversationStatus(ConversationStatus.ACTIVE);
        int insert = chatConversationMapper.insert(chatConversation);
        if (insert != 1) {
            throw new IllegalStateException("创建会话失败");
        }

        // 重新查询一次，让返回对象包含数据库生成的主键和审计时间。
        return chatConversationMapper.findByConversationId(
                chatConversation.getConversationId());
    }

    /**
     * 按会话业务 ID 查询会话，不存在时按接口约定返回 null。
     */
    @Override
    public ChatConversation getConversation(String conversationId) {
        requireText(conversationId, "conversationId 不能为空");
        return chatConversationMapper.findByConversationId(conversationId);
    }

    /**
     * 查询用户当前的活跃会话，不返回已归档或逻辑删除的记录。
     */
    @Override
    public List<ChatConversation> listConversations(String userId) {
        requireText(userId, "userId 不能为空");
        return chatConversationMapper.findByUserIdAndStatus(
                userId.trim(), ConversationStatus.ACTIVE);
    }

    /**
     * 更新会话标题，更新前确认会话存在并校验标题长度。
     */
    @Override
    @Transactional
    public void updateConversationTitle(String conversationId, String title) {
        requireConversation(conversationId);
        int updated = chatConversationMapper.updateTitle(
                conversationId, normalizeTitle(title));
        requireSingleRow(updated, "更新会话标题失败");
    }

    /**
     * 逻辑删除会话，仅将状态改为 DELETED，不删除历史消息。
     */
    @Override
    @Transactional
    public void deleteConversation(String conversationId) {
        requireConversation(conversationId);
        // 逻辑删除
        int updated = chatConversationMapper.updateStatus(
                conversationId, ConversationStatus.DELETED);
        requireSingleRow(updated, "删除会话失败");
    }

    /**
     * 保存不带 Token 数量的消息，作为普通 USER/SYSTEM 消息的便捷入口。
     */
    @Override
    @Transactional
    public ChatMessage saveMessage(String conversationId,
                                   MessageType type,
                                   String content,
                                   String modelName) {
        // 统一委托给完整方法，避免两套消息持久化逻辑产生偏差。
        return saveMessage(conversationId, type, content, null, modelName);
    }

    /**
     * 保存完整消息及模型用量，并在同一事务中刷新会话的更新时间。
     */
    @Override
    @Transactional
    public ChatMessage saveMessage(String conversationId,
                                   MessageType type,
                                   String content,
                                   Integer tokenCount,
                                   String modelName) {
        // 先确认父会话存在，避免最终依赖外键异常才发现参数错误。
        ChatConversation conversation = requireConversation(conversationId);

        // 归档或删除后的会话只允许读取，不再接受新消息。
        if (conversation.getStatus() != ConversationStatus.ACTIVE) {
            throw new IllegalArgumentException(
                    "当前会话不可继续发送消息，状态: "
                            + conversation.getStatus());
        }
        // type/content 对应表中 NOT NULL 字段，在 Service 层返回更清晰的业务错误。
        if (type == null) {
            throw new IllegalArgumentException("消息类型不能为空");
        }
        requireText(content, "消息内容不能为空");
        // 表字段是 INT UNSIGNED，提前拒绝负数，避免抛出难以理解的 SQL 异常。
        if (tokenCount != null && tokenCount < 0) {
            throw new IllegalArgumentException("Token 数量不能小于 0");
        }
        String normalizedModelName = normalizeModelName(modelName);

        // 消息 ID 使用雪花算法，趋势递增且适合作为外部业务标识。
        ChatMessage message = new ChatMessage();
        message.setMessageId(nextBusinessId());
        message.setConversationId(conversationId);
        message.setMessageType(type);
        message.setContent(content);
        message.setTokenCount(tokenCount);
        message.setModelName(normalizedModelName);

        // 先写入消息，再刷新会话时间；任意一步失败都由 @Transactional 整体回滚。
        int inserted = chatMessageMapper.insert(message);
        requireSingleRow(inserted, "保存消息失败");

        int touched = chatConversationMapper.touch(conversationId);
        requireSingleRow(touched, "刷新会话时间失败");

        // 重新查询以返回数据库生成的自增主键和创建/更新时间。
        return chatMessageMapper.findByMessageId(message.getMessageId());
    }

    /**
     * 按消息业务 ID 查询消息，不存在时返回 null。
     */
    @Override
    public ChatMessage getMessage(String messageId) {
        requireText(messageId, "messageId 不能为空");
        return chatMessageMapper.findByMessageId(messageId);
    }

    /**
     * 按创建时间正序返回会话的完整消息历史。
     */
    @Override
    public List<ChatMessage> listMessages(String conversationId) {
        requireConversation(conversationId);
        return chatMessageMapper.findByConversationId(conversationId);
    }

    /**
     * 恢复调用方指定的会话；若不存在，则使用该 ID 创建会话。
     */
    @Override
    @Transactional
    public ChatConversation getOrCreateConversation(String conversationId,
                                                    String userId,
                                                    String title) {
        requireText(conversationId, "conversationId 不能为空");
        requireText(userId, "userId 不能为空");

        // 已有会话直接复用，标题只在首次创建时生效。
        ChatConversation existing =
                chatConversationMapper.findByConversationId(conversationId);
        if (existing != null) {
            return existing;
        }

        // 该分支用于兼容前端主动传入会话 ID 的场景；新版前端通常使用 createConversation 返回的 UUID。
        ChatConversation conversation = new ChatConversation();
        conversation.setConversationId(conversationId.trim());
        // todo userid从当前登录用户的上下文里拿到，暂未实现登陆逻辑
        conversation.setUserId(userId.trim());
        conversation.setTitle(normalizeTitle(title));
        conversation.setConversationStatus(ConversationStatus.ACTIVE);
        int inserted = chatConversationMapper.insert(conversation);
        requireSingleRow(inserted, "创建会话失败");
        return conversation;
    }

    /**
     * 查询最近 limit 条消息，Mapper 会将结果重新按时间正序排列供模型使用。
     */
    @Override
    public List<ChatMessage> listRecentMessages(String conversationId, int limit) {
        requireConversation(conversationId);
        if (limit <= 0) {
            return List.of();
        }
        return chatMessageMapper.findRecentByConversationId(conversationId, limit);
    }

    /**
     * 查询必须存在的会话，统一处理参数为空和会话不存在两类错误。
     */
    private ChatConversation requireConversation(String conversationId) {
        requireText(conversationId, "conversationId 不能为空");
        ChatConversation conversation =
                chatConversationMapper.findByConversationId(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException(
                    "会话不存在: " + conversationId);
        }
        return conversation;
    }

    /**
     * 规范化会话标题：空值转为空字符串，非空值去除首尾空格并校验表字段长度。
     */
    private String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }
        String normalized = title.trim();
        if (normalized.length() > DATABASE_TITLE_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "会话标题不能超过 "
                            + DATABASE_TITLE_MAX_LENGTH + " 个字符");
        }
        return normalized;
    }

    /**
     * 规范化模型名称：供应商未返回时保留 null，有值时校验长度。
     */
    private String normalizeModelName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return null;
        }
        String normalized = modelName.trim();
        if (normalized.length() > DATABASE_MODEL_NAME_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "模型名称不能超过 "
                            + DATABASE_MODEL_NAME_MAX_LENGTH + " 个字符");
        }
        return normalized;
    }

    /**
     * 生成消息等记录使用的雪花业务 ID，转为字符串后与 VARCHAR(64) 表字段对应。
     */
    private String nextBusinessId() {
        return String.valueOf(idGenerator.nextId());
    }

    /**
     * 校验必填字符串，同时拒绝 null、空字符串和纯空格。
     */
    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 确保单记录写操作恰好影响一行，避免静默忽略未更新或异常多更新。
     */
    private void requireSingleRow(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new IllegalStateException(message);
        }
    }
}

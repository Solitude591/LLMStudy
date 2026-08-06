package com.llmstudy.rag.module.chat.conversation;

import com.llmstudy.rag.config.ChatProperties;
import com.llmstudy.rag.entity.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话历史的 Redis 滑动窗口缓存，作为 MySQL 之上的加速层。
 *
 * <p>Redis 故障、数据损坏均在此类内部降级，绝不阻断 MySQL 主链路
 * （与 RAG 父分片二级缓存的容错策略一致）。窗口大小与模型上下文
 * 上限共用 {@link ChatProperties#getHistoryLimit()}，保证缓存命中的
 * 数据量恒等于调用方期望的条数。</p>
 */
@Component
public class ChatHistoryCache {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryCache.class);
    private static final String KEY_PREFIX = "chat:history:v2:";
    private static final DefaultRedisScript<Long> PUSH = script(
            "redis/chat-history-push.lua");
    private static final DefaultRedisScript<Long> REPLACE = script(
            "redis/chat-history-replace.lua");

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final ChatProperties properties;

    public ChatHistoryCache(StringRedisTemplate redis,
                            JsonMapper jsonMapper,
                            ChatProperties properties) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
    }

    /**
     * 读取窗口缓存。
     *
     * @return 消息列表；缓存未命中、数据损坏或 Redis 不可用时返回 null，
     *         由调用方回源 MySQL
     */
    public List<ChatMessage> get(String conversationId, long expectedVersion) {
        String redisKey = key(conversationId);
        try {
            List<String> values = redis.opsForList().range(
                    redisKey, 0, -1);
            if (values == null || values.isEmpty()) {
                return null;
            }
            long cachedVersion;
            try {
                cachedVersion = Long.parseLong(values.getFirst());
            } catch (NumberFormatException e) {
                delete(conversationId);
                return null;
            }

            if (cachedVersion != expectedVersion) {
                return null;
            }
            // 滑动续期；续期失败不影响本次已经成功读取的数据
            try {
                redis.expire(
                        redisKey,
                        java.time.Duration.ofSeconds(
                                properties.getHistoryCacheTtlSeconds()));
            } catch (Exception e) {
                log.warn("刷新历史缓存 TTL 失败: conversationId={}",
                        conversationId, e);
            }

            List<ChatMessage> result =
                    new ArrayList<>(Math.max(0, values.size() - 1));

            for (int i = 1; i < values.size(); i++) {
                result.add(jsonMapper.readValue(
                        values.get(i), ChatMessage.class));
            }

            return List.copyOf(result);
        } catch (Exception e) {
            log.warn("读取会话历史缓存失败，回退 MySQL: conversationId={}",
                    conversationId, e);
            return null;
        }
    }

    /**
     * 新消息落库成功后追加到窗口，自动裁剪为最近 N 条并续期。
     * 异常已在此消化，不影响调用方事务。
     *
     * @param conversationId 所属会话标识
     * @param message        已落库的完整消息（含数据库回填的审计时间）
     */
    public void push(String conversationId,
                     ChatMessage message,
                     long messageVersion) {
        try {
            redis.execute(PUSH, List.of(key(conversationId)),
                    String.valueOf(messageVersion),
                    jsonMapper.writeValueAsString(message),
                    String.valueOf(properties.getHistoryLimit()),
                    String.valueOf(properties.getHistoryCacheTtlSeconds()));
        } catch (Exception e) {
            log.warn("写入会话历史缓存失败: conversationId={}, version={}",
                    conversationId, messageVersion, e);
        }
    }

    /**
     * MySQL 回填时整体重建窗口；结果为空也回填，避免空会话反复穿透。
     * 异常已在此消化，不影响调用方主流程。
     *
     * @param conversationId 所属会话标识
     * @param messages       最近 N 条消息（时间正序）
     */
    public void replace(String conversationId,
                        List<ChatMessage> messages,
                        long messageVersion) {
        try {
            int windowSize = properties.getHistoryLimit();
            List<ChatMessage> window = messages.size() <= windowSize
                    ? messages
                    : messages.subList(messages.size() - windowSize,
                            messages.size());

            List<String> args = new ArrayList<>(window.size() + 3);
            args.add(String.valueOf(messageVersion));
            args.add(String.valueOf(windowSize));
            args.add(String.valueOf(properties.getHistoryCacheTtlSeconds()));
            for (ChatMessage message : window) {
                args.add(jsonMapper.writeValueAsString(message));
            }
            redis.execute(REPLACE, List.of(key(conversationId)),
                    args.toArray());
        } catch (Exception e) {
            log.warn("重建会话历史缓存失败: conversationId={}, version={}",
                    conversationId, messageVersion, e);
        }
    }

    /**
     * 删除会话时同步清理缓存。
     *
     * @param conversationId 所属会话标识
     */
    public void delete(String conversationId) {
        try {
            redis.delete(key(conversationId));
        } catch (Exception e) {
            log.warn("删除会话历史缓存失败: conversationId={}",
                    conversationId, e);
        }
    }

    private String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }

    private static DefaultRedisScript<Long> script(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(Long.class);
        return script;
    }
}

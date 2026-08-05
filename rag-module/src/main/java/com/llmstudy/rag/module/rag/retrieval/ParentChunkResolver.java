package com.llmstudy.rag.module.rag.retrieval;

import com.llmstudy.rag.entity.KnowledgeSegment;
import com.llmstudy.rag.mapper.KnowledgeSegmentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;

/** 按“请求级缓存 → Redis → MySQL”三级链路回查父分片。 */
@Component
public class ParentChunkResolver {

    private static final Logger log = LoggerFactory.getLogger(ParentChunkResolver.class);
    private static final String KEY_PREFIX = "rag:parent-chunk:v1:";
    private static final Duration TTL = Duration.ofHours(1);
    private final KnowledgeSegmentMapper mapper;
    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;

    public ParentChunkResolver(KnowledgeSegmentMapper mapper,
                               StringRedisTemplate redis,
                               JsonMapper jsonMapper) {
        this.mapper = mapper;
        this.redis = redis;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 解析父分片。Redis 异常会降级 MySQL，MySQL 异常返回 null 以保留子分片。
     *
     * @param chunkId     父分片业务 ID
     * @param requestCache 当前检索请求内共享的一级缓存
     * @return 父分片；不存在或回查失败时返回 null
     */
    public KnowledgeSegment resolve(String chunkId,
                                    Map<String, KnowledgeSegment> requestCache) {
        // L1 只在单次 KNN 检索内存活，无序列化或网络开销。
        KnowledgeSegment local = requestCache.get(chunkId);
        if (local != null) {
            return local;
        }
        // L2 为跨请求 Redis 缓存，损坏数据或连接故障均不阻断 L3。
        try {
            String value = redis.opsForValue().get(KEY_PREFIX + chunkId);
            if (value != null && !value.isBlank()) {
                KnowledgeSegment cached = jsonMapper.readValue(value, KnowledgeSegment.class);
                requestCache.put(chunkId, cached);
                return cached;
            }
        } catch (Exception e) {
            log.warn("读取 Redis 父分片缓存失败，回退数据库: chunkId={}", chunkId, e);
        }
        // L3 MySQL 是权威数据源；命中后回填前两级缓存。
        try {
            KnowledgeSegment segment = mapper.findByChunkId(chunkId);
            if (segment == null || segment.getText() == null || segment.getText().isBlank()) {
                return segment;
            }
            requestCache.put(chunkId, segment);
            try {
                redis.opsForValue().set(KEY_PREFIX + chunkId,
                        jsonMapper.writeValueAsString(segment), TTL);
            } catch (Exception e) {
                log.warn("写入 Redis 父分片缓存失败: chunkId={}", chunkId, e);
            }
            return segment;
        } catch (Exception e) {
            log.warn("查询父分片失败，保留子分片: chunkId={}", chunkId, e);
            return null;
        }
    }
}

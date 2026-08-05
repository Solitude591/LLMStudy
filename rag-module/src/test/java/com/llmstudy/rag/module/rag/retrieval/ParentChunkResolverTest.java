package com.llmstudy.rag.module.rag.retrieval;

import com.llmstudy.rag.entity.KnowledgeSegment;
import com.llmstudy.rag.mapper.KnowledgeSegmentMapper;
import com.llmstudy.rag.enums.SegmentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ParentChunkResolverTest {

    @Test
    void redisHitSkipsMysql() throws Exception {
        KnowledgeSegmentMapper mapper = mock(KnowledgeSegmentMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        JsonMapper json = JsonMapper.builder().build();
        KnowledgeSegment parent = parent();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("rag:parent-chunk:v1:p-1"))
                .thenReturn(json.writeValueAsString(parent));

        KnowledgeSegment resolved = new ParentChunkResolver(mapper, redis, json)
                .resolve("p-1", new HashMap<>());

        assertEquals("parent text", resolved.getText());
        verifyNoInteractions(mapper);
    }

    @Test
    void mysqlFallbackWritesOneHourCache() throws Exception {
        KnowledgeSegmentMapper mapper = mock(KnowledgeSegmentMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        JsonMapper json = JsonMapper.builder().build();
        KnowledgeSegment parent = parent();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("rag:parent-chunk:v1:p-1")).thenReturn(null);
        when(mapper.findByChunkId("p-1")).thenReturn(parent);

        new ParentChunkResolver(mapper, redis, json)
                .resolve("p-1", new HashMap<>());

        verify(values).set("rag:parent-chunk:v1:p-1",
                json.writeValueAsString(parent), Duration.ofHours(1));
    }

    private static KnowledgeSegment parent() {
        KnowledgeSegment segment = new KnowledgeSegment();
        segment.setChunkId("p-1");
        segment.setText("parent text");
        segment.setSegmentStatus(SegmentStatus.INIT);
        return segment;
    }
}

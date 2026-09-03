package com.llmstudy.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ElasticsearchMappingTest {

    @Test
    void mappingIsSchemaV4WithSearchableHeaderPath() throws Exception {
        try (InputStream in = new ClassPathResource(
                "elasticsearch/know-engine-mapping.json").getInputStream()) {
            Map<?, ?> root = new JsonMapper().readValue(in, Map.class);
            Map<?, ?> mappings = (Map<?, ?>) root.get("mappings");
            Map<?, ?> meta = (Map<?, ?>) mappings.get("_meta");
            assertEquals(4, ((Number) meta.get("schema_version")).intValue());
            Map<?, ?> metadata = (Map<?, ?>) ((Map<?, ?>) mappings.get("properties")).get("metadata");
            Map<?, ?> fields = (Map<?, ?>) metadata.get("properties");
            assertEquals("keyword", ((Map<?, ?>) fields.get("language")).get("type"));
            // 章节标题必须可检索：keyword + index:false 时 BM25 完全看不到标题词。
            Map<?, ?> headerPath = (Map<?, ?>) fields.get("header_path");
            assertEquals("text", headerPath.get("type"));
            assertEquals("ik_max_word", headerPath.get("analyzer"));
            // 中文按词而不是按单字切分，否则 BM25 退化成字符匹配。
            assertEquals("ik_max_word",
                    ((Map<?, ?>) ((Map<?, ?>) mappings.get("properties")).get("text"))
                            .get("analyzer"));
            assertEquals(8, fields.size());
        }
    }
}

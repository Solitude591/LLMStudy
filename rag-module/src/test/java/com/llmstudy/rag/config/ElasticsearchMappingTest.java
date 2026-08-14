package com.llmstudy.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ElasticsearchMappingTest {

    @Test
    void mappingIsSchemaV3WithLanguageKeyword() throws Exception {
        try (InputStream in = new ClassPathResource(
                "elasticsearch/know-engine-mapping.json").getInputStream()) {
            Map<?, ?> root = new JsonMapper().readValue(in, Map.class);
            Map<?, ?> mappings = (Map<?, ?>) root.get("mappings");
            Map<?, ?> meta = (Map<?, ?>) mappings.get("_meta");
            assertEquals(3, ((Number) meta.get("schema_version")).intValue());
            Map<?, ?> metadata = (Map<?, ?>) ((Map<?, ?>) mappings.get("properties")).get("metadata");
            Map<?, ?> fields = (Map<?, ?>) metadata.get("properties");
            assertEquals("keyword", ((Map<?, ?>) fields.get("language")).get("type"));
            assertEquals(8, fields.size());
        }
    }
}

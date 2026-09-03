package com.llmstudy.rag.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import co.elastic.clients.json.JsonData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Map;
import java.util.Objects;

/** 启动时幂等创建并校验 RAG 向量索引，避免首次写入触发动态 mapping。 */
@Component
public class ElasticsearchIndexInitializer implements InitializingBean {

    private static final Logger log =
            LoggerFactory.getLogger(ElasticsearchIndexInitializer.class);
    private static final String MAPPING_RESOURCE =
            "elasticsearch/know-engine-mapping.json";
    private static final int SCHEMA_VERSION = 4;
    /** 与 mapping 文件保持一致；不校验分词器时，旧索引会静默退化为单字切分。 */
    private static final String TEXT_ANALYZER = "ik_max_word";

    private final ElasticsearchClient client;
    private final ElasticsearchProperties properties;

    public ElasticsearchIndexInitializer(ElasticsearchClient client,
                                         ElasticsearchProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        String indexName = properties.getIndexName();
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalStateException("elasticsearch.index-name 不能为空");
        }
        if (properties.getDimensions() <= 0) {
            throw new IllegalStateException("elasticsearch.dimensions 必须大于 0");
        }

        if (!indexExists(indexName)) {
            createIndex(indexName);
        } else {
            maybeUpgradeSchema(indexName);
        }
        validateIndex(indexName);
        log.info("Elasticsearch 索引已就绪: index={}, schemaVersion={}, dimensions={}",
                indexName, SCHEMA_VERSION, properties.getDimensions());
    }

    private boolean indexExists(String indexName) throws Exception {
        return client.indices().exists(request -> request.index(indexName)).value();
    }

    private void createIndex(String indexName) throws Exception {
        ClassPathResource mapping = new ClassPathResource(MAPPING_RESOURCE);
        try (InputStream input = mapping.getInputStream()) {
            boolean acknowledged = client.indices().create(request -> request
                    .index(indexName)
                    .withJson(input)).acknowledged();
            if (!acknowledged) {
                throw new IllegalStateException("Elasticsearch 未确认索引创建: " + indexName);
            }
            log.info("Elasticsearch 索引创建完成: {}", indexName);
        } catch (ElasticsearchException exception) {
            if ("resource_already_exists_exception".equals(exception.error().type())) {
                log.info("Elasticsearch 索引已由其他实例创建: {}", indexName);
                return;
            }
            throw exception;
        }
    }

    /**
     * schema 1/2 → 3：补齐页码与 language，并更新 schema_version。
     *
     * <p>3 → 4 把 header_path 从 keyword 改成可检索 text，属于不可原地变更的字段类型改动，
     * 只能重建索引后 reindex，这里不做静默升级，交由 {@link #validateIndex} 报错。</p>
     */
    private void maybeUpgradeSchema(String indexName) throws Exception {
        IndexMappingRecord record = client.indices()
                .getMapping(request -> request.index(indexName))
                .get(indexName);
        if (record == null) {
            return;
        }
        JsonData schemaVersion = record.mappings().meta().get("schema_version");
        Integer actual = schemaVersion == null ? null : schemaVersion.to(Integer.class);
        if (Objects.equals(actual, SCHEMA_VERSION)) {
            return;
        }
        if (!Objects.equals(actual, 1) && !Objects.equals(actual, 2)) {
            return;
        }
        boolean addPages = Objects.equals(actual, 1);
        client.indices().putMapping(request -> request
                .index(indexName)
                .properties("metadata", metadata -> metadata.object(object -> {
                    object.properties("language", lang -> lang.keyword(keyword -> keyword
                            .ignoreAbove(16)));
                    if (addPages) {
                        object.properties("page_start", page -> page.integer(integer -> integer
                                        .index(false)
                                        .docValues(false)))
                                .properties("page_end", page -> page.integer(integer -> integer
                                        .index(false)
                                        .docValues(false)));
                    }
                    return object;
                }))
                .meta("schema_version", JsonData.of(SCHEMA_VERSION)));
        log.info("Elasticsearch 索引 schema 已升级: index={}, {} -> {}",
                indexName, actual, SCHEMA_VERSION);
    }

    private void validateIndex(String indexName) throws Exception {
        IndexMappingRecord record = client.indices()
                .getMapping(request -> request.index(indexName))
                .get(indexName);
        if (record == null) {
            throw incompatible(indexName, "无法读取 mapping");
        }

        TypeMapping mapping = record.mappings();
        require(indexName, mapping.dynamic() == DynamicMapping.Strict,
                "顶层 dynamic 必须为 strict");

        JsonData schemaVersion = mapping.meta().get("schema_version");
        Integer actualSchemaVersion = schemaVersion == null
                ? null : schemaVersion.to(Integer.class);
        require(indexName, Objects.equals(actualSchemaVersion, SCHEMA_VERSION),
                "schema_version 应为 " + SCHEMA_VERSION + "，实际为 " + actualSchemaVersion);

        Map<String, Property> topLevel = mapping.properties();
        Property vector = topLevel.get("vector");
        require(indexName, vector != null && vector.isDenseVector(),
                "vector 必须为 dense_vector");
        require(indexName, Objects.equals(vector.denseVector().dims(), properties.getDimensions()),
                "vector.dims 与 elasticsearch.dimensions 不一致");
        require(indexName, vector.denseVector().similarity() == DenseVectorSimilarity.Cosine,
                "vector.similarity 必须为 cosine");

        Property text = topLevel.get("text");
        require(indexName, text != null && text.isText(), "text 必须为 text");
        // 分词器决定中文 BM25 是按词还是按单字召回，索引一旦建好就无法原地改，必须校验。
        require(indexName, TEXT_ANALYZER.equals(text.text().analyzer()),
                "text.analyzer 应为 " + TEXT_ANALYZER + "，实际为 " + text.text().analyzer());

        Property metadata = topLevel.get("metadata");
        require(indexName, metadata != null && metadata.isObject(),
                "metadata 必须为 object");
        require(indexName, metadata.object().dynamic() == DynamicMapping.Strict,
                "metadata.dynamic 必须为 strict");

        Map<String, Property> metadataFields = metadata.object().properties();
        requireKeyword(indexName, metadataFields, "doc_id");
        requireTextWithKeyword(indexName, metadataFields, "version_id");
        requireKeyword(indexName, metadataFields, "parent_chunk_id");
        requireTextWithKeyword(indexName, metadataFields, "header_path");
        requireKeyword(indexName, metadataFields, "source_url");
        requireInteger(indexName, metadataFields, "page_start");
        requireInteger(indexName, metadataFields, "page_end");
        requireKeyword(indexName, metadataFields, "language");
        require(indexName, metadataFields.size() == 8,
                "metadata 只能包含约定的 8 个字段，实际为 " + metadataFields.keySet());
    }

    private static void requireKeyword(String indexName,
                                       Map<String, Property> fields,
                                       String fieldName) {
        Property property = fields.get(fieldName);
        require(indexName, property != null && property.isKeyword(),
                "metadata." + fieldName + " 必须为 keyword");
    }

    private static void requireTextWithKeyword(String indexName,
                                               Map<String, Property> fields,
                                               String fieldName) {
        Property property = fields.get(fieldName);
        require(indexName, property != null && property.isText(),
                "metadata." + fieldName + " 必须为 text");
        Property keyword = property.text().fields().get("keyword");
        require(indexName, keyword != null && keyword.isKeyword(),
                "metadata." + fieldName + ".keyword 必须存在且为 keyword");
    }

    private static void requireInteger(String indexName,
                                       Map<String, Property> fields,
                                       String fieldName) {
        Property property = fields.get(fieldName);
        require(indexName, property != null && property.isInteger(),
                "metadata." + fieldName + " 必须为 integer");
    }

    private static void require(String indexName, boolean condition, String message) {
        if (!condition) {
            throw incompatible(indexName, message);
        }
    }

    private static IllegalStateException incompatible(String indexName, String message) {
        return new IllegalStateException("Elasticsearch 索引 " + indexName
                + " 与当前 mapping 不兼容：" + message
                + "。请迁移索引或删除后重新启动应用");
    }
}

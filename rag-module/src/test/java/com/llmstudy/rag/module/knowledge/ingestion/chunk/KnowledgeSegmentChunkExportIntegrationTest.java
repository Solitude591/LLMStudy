package com.llmstudy.rag.module.knowledge.ingestion.chunk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 从真实 MySQL 导出指定文档的全部 chunk，供人工检查切分质量。
 *
 * <p>默认不运行，示例：</p>
 * <pre>
 * mvn -Dtest=KnowledgeSegmentChunkExportIntegrationTest \
 *     -Dchunk-export.doc-id=212544606686941184 test
 * </pre>
 */
class KnowledgeSegmentChunkExportIntegrationTest {

    private static final String DOC_ID_PROPERTY = "chunk-export.doc-id";
    private static final String OUTPUT_DIR_PROPERTY = "chunk-export.output-dir";

    @Test
    @EnabledIfSystemProperty(named = DOC_ID_PROPERTY, matches = "\\d+")
    void exportsAllChunksForManualReview() throws Exception {
        String docId = System.getProperty(DOC_ID_PROPERTY);
        Properties application = loadApplicationProperties();

        String jdbcUrl = setting(application,
                "chunk-export.jdbc-url", "SPRING_DATASOURCE_URL", "spring.datasource.url");
        String username = setting(application,
                "chunk-export.jdbc-user", "SPRING_DATASOURCE_USERNAME", "spring.datasource.username");
        String password = setting(application,
                "chunk-export.jdbc-password", "SPRING_DATASOURCE_PASSWORD", "spring.datasource.password");
        String driver = application.getProperty(
                "spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");

        Class.forName(driver);
        List<ChunkRow> chunks;
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            connection.setReadOnly(true);
            chunks = queryChunks(connection, docId);
        }

        assertFalse(chunks.isEmpty(), () -> "没有查询到 docId=" + docId + " 的 chunk");
        Path outputDirectory = resolveOutputDirectory(docId);
        Files.createDirectories(outputDirectory);
        Files.writeString(outputDirectory.resolve("summary.md"),
                renderSummary(docId, chunks), StandardCharsets.UTF_8);
        Files.writeString(outputDirectory.resolve("all-chunks.md"),
                renderAllChunks(docId, chunks), StandardCharsets.UTF_8);

        System.out.printf("已导出 docId=%s 的 %d 个 chunk 到 %s%n",
                docId, chunks.size(), outputDirectory.toAbsolutePath());
    }

    private static List<ChunkRow> queryChunks(Connection connection, String docId) throws Exception {
        String sql = """
                SELECT chunk_id, doc_id, version_id, chunk_order, skip_embedding,
                       embedding_id, status, metadata, text
                FROM knowledge_segment
                WHERE doc_id = ?
                ORDER BY version_id ASC, chunk_order ASC, id ASC
                """;
        List<ChunkRow> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, docId);
            statement.setFetchSize(200);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new ChunkRow(
                            rows.getString("chunk_id"),
                            rows.getString("doc_id"),
                            rows.getString("version_id"),
                            rows.getInt("chunk_order"),
                            rows.getBoolean("skip_embedding"),
                            rows.getString("embedding_id"),
                            rows.getString("status"),
                            rows.getString("metadata"),
                            rows.getString("text")));
                }
            }
        }
        return result;
    }

    private static Path resolveOutputDirectory(String docId) {
        String configured = System.getProperty(OUTPUT_DIR_PROPERTY, "log/chunk-review");
        return Path.of(configured).resolve(docId).normalize();
    }

    private static String renderSummary(String docId, List<ChunkRow> chunks) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        chunks.forEach(chunk -> counts.merge(chunk.type(), 1, Integer::sum));

        StringBuilder output = new StringBuilder();
        output.append("# Chunk review summary\n\n")
                .append("- doc_id: `").append(docId).append("`\n")
                .append("- total: ").append(chunks.size()).append("\n")
                .append("- parent: ").append(counts.getOrDefault("PARENT", 0)).append("\n")
                .append("- child: ").append(counts.getOrDefault("CHILD", 0)).append("\n")
                .append("- standalone: ").append(counts.getOrDefault("STANDALONE", 0)).append("\n\n")
                .append("| order | type | chars | version_id | page | header_path | chunk_id |\n")
                .append("| ---: | --- | ---: | --- | --- | --- | --- |\n");
        for (ChunkRow chunk : chunks) {
            output.append("| ").append(chunk.chunkOrder())
                    .append(" | ").append(chunk.type())
                    .append(" | ").append(chunk.codePointCount())
                    .append(" | ").append(cell(chunk.versionId()))
                    .append(" | ").append(cell(metadataValue(chunk.metadata(), "page_start")))
                    .append(pageEndSuffix(chunk.metadata()))
                    .append(" | ").append(cell(metadataValue(chunk.metadata(), "header_path")))
                    .append(" | ").append(cell(chunk.chunkId())).append(" |\n");
        }
        return output.toString();
    }

    private static String renderAllChunks(String docId, List<ChunkRow> chunks) {
        StringBuilder output = new StringBuilder();
        output.append("# All chunks for doc_id ").append(docId).append("\n\n");
        for (ChunkRow chunk : chunks) {
            output.append("## chunk_order=").append(chunk.chunkOrder())
                    .append(" · ").append(chunk.type()).append("\n\n")
                    .append("- chunk_id: `").append(chunk.chunkId()).append("`\n")
                    .append("- version_id: `").append(chunk.versionId()).append("`\n")
                    .append("- skip_embedding: `").append(chunk.skipEmbedding()).append("`\n")
                    .append("- status: `").append(value(chunk.status())).append("`\n")
                    .append("- embedding_id: `").append(value(chunk.embeddingId())).append("`\n")
                    .append("- code_points: ").append(chunk.codePointCount()).append("\n\n")
                    .append("### metadata\n\n")
                    .append(fenced(value(chunk.metadata()), "json"))
                    .append("\n### text\n\n")
                    .append(fenced(value(chunk.text()), "text"))
                    .append("\n---\n\n");
        }
        return output.toString();
    }

    private static String fenced(String content, String language) {
        int longestRun = 0;
        int currentRun = 0;
        for (int index = 0; index < content.length(); index++) {
            if (content.charAt(index) == '`') {
                longestRun = Math.max(longestRun, ++currentRun);
            } else {
                currentRun = 0;
            }
        }
        String fence = "`".repeat(Math.max(4, longestRun + 1));
        return fence + language + "\n" + content + "\n" + fence + "\n";
    }

    private static String metadataValue(String metadata, String key) {
        if (metadata == null || metadata.isBlank()) {
            return "";
        }
        String marker = "\"" + key + "\"";
        int keyStart = metadata.indexOf(marker);
        if (keyStart < 0) {
            return "";
        }
        int colon = metadata.indexOf(':', keyStart + marker.length());
        if (colon < 0) {
            return "";
        }
        int start = colon + 1;
        while (start < metadata.length() && Character.isWhitespace(metadata.charAt(start))) {
            start++;
        }
        if (start >= metadata.length()) {
            return "";
        }
        if (metadata.charAt(start) == '"') {
            int end = metadata.indexOf('"', start + 1);
            return end < 0 ? "" : metadata.substring(start + 1, end);
        }
        int end = start;
        while (end < metadata.length() && metadata.charAt(end) != ',' && metadata.charAt(end) != '}') {
            end++;
        }
        return metadata.substring(start, end).trim();
    }

    private static String pageEndSuffix(String metadata) {
        String start = metadataValue(metadata, "page_start");
        String end = metadataValue(metadata, "page_end");
        return end.isBlank() || end.equals(start) ? "" : "–" + cell(end);
    }

    private static String cell(String value) {
        return value(value).replace("|", "\\|").replace('\n', ' ');
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static Properties loadApplicationProperties() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));
        Properties properties = yaml.getObject();
        if (properties == null) {
            throw new IllegalStateException("无法读取 src/main/resources/application.yml");
        }
        return properties;
    }

    private static String setting(Properties properties,
                                  String systemProperty,
                                  String environmentVariable,
                                  String yamlKey) {
        String value = System.getProperty(systemProperty);
        if (value == null || value.isBlank()) {
            value = System.getenv(environmentVariable);
        }
        if (value == null || value.isBlank()) {
            value = properties.getProperty(yamlKey);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少数据库配置: " + yamlKey);
        }
        return value;
    }

    private record ChunkRow(String chunkId,
                            String docId,
                            String versionId,
                            int chunkOrder,
                            boolean skipEmbedding,
                            String embeddingId,
                            String status,
                            String metadata,
                            String text) {

        private String type() {
            if (skipEmbedding) {
                return "PARENT";
            }
            return metadata != null && metadata.contains("\"parent_chunk_id\"")
                    ? "CHILD" : "STANDALONE";
        }

        private int codePointCount() {
            return text == null ? 0 : text.codePointCount(0, text.length());
        }
    }
}

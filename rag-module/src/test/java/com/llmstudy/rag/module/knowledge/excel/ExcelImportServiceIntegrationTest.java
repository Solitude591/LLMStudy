package com.llmstudy.rag.module.knowledge.excel;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.llmstudy.rag.config.MinioProperties;
import com.llmstudy.rag.entity.KnowledgeDocumentVersion;
import com.llmstudy.rag.entity.TableMeta;
import com.llmstudy.rag.enums.DocumentStatus;
import com.llmstudy.rag.mapper.KnowledgeDocumentVersionMapper;
import com.llmstudy.rag.mapper.TableMetaMapper;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import okhttp3.Headers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@EnabledIfSystemProperty(named = "excel.db.integration", matches = "true")
class ExcelImportServiceIntegrationTest {

    private final String baseTableName =
            "excel_import_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    private JdbcTemplate jdbcTemplate() throws Exception {
        PropertySource<?> properties = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();
        String url = requiredProperty(properties, "spring.datasource.url");
        String username = requiredProperty(properties, "spring.datasource.username");
        String password = requiredProperty(properties, "spring.datasource.password");
        return new JdbcTemplate(new DriverManagerDataSource(url, username, password));
    }

    @AfterEach
    void cleanTables() throws Exception {
        JdbcTemplate jdbc = jdbcTemplate();
        jdbc.execute("DROP TABLE IF EXISTS `" + baseTableName + "_2`");
        jdbc.execute("DROP TABLE IF EXISTS `" + baseTableName + "`");
    }

    @Test
    void importDocument_每个Sheet建表并批量写入() throws Exception {
        byte[] workbook = buildWorkbook();
        KnowledgeDocumentVersionMapper versionMapper = mock(KnowledgeDocumentVersionMapper.class);
        TableMetaMapper tableMetaMapper = mock(TableMetaMapper.class);
        MinioClient minioClient = mock(MinioClient.class);
        JdbcTemplate jdbc = jdbcTemplate();

        stubSuccessfulLifecycle(versionMapper);
        when(tableMetaMapper.findByDocId("1001")).thenReturn(List.of());
        when(tableMetaMapper.insert(any())).thenReturn(1);
        when(tableMetaMapper.markImported(eq("1001"), anyInt(), anyLong()))
                .thenReturn(1);
        when(minioClient.getObject(any())).thenAnswer(invocation ->
                minioResponse(workbook));

        ExcelImportService service = new ExcelImportService(
                minioClient,
                minioProperties(),
                versionMapper,
                tableMetaMapper,
                new ExcelSplitter(),
                jdbc,
                JsonMapper.builder().build());

        service.importDocument(excelVersion(), baseTableName);

        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM `" + baseTableName + "`", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM `" + baseTableName + "_2`", Integer.class));
        Map<String, Object> firstRow = jdbc.queryForMap(
                "SELECT `_excel_row_no`, `姓名`, `年龄` FROM `"
                        + baseTableName + "` WHERE `_row_id` = 1");
        assertEquals(2, ((Number) firstRow.get("_excel_row_no")).intValue());
        assertEquals("张三", firstRow.get("姓名"));
        assertEquals("30", firstRow.get("年龄"));

        List<String> dataTypes = jdbc.queryForList("""
                SELECT DATA_TYPE FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND column_name NOT IN ('_row_id', '_excel_row_no')
                ORDER BY ORDINAL_POSITION
                """, String.class, baseTableName);
        assertTrue(dataTypes.stream().allMatch("varchar"::equals));

        ArgumentCaptor<TableMeta> metaCaptor = ArgumentCaptor.forClass(TableMeta.class);
        verify(tableMetaMapper, times(2)).insert(metaCaptor.capture());
        assertEquals(List.of(baseTableName, baseTableName + "_2"),
                metaCaptor.getAllValues().stream().map(TableMeta::getTableName).toList());
        assertTrue(metaCaptor.getAllValues().getFirst().getColumnMapping().contains("姓名"));
        verify(versionMapper).compareAndSetProcessingStatusAndClearError(
                "version-1001", DocumentStatus.IMPORTED, DocumentStatus.IMPORTING);
        verify(tableMetaMapper).markImported("1001", 1, 2);
        verify(tableMetaMapper).markImported("1001", 2, 1);
    }

    @Test
    void importDocument_目标表已存在时不覆盖() throws Exception {
        JdbcTemplate jdbc = jdbcTemplate();
        jdbc.execute("CREATE TABLE `" + baseTableName + "` (`value` VARCHAR(255))");
        jdbc.update("INSERT INTO `" + baseTableName + "` (`value`) VALUES ('keep-me')");

        KnowledgeDocumentVersionMapper versionMapper = mock(KnowledgeDocumentVersionMapper.class);
        TableMetaMapper tableMetaMapper = mock(TableMetaMapper.class);
        MinioClient minioClient = mock(MinioClient.class);
        stubSuccessfulLifecycle(versionMapper);
        when(tableMetaMapper.findByDocId("1001")).thenReturn(List.of());
        when(tableMetaMapper.insert(any())).thenReturn(1);
        when(minioClient.getObject(any())).thenAnswer(invocation ->
                minioResponse(buildWorkbook()));

        ExcelImportService service = new ExcelImportService(
                minioClient,
                minioProperties(),
                versionMapper,
                tableMetaMapper,
                new ExcelSplitter(),
                jdbc,
                JsonMapper.builder().build());

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> service.importDocument(excelVersion(), baseTableName));

        assertTrue(error.getMessage().contains("目标表已存在"));
        assertEquals("keep-me", jdbc.queryForObject(
                "SELECT `value` FROM `" + baseTableName + "`", String.class));
        verify(versionMapper).compareAndSetProcessingStatusWithError(
                eq("version-1001"),
                eq(DocumentStatus.UPLOADED),
                eq(DocumentStatus.IMPORTING),
                any(String.class));
        verify(versionMapper, never()).compareAndSetProcessingStatusAndClearError(
                "version-1001", DocumentStatus.IMPORTED, DocumentStatus.IMPORTING);
    }

    @Test
    void schemaSql_可以初始化Excel导入元数据结构() throws Exception {
        PropertySource<?> properties = loadApplicationProperties();
        String configuredUrl = requiredProperty(properties, "spring.datasource.url");
        String username = requiredProperty(properties, "spring.datasource.username");
        String password = requiredProperty(properties, "spring.datasource.password");
        String schemaName = "excel_schema_it_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        JdbcTemplate admin = new JdbcTemplate(
                new DriverManagerDataSource(configuredUrl, username, password));

        admin.execute("CREATE DATABASE `" + schemaName
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        try {
            DriverManagerDataSource testDataSource = new DriverManagerDataSource(
                    replaceDatabase(configuredUrl, schemaName), username, password);
            ResourceDatabasePopulator populator =
                    new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
            populator.execute(testDataSource);
            // schema.sql 配置为每次启动执行，必须保证重复运行幂等。
            populator.execute(testDataSource);
            JdbcTemplate testDatabase = new JdbcTemplate(testDataSource);

            assertEquals(1, testDatabase.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = DATABASE() AND table_name = 'table_meta'
                    """, Integer.class));
            assertEquals(3, testDatabase.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'knowledge_document'
                      AND column_name IN ('owner_user_id', 'visibility', 'organization_id')
                    """, Integer.class));
            assertEquals(1, testDatabase.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = DATABASE() AND table_name = 'auth_user'
                    """, Integer.class));
        } finally {
            if (!schemaName.matches("excel_schema_it_[a-f0-9]{12}")) {
                throw new IllegalStateException("拒绝删除非测试数据库: " + schemaName);
            }
            admin.execute("DROP DATABASE IF EXISTS `" + schemaName + "`");
        }
    }

    private void stubSuccessfulLifecycle(KnowledgeDocumentVersionMapper mapper) {
        when(mapper.compareAndSetProcessingStatus(
                "version-1001", DocumentStatus.IMPORTING, DocumentStatus.UPLOADED))
                .thenReturn(1);
        when(mapper.compareAndSetProcessingStatusAndClearError(
                "version-1001", DocumentStatus.IMPORTED, DocumentStatus.IMPORTING))
                .thenReturn(1);
    }

    private KnowledgeDocumentVersion excelVersion() {
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setVersionId("version-1001");
        version.setDocId("1001");
        version.setFileType("xlsx");
        version.setRawObjectKey("1001/raw/employees.xlsx");
        version.setDocumentStatus(DocumentStatus.UPLOADED);
        return version;
    }

    private MinioProperties minioProperties() {
        MinioProperties properties = new MinioProperties();
        properties.setBucketName("rag");
        return properties;
    }

    private GetObjectResponse minioResponse(byte[] data) {
        return new GetObjectResponse(
                new Headers.Builder().build(),
                "rag",
                "",
                "1001/raw/employees.xlsx",
                new ByteArrayInputStream(data));
    }

    private byte[] buildWorkbook() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = EasyExcel.write(out).build()) {
            WriteSheet employees = EasyExcel.writerSheet("员工表")
                    .head(List.of(List.of("姓名"), List.of("年龄")))
                    .build();
            writer.write(List.of(
                    List.of("张三", 30),
                    List.of("李四", 31)), employees);

            WriteSheet departments = EasyExcel.writerSheet("部门表")
                    .head(List.of(List.of("部门")))
                    .build();
            writer.write(List.of(List.of("研发")), departments);
        }
        return out.toByteArray();
    }

    private String requiredProperty(PropertySource<?> properties, String key) {
        Object value = properties.getProperty(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException("application.yml 缺少配置: " + key);
        }
        return value.toString();
    }

    private PropertySource<?> loadApplicationProperties() throws Exception {
        return new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();
    }

    private String replaceDatabase(String jdbcUrl, String databaseName) {
        int queryIndex = jdbcUrl.indexOf('?');
        int searchEnd = queryIndex >= 0 ? queryIndex : jdbcUrl.length();
        int slashIndex = jdbcUrl.lastIndexOf('/', searchEnd);
        if (slashIndex < 0) {
            throw new IllegalArgumentException("无法识别 JDBC URL 中的数据库名");
        }
        return jdbcUrl.substring(0, slashIndex + 1)
                + databaseName
                + jdbcUrl.substring(searchEnd);
    }
}

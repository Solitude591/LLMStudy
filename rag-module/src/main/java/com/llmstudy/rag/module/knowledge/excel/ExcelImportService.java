package com.llmstudy.rag.module.knowledge.excel;

import com.llmstudy.rag.config.MinioProperties;
import com.llmstudy.rag.entity.KnowledgeDocumentVersion;
import com.llmstudy.rag.entity.TableMeta;
import com.llmstudy.rag.enums.DocumentStatus;
import com.llmstudy.rag.enums.TableMetaStatus;
import com.llmstudy.rag.mapper.KnowledgeDocumentVersionMapper;
import com.llmstudy.rag.mapper.TableMetaMapper;
import com.llmstudy.rag.module.knowledge.ingestion.DocumentStageAlreadyRunningException;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Excel 结构化导入服务：每个非空 Sheet 创建一张 MySQL 物理表。
 */
@Service
public class ExcelImportService {

    public static final Pattern TABLE_NAME_PATTERN =
            Pattern.compile("[a-z][a-z0-9_]{0,47}");

    private static final Logger log = LoggerFactory.getLogger(ExcelImportService.class);
    private static final int INSERT_BATCH_SIZE = 500;

    private final MinioClient minioClient;
    private final MinioProperties minioProperties;
    private final KnowledgeDocumentVersionMapper versionMapper;
    private final TableMetaMapper tableMetaMapper;
    private final ExcelSplitter excelSplitter;
    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public ExcelImportService(MinioClient minioClient,
                              MinioProperties minioProperties,
                              KnowledgeDocumentVersionMapper versionMapper,
                              TableMetaMapper tableMetaMapper,
                              ExcelSplitter excelSplitter,
                              JdbcTemplate jdbcTemplate,
                              JsonMapper jsonMapper) {
        this.minioClient = minioClient;
        this.minioProperties = minioProperties;
        this.versionMapper = versionMapper;
        this.tableMetaMapper = tableMetaMapper;
        this.excelSplitter = excelSplitter;
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 将 Excel 的每个非空 Sheet 导入独立 MySQL 物理表。
     *
     * <p>导入前通过 CAS 抢占 importing 状态并预留表名；任一阶段失败时，
     * 按创建逆序删除物理表及本次 metadata，便于后续补偿重试。</p>
     *
     * @param version 已上传的 Excel 物理版本
     * @param targetTableName 目标基础表名；Excel 尚未接回上传入口，因此暂由调用方显式提供
     */
    public void importDocument(KnowledgeDocumentVersion version, String targetTableName) {
        String docId = version.getDocId();
        String versionId = version.getVersionId();
        if (versionMapper.compareAndSetProcessingStatus(
                versionId, DocumentStatus.IMPORTING, DocumentStatus.UPLOADED) != 1) {
            throw new DocumentStageAlreadyRunningException(
                    "Excel 导入阶段已经被其他线程抢占: " + versionId);
        }

        Path localFile = null;
        List<String> createdTables = new ArrayList<>();
        boolean ownsMetadata = false;
        try {
            String baseTableName = requireValidTableName(targetTableName);
            localFile = downloadToTemp(version);

            // 第一遍仅检查结构和数据边界，在创建任何物理表前尽早拒绝坏文件。
            List<ExcelSplitter.SheetDefinition> sheets = excelSplitter.inspect(localFile);
            if (sheets.isEmpty()) {
                throw new IllegalArgumentException("Excel 不包含可导入的 Sheet");
            }

            cleanupStaleAttempt(docId);
            List<ImportTable> importTables = buildImportTables(baseTableName, sheets);
            ownsMetadata = true;
            reserveTableNames(docId, importTables);

            // 第二遍流式读取数据行，以批量写入控制 JVM 内存和数据库往返次数。
            for (ImportTable importTable : importTables) {
                ensurePhysicalTableAbsent(importTable.tableName());
                createPhysicalTable(importTable);
                createdTables.add(importTable.tableName());

                long importedRows = insertSheetRows(localFile, importTable);
                if (importedRows != importTable.sheet().rowCount()) {
                    throw new IllegalStateException(
                            "Excel 两次读取的行数不一致: sheet="
                                    + importTable.sheet().sheetName()
                                    + ", inspect=" + importTable.sheet().rowCount()
                                    + ", import=" + importedRows);
                }
                if (tableMetaMapper.markImported(
                        docId, importTable.sheet().sheetIndex(), importedRows) != 1) {
                    throw new IllegalStateException(
                            "更新 Excel 表元数据失败: table=" + importTable.tableName());
                }
            }

            if (versionMapper.compareAndSetProcessingStatusAndClearError(
                    versionId, DocumentStatus.IMPORTED, DocumentStatus.IMPORTING) != 1) {
                throw new IllegalStateException("Excel 导入完成后更新版本状态失败: " + versionId);
            }
            log.info("Excel 导入完成: docId={}, versionId={}, tableCount={}, tables={}",
                    docId, versionId, createdTables.size(), createdTables);
        } catch (Exception e) {
            cleanupFailedImport(docId, createdTables, ownsMetadata);
            versionMapper.compareAndSetProcessingStatusWithError(
                    versionId,
                    DocumentStatus.UPLOADED,
                    DocumentStatus.IMPORTING,
                    truncateError("导入失败: " + e.getMessage()));
            throw new RuntimeException("Excel 导入失败: " + e.getMessage(), e);
        } finally {
            deleteTempFile(localFile);
        }
    }

    /**
     * 校验并规范化用户指定的 MySQL 基础表名，防止动态 DDL 注入。
     *
     * @return 可安全用于派生 Sheet 表名的标识符
     */
    public static String requireValidTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Excel 上传时 tableName 不能为空");
        }
        String normalized = tableName.strip();
        if (!TABLE_NAME_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "tableName 只能以小写字母开头，并包含小写字母、数字、下划线，最长 48 个字符");
        }
        return normalized;
    }

    private Path downloadToTemp(KnowledgeDocumentVersion version) throws Exception {
        String objectKey = resolveObjectKey(version);
        Path tempFile = Files.createTempFile(
                "excel-" + version.getVersionId() + "-", "." + version.getFileType());
        try (GetObjectResponse response = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(minioProperties.getBucketName())
                        .object(objectKey)
                        .build())) {
            Files.copy(response, tempFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Files.deleteIfExists(tempFile);
            throw e;
        }
        return tempFile;
    }

    private String resolveObjectKey(KnowledgeDocumentVersion version) {
        if (version.getRawObjectKey() != null && !version.getRawObjectKey().isBlank()) {
            return version.getRawObjectKey();
        }

        // 兼容迁移前未保存 raw_object_key 的历史文档。
        String docUrl = version.getDocUrl();
        if (docUrl == null || docUrl.isBlank()) {
            throw new IllegalArgumentException("Excel 文档缺少 MinIO 原文件地址");
        }
        String path = URI.create(docUrl).getPath();
        String marker = "/" + minioProperties.getBucketName() + "/";
        int markerIndex = path.indexOf(marker);
        if (markerIndex < 0) {
            throw new IllegalArgumentException("Excel 文档地址不包含 MinIO bucket: " + docUrl);
        }
        return path.substring(markerIndex + marker.length());
    }

    private List<ImportTable> buildImportTables(
            String baseTableName,
            List<ExcelSplitter.SheetDefinition> sheets) {
        List<ImportTable> result = new ArrayList<>(sheets.size());
        for (ExcelSplitter.SheetDefinition sheet : sheets) {
            String tableName = sheet.sheetIndex() == 1
                    ? baseTableName
                    : baseTableName + "_" + sheet.sheetIndex();
            if (tableName.length() > 64) {
                throw new IllegalArgumentException("Sheet 派生表名超过 MySQL 64 字符限制: " + tableName);
            }
            result.add(new ImportTable(tableName, sheet));
        }
        return result;
    }

    private void reserveTableNames(String docId, List<ImportTable> importTables) throws Exception {
        for (ImportTable importTable : importTables) {
            TableMeta meta = new TableMeta();
            meta.setDocId(docId);
            meta.setSheetIndex(importTable.sheet().sheetIndex());
            meta.setSheetName(importTable.sheet().sheetName());
            meta.setTableName(importTable.tableName());
            meta.setColumnMapping(columnMappingJson(importTable.sheet()));
            meta.setRowCount(0L);
            meta.setStatus(TableMetaStatus.CREATING.value());
            if (tableMetaMapper.insert(meta) != 1) {
                throw new IllegalStateException("占用 Excel 目标表名失败: " + importTable.tableName());
            }
        }
    }

    private String columnMappingJson(ExcelSplitter.SheetDefinition sheet) throws Exception {
        List<Map<String, Object>> mappings = new ArrayList<>(sheet.columns().size());
        for (ExcelSplitter.ColumnDefinition column : sheet.columns()) {
            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("columnIndex", column.columnIndex());
            mapping.put("originalName", column.originalName());
            mapping.put("columnName", column.columnName());
            mappings.add(mapping);
        }
        return jsonMapper.writeValueAsString(mappings);
    }

    private void cleanupStaleAttempt(String docId) {
        List<TableMeta> existing = tableMetaMapper.findByDocId(docId);
        if (existing.stream().anyMatch(meta -> TableMetaStatus.IMPORTED.matches(meta.getStatus()))) {
            throw new IllegalStateException("Excel 已存在完成的导入表，不允许覆盖: docId=" + docId);
        }
        for (TableMeta meta : existing) {
            dropTableQuietly(meta.getTableName());
        }
        if (!existing.isEmpty()) {
            tableMetaMapper.deleteByDocId(docId);
        }
    }

    private void ensurePhysicalTableAbsent(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class,
                tableName);
        if (count != null && count > 0) {
            throw new IllegalArgumentException("MySQL 目标表已存在: " + tableName);
        }
    }

    private void createPhysicalTable(ImportTable importTable) {
        String businessColumns = importTable.sheet().columns().stream()
                .map(column -> quoteIdentifier(column.columnName()) + " VARCHAR(255) NULL")
                .collect(Collectors.joining(", "));
        String sql = "CREATE TABLE " + quoteIdentifier(importTable.tableName()) + " ("
                + "`_row_id` BIGINT NOT NULL AUTO_INCREMENT, "
                + "`_excel_row_no` INT NOT NULL, "
                + businessColumns + ", "
                + "PRIMARY KEY (`_row_id`)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        jdbcTemplate.execute(sql);
    }

    private long insertSheetRows(Path localFile, ImportTable importTable) {
        List<ExcelSplitter.ColumnDefinition> columns = importTable.sheet().columns();
        String columnSql = columns.stream()
                .map(column -> quoteIdentifier(column.columnName()))
                .collect(Collectors.joining(", "));
        String placeholders = java.util.stream.IntStream
                .range(0, columns.size() + 1)
                .mapToObj(index -> "?")
                .collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + quoteIdentifier(importTable.tableName())
                + " (`_excel_row_no`, " + columnSql + ") VALUES (" + placeholders + ")";

        RowBatchWriter batchWriter = new RowBatchWriter(jdbcTemplate, sql);
        long rowCount = excelSplitter.readRows(
                localFile,
                importTable.sheet(),
                batchWriter::add);
        batchWriter.flush();
        return rowCount;
    }

    private void cleanupFailedImport(String docId,
                                     List<String> createdTables,
                                     boolean ownsMetadata) {
        for (int index = createdTables.size() - 1; index >= 0; index--) {
            dropTableQuietly(createdTables.get(index));
        }
        if (ownsMetadata) {
            try {
                tableMetaMapper.deleteByDocId(docId);
            } catch (Exception cleanupException) {
                log.warn("清理 table_meta 失败: docId={}, error={}",
                        docId, cleanupException.getMessage());
            }
        }
    }

    private void dropTableQuietly(String tableName) {
        if (tableName == null || !tableName.matches("[a-z][a-z0-9_]{0,63}")) {
            log.warn("拒绝清理非法动态表名: {}", tableName);
            return;
        }
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + quoteIdentifier(tableName));
        } catch (Exception cleanupException) {
            log.warn("清理 Excel 动态表失败: table={}, error={}",
                    tableName, cleanupException.getMessage());
        }
    }

    private void deleteTempFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.warn("删除 Excel 临时文件失败: path={}, error={}", path, e.getMessage());
        }
    }

    private static String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private static String truncateError(String message) {
        if (message == null) {
            return "未知错误";
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000) + "...";
    }

    private record ImportTable(String tableName,
                               ExcelSplitter.SheetDefinition sheet) {
    }

    private static final class RowBatchWriter {

        private final JdbcTemplate jdbcTemplate;
        private final String sql;
        private final List<Object[]> batch = new ArrayList<>(INSERT_BATCH_SIZE);

        private RowBatchWriter(JdbcTemplate jdbcTemplate, String sql) {
            this.jdbcTemplate = jdbcTemplate;
            this.sql = sql;
        }

        private void add(int excelRowNumber, List<String> values) {
            Object[] parameters = new Object[values.size() + 1];
            parameters[0] = excelRowNumber;
            for (int index = 0; index < values.size(); index++) {
                parameters[index + 1] = values.get(index);
            }
            batch.add(parameters);
            if (batch.size() >= INSERT_BATCH_SIZE) {
                flush();
            }
        }

        private void flush() {
            if (batch.isEmpty()) {
                return;
            }
            jdbcTemplate.batchUpdate(sql, batch);
            batch.clear();
        }
    }
}

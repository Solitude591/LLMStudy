package com.llmstudy.rag.service.splitter;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelReader;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.read.metadata.ReadSheet;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 使用 EasyExcel 流式提取 Excel 结构和数据行。
 *
 * <p>第一遍扫描 Sheet 结构并校验数据，第二遍逐行回调，
 * 供数据库导入层分批写入，不在 JVM 中保留整张表。</p>
 */
@Component
public class ExcelSplitter {

    public static final int MAX_CELL_LENGTH = 255;

    private static final int MAX_COLUMN_NAME_LENGTH = 48;

    private static final Set<String> SYSTEM_COLUMN_NAMES = Set.of(
            "_row_id", "_excel_row_no");

    /**
     * Excel/XML 不允许的控制字符。保留制表符和换行，避免破坏单元格原始语义。
     */
    private static final Pattern ILLEGAL_CONTROL_CHARACTERS =
            Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 第一遍流式扫描工作簿，提取非空 Sheet 的列结构并校验单元格长度。
     */
    public List<SheetDefinition> inspect(Path excelFile) {
        Objects.requireNonNull(excelFile, "Excel 文件路径不能为空");

        List<SheetDefinition> definitions = new ArrayList<>();
        try (ExcelReader reader = EasyExcel.read(excelFile.toFile()).build()) {
            int nonEmptyIndex = 0;
            for (ReadSheet sourceSheet : reader.excelExecutor().sheetList()) {
                SheetInspectionListener listener = new SheetInspectionListener(
                        sourceSheet.getSheetNo(), sourceSheet.getSheetName());
                ReadSheet readSheet = EasyExcel.readSheet(sourceSheet.getSheetNo())
                        .headRowNumber(1)
                        .registerReadListener(listener)
                        .build();
                reader.read(readSheet);

                SheetDefinition definition = listener.toDefinition(nonEmptyIndex + 1);
                if (definition != null) {
                    definitions.add(definition);
                    nonEmptyIndex++;
                }
            }
        }
        return List.copyOf(definitions);
    }

    /**
     * 第二遍逐行读取指定 Sheet，不在内存中保留整张表。
     *
     * @return 实际回调的数据行数
     */
    public long readRows(Path excelFile,
                         SheetDefinition sheet,
                         RowConsumer consumer) {
        Objects.requireNonNull(excelFile, "Excel 文件路径不能为空");
        Objects.requireNonNull(sheet, "Sheet 定义不能为空");
        Objects.requireNonNull(consumer, "Excel 行消费器不能为空");

        RowStreamingListener listener = new RowStreamingListener(sheet, consumer);
        EasyExcel.read(excelFile.toFile(), listener)
                .headRowNumber(1)
                .sheet(sheet.sheetNo())
                .doRead();
        return listener.rowCount();
    }

    private static String cellValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return cleanCell(text);
        }
        if (value instanceof BigDecimal decimal) {
            return cleanCell(decimal.stripTrailingZeros().toPlainString());
        }
        if (value instanceof Double number) {
            return cleanCell(formatFloatingPoint(number));
        }
        if (value instanceof Float number) {
            return cleanCell(formatFloatingPoint(number.doubleValue()));
        }
        if (value instanceof Number number) {
            return cleanCell(number.toString());
        }
        if (value instanceof LocalDateTime dateTime) {
            return cleanCell(dateTime.format(DATE_TIME_FORMATTER));
        }
        if (value instanceof LocalDate date) {
            return cleanCell(date.toString());
        }
        if (value instanceof Date date) {
            return cleanCell(date.toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime()
                    .format(DATE_TIME_FORMATTER));
        }
        return cleanCell(value.toString());
    }

    private static String cleanCell(String value) {
        if (value == null) {
            return "";
        }
        return ILLEGAL_CONTROL_CHARACTERS.matcher(value)
                .replaceAll("")
                .strip();
    }

    private static String formatFloatingPoint(double value) {
        if (!Double.isFinite(value)) {
            return String.valueOf(value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static List<ColumnDefinition> buildColumns(Map<Integer, String> headers,
                                                        int columnCount) {
        List<ColumnDefinition> columns = new ArrayList<>(columnCount);
        Set<String> usedNames = new HashSet<>(SYSTEM_COLUMN_NAMES);

        for (int index = 0; index < columnCount; index++) {
            String originalName = cleanCell(headers.get(index));
            String baseName = normalizeColumnName(originalName, index);
            String columnName = uniqueColumnName(baseName, usedNames);
            columns.add(new ColumnDefinition(index, originalName, columnName));
        }
        return List.copyOf(columns);
    }

    private static String normalizeColumnName(String originalName, int columnIndex) {
        String normalized = originalName == null
                ? ""
                : originalName.strip().replaceAll("[^\\p{L}\\p{N}_]+", "_");
        normalized = normalized.replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");

        if (normalized.isBlank()) {
            normalized = "column_" + (columnIndex + 1);
        } else if (Character.isDigit(normalized.codePointAt(0))) {
            normalized = "column_" + normalized;
        }
        return truncateCodePoints(normalized, MAX_COLUMN_NAME_LENGTH);
    }

    private static String uniqueColumnName(String baseName, Set<String> usedNames) {
        String candidate = baseName;
        int suffix = 2;
        while (!usedNames.add(candidate.toLowerCase(Locale.ROOT))) {
            String suffixText = "_" + suffix++;
            candidate = truncateCodePoints(
                    baseName, MAX_COLUMN_NAME_LENGTH - suffixText.length()) + suffixText;
        }
        return candidate;
    }

    private static String truncateCodePoints(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private static void validateCellLength(String value,
                                           String sheetName,
                                           int excelRowNumber,
                                           int excelColumnNumber) {
        if (value.length() > MAX_CELL_LENGTH) {
            throw new IllegalArgumentException(
                    "Excel 单元格超过 " + MAX_CELL_LENGTH + " 个字符: sheet="
                            + sheetName + ", row=" + excelRowNumber
                            + ", column=" + excelColumnNumber);
        }
    }

    public record ColumnDefinition(int columnIndex,
                                   String originalName,
                                   String columnName) {
    }

    public record SheetDefinition(int sheetNo,
                                  int sheetIndex,
                                  String sheetName,
                                  List<ColumnDefinition> columns,
                                  long rowCount) {
        public SheetDefinition {
            columns = List.copyOf(columns);
        }
    }

    @FunctionalInterface
    public interface RowConsumer {
        void accept(int excelRowNumber, List<String> values);
    }

    private static final class SheetInspectionListener
            extends AnalysisEventListener<Map<Integer, Object>> {

        private final int sheetNo;
        private final String sheetName;
        private final Map<Integer, String> headers = new LinkedHashMap<>();
        private int columnCount;
        private long rowCount;

        private SheetInspectionListener(int sheetNo, String sheetName) {
            this.sheetNo = sheetNo;
            this.sheetName = sheetName;
        }

        @Override
        public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
            if (headMap == null) {
                return;
            }
            for (Map.Entry<Integer, String> entry : headMap.entrySet()) {
                String header = cleanCell(entry.getValue());
                validateCellLength(header, sheetName, 1, entry.getKey() + 1);
                headers.put(entry.getKey(), header);
            }
            columnCount = Math.max(columnCount, highestColumn(headMap));
        }

        @Override
        public void invoke(Map<Integer, Object> row, AnalysisContext context) {
            int excelRowNumber = context.readRowHolder().getRowIndex() + 1;
            rowCount++;
            columnCount = Math.max(columnCount, highestColumn(row));
            for (Map.Entry<Integer, Object> entry : row.entrySet()) {
                String value = cellValue(entry.getValue());
                validateCellLength(value, sheetName, excelRowNumber, entry.getKey() + 1);
            }
        }

        private SheetDefinition toDefinition(int sheetIndex) {
            boolean hasHeader = headers.values().stream().anyMatch(value -> !value.isBlank());
            if (!hasHeader && rowCount == 0) {
                return null;
            }
            if (columnCount == 0) {
                throw new IllegalArgumentException("Excel Sheet 没有可导入的列: " + sheetName);
            }
            return new SheetDefinition(
                    sheetNo, sheetIndex, sheetName,
                    buildColumns(headers, columnCount), rowCount);
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            // 结果由 toDefinition 统一生成。
        }
    }

    private static final class RowStreamingListener
            extends AnalysisEventListener<Map<Integer, Object>> {

        private final SheetDefinition sheet;
        private final RowConsumer consumer;
        private long rowCount;

        private RowStreamingListener(SheetDefinition sheet, RowConsumer consumer) {
            this.sheet = sheet;
            this.consumer = consumer;
        }

        @Override
        public void invoke(Map<Integer, Object> row, AnalysisContext context) {
            int excelRowNumber = context.readRowHolder().getRowIndex() + 1;
            List<String> values = new ArrayList<>(sheet.columns().size());
            for (ColumnDefinition column : sheet.columns()) {
                String value = cellValue(row.get(column.columnIndex()));
                validateCellLength(value, sheet.sheetName(), excelRowNumber,
                        column.columnIndex() + 1);
                values.add(value.isBlank() ? null : value);
            }
            consumer.accept(excelRowNumber, values);
            rowCount++;
        }

        private long rowCount() {
            return rowCount;
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            // 数据已逐行交给消费器。
        }
    }

    private static int highestColumn(Map<Integer, ?> row) {
        int maxColumn = -1;
        for (Integer column : row.keySet()) {
            if (column != null && column > maxColumn) {
                maxColumn = column;
            }
        }
        return maxColumn + 1;
    }

}

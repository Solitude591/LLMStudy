package com.llmstudy.rag.service.splitter;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcelSplitterTest {

    @TempDir
    Path tempDir;

    @Test
    void inspectAndReadRows_解析多Sheet并保留结构() throws Exception {
        Path workbook = tempDir.resolve("employees.xlsx");
        Files.write(workbook, buildWorkbook());

        ExcelSplitter splitter = new ExcelSplitter();
        List<ExcelSplitter.SheetDefinition> sheets = splitter.inspect(workbook);

        assertEquals(2, sheets.size());
        assertEquals("员工表", sheets.get(0).sheetName());
        assertEquals(List.of("姓名", "年龄", "姓名_2"),
                sheets.get(0).columns().stream()
                        .map(ExcelSplitter.ColumnDefinition::columnName)
                        .toList());
        assertEquals(2, sheets.get(0).rowCount());

        List<List<String>> rows = new ArrayList<>();
        List<Integer> rowNumbers = new ArrayList<>();
        long rowCount = splitter.readRows(workbook, sheets.get(0), (rowNumber, values) -> {
            rowNumbers.add(rowNumber);
            rows.add(new ArrayList<>(values));
        });

        assertEquals(2, rowCount);
        assertEquals(List.of(2, 3), rowNumbers);
        assertEquals(List.of("张三", "30", "杭州"), rows.get(0));
        assertEquals(List.of("李四", "31", "上海"), rows.get(1));
    }

    @Test
    void inspect_单元格超过255字符时拒绝导入() throws Exception {
        Path workbook = tempDir.resolve("too-long.xlsx");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        EasyExcel.write(out)
                .head(List.of(List.of("备注")))
                .sheet("数据")
                .doWrite(List.of(List.of("x".repeat(256))));
        Files.write(workbook, out.toByteArray());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new ExcelSplitter().inspect(workbook));

        assertTrue(error.getMessage().contains("超过 255 个字符"));
    }

    private byte[] buildWorkbook() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = EasyExcel.write(out).build()) {
            WriteSheet employees = EasyExcel.writerSheet("员工表")
                    .head(List.of(List.of("姓名"), List.of("年龄"), List.of("姓名")))
                    .build();
            writer.write(List.of(
                    List.of("张三", 30, "杭州"),
                    List.of("李四", 31, "上海")), employees);

            WriteSheet products = EasyExcel.writerSheet("商品表")
                    .head(List.of(List.of("商品"), List.of("价格")))
                    .build();
            writer.write(List.of(List.of("手机", 1999.5)), products);
        }
        return out.toByteArray();
    }
}

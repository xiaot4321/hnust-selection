package cn.hnust.selection.utils;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 名单解析功能测试：覆盖常见 Excel/CSV 内容、错误文件和导入行数边界。 */
class PersonnelImportFileParserTest {

    @Test
    void parsesUtf8BomEscapedQuotesCommaAndNewlineInCsv() throws Exception {
        String csv = "\uFEFF学号,姓名,备注\r\n"
            + "S001,\"张\"\"三\" ,\"课程, 备注\"\r\n"
            + "S002,李四,\"第一行\n第二行\"";

        List<List<String>> rows = PersonnelImportFileParser.parse(csv.getBytes(StandardCharsets.UTF_8), "roster.csv");

        assertEquals(3, rows.size());
        assertEquals("学号", rows.get(0).get(0));
        assertEquals("张\"三", rows.get(1).get(1));
        assertEquals("课程, 备注", rows.get(1).get(2));
        assertEquals("第一行\n第二行", rows.get(2).get(2));
    }

    @Test
    void rejectsUnclosedCsvQuote() {
        String csv = "学号,姓名\nS001,\"姓名未闭合";
        assertThrows(IOException.class, () -> PersonnelImportFileParser.parse(
            csv.getBytes(StandardCharsets.UTF_8), "roster.csv"));
    }

    @Test
    void rejectsMoreThanTwoThousandDataRows() {
        StringBuilder csv = new StringBuilder("学号,姓名\n");
        for (int row = 0; row < 2001; row++) csv.append("S").append(row).append(",姓名\n");
        assertThrows(IOException.class, () -> PersonnelImportFileParser.parse(
            csv.toString().getBytes(StandardCharsets.UTF_8), "roster.csv"));
    }

    @Test
    void xlsxParserDoesNotIgnoreColumnsThatAppearAfterTheHeader() throws Exception {
        byte[] xlsx;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("人员名单");
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("工号");
            header.createCell(1).setCellValue("姓名");
            org.apache.poi.ss.usermodel.Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("T001");
            data.createCell(1).setCellValue("教师");
            data.createCell(2).setCellValue("未定义列");
            workbook.write(output);
            xlsx = output.toByteArray();
        }

        List<List<String>> rows = PersonnelImportFileParser.parse(xlsx, "roster.xlsx");
        assertEquals(3, rows.get(1).size());
        assertEquals("未定义列", rows.get(1).get(2));
    }
}

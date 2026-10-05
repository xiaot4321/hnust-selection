package cn.hnust.selection.utils;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 固定模板名单解析器：接受 CSV 与 XLSX，统一转换成按表格行排列的字符串单元格。
 *
 * <p>解析器不创建任何账号，也不决定行的业务有效性；它只识别文件结构。Service 再按模板列顺序
 * 映射成学生或导师命令，并在独立行事务中执行账号与档案创建。</p>
 */
public final class PersonnelImportFileParser {
    /** 模板包含一行表头，因此最多容纳 2000 条业务行。 */
    private static final int MAX_FILE_ROWS = 2001;

    private PersonnelImportFileParser() { }

    public static List<List<String>> parse(byte[] bytes, String filename) throws IOException {
        if (filename == null) throw new IOException("文件名缺失");
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".csv")) return parseCsv(bytes);
        if (lower.endsWith(".xlsx")) return parseXlsx(bytes);
        throw new IOException("仅接受 .csv 或 .xlsx 文件");
    }

    /** DataFormatter 保留学号、工号等文本格式，避免把单元格里的值错误地转成浮点数。 */
    private static List<List<String>> parseXlsx(byte[] bytes) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) throw new IOException("工作簿中没有工作表");
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);
            List<List<String>> rows = new ArrayList<List<String>>();
            if (sheet.getLastRowNum() + 1 > MAX_FILE_ROWS) {
                throw new IOException("名单文件最多包含 2000 条数据行");
            }
            // 以整张表的最大列数检查多出的字段，不能只看第一行，避免后续某行的额外字段被静默丢弃。
            int lastColumn = 0;
            for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row != null) lastColumn = Math.max(lastColumn, row.getLastCellNum());
            }
            for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                List<String> cells = new ArrayList<String>();
                for (int column = 0; column < lastColumn; column++) {
                    Cell cell = row == null ? null : row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    cells.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
                }
                rows.add(cells);
            }
            return rows;
        } catch (RuntimeException exception) {
            throw new IOException("XLSX 文件无法读取，请使用系统模板并确认文件未损坏", exception);
        }
    }

    /**
     * RFC 4180 风格 CSV 解析：支持双引号转义、逗号和单元格内换行；首个 UTF-8 BOM 会被移除。
     * 不使用按逗号 split 的简化实现，避免姓名说明中包含逗号时整行错位。
     */
    private static List<List<String>> parseCsv(byte[] bytes) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        List<List<String>> rows = new ArrayList<List<String>>();
        List<String> row = new ArrayList<String>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (quoted) {
                if (current == '"') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                        cell.append('"'); index++;
                    } else quoted = false;
                } else cell.append(current);
                continue;
            }
            if (current == '"' && cell.length() == 0) quoted = true;
            else if (current == ',') { row.add(cell.toString().trim()); cell.setLength(0); }
            else if (current == '\n' || current == '\r') {
                if (current == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') index++;
                row.add(cell.toString().trim()); cell.setLength(0);
                rows.add(row); row = new ArrayList<String>();
                if (rows.size() > MAX_FILE_ROWS) {
                    throw new IOException("名单文件最多包含 2000 条数据行");
                }
            } else cell.append(current);
        }
        if (quoted) throw new IOException("CSV 文件包含未闭合的双引号");
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString().trim()); rows.add(row);
        }
        if (rows.size() > MAX_FILE_ROWS) throw new IOException("名单文件最多包含 2000 条数据行");
        if (rows.isEmpty()) throw new IOException("名单文件为空");
        return rows;
    }
}

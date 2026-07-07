/**
 * @Author : Cui
 * @Date: 2026/07/07 19:28
 * @Description DataSmart Govern Backend - SyncTaskDefinitionExchangeCodecSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 同步任务定义导入/导出文件编解码组件。
 *
 * <p>该组件只处理“文件格式”和“列映射”，不做权限、不做模板预检、不插入任务。
 * 这样拆分的好处是：</p>
 * <p>1. CSV/XLSX 格式细节不会污染任务导入业务逻辑；</p>
 * <p>2. 后续如果要支持 JSON、Parquet 或 MinIO 批量导入，只需要新增 codec，不必改任务状态机；</p>
 * <p>3. Agent 工具可以复用相同列契约生成文件或解释导入模板。</p>
 *
 * <p>导入导出的字段只包含低敏任务定义字段和模板引用，不包含连接串、密码、完整 SQL、样本数据或执行器内部计划。</p>
 */
@Component
public class SyncTaskDefinitionExchangeCodecSupport {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 任务定义交换表头。
     *
     * <p>其中 taskId/currentState/approvalState/triggerType 是导出时给人看的上下文字段，导入时会被忽略。
     * 新建任务的真实状态由导入选项决定：不立即执行则 DRAFT，立即执行则先发布再创建 MANUAL execution。</p>
     */
    private static final List<String> HEADERS = List.of(
            "taskId",
            "tenantId",
            "projectId",
            "workspaceId",
            "templateId",
            "name",
            "description",
            "priority",
            "ownerId",
            "groupCode",
            "groupName",
            "scheduleConfig",
            "runMode",
            "currentState",
            "approvalState",
            "triggerType",
            "createTime",
            "updateTime"
    );

    /**
     * 解析导出格式。
     *
     * @param requestedFormat 请求参数中的格式，可为空
     * @param fileName 文件名，可用于导入时推断格式
     * @return CSV 或 XLSX
     */
    public String resolveFormat(String requestedFormat, String fileName) {
        String normalized = requestedFormat == null ? null : requestedFormat.trim().toUpperCase(Locale.ROOT);
        if (normalized == null || normalized.isBlank() || "AUTO".equals(normalized)) {
            String lowerFileName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
            if (lowerFileName.endsWith(".xlsx") || lowerFileName.endsWith(".xlsm") || lowerFileName.endsWith(".xls")) {
                return "XLSX";
            }
            return "CSV";
        }
        if ("EXCEL".equals(normalized) || "XLSX".equals(normalized)) {
            return "XLSX";
        }
        if ("CSV".equals(normalized)) {
            return "CSV";
        }
        throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                "不支持的同步任务导入导出格式: " + requestedFormat + "，仅支持 CSV 或 XLSX");
    }

    public String contentType(String format) {
        return "XLSX".equals(format)
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : "text/csv;charset=UTF-8";
    }

    public String fileExtension(String format) {
        return "XLSX".equals(format) ? "xlsx" : "csv";
    }

    /**
     * 将任务列表编码为导出文件。
     */
    public byte[] encodeTasks(List<SyncTask> tasks, String format) {
        return "XLSX".equals(format) ? encodeExcel(tasks) : encodeCsv(tasks);
    }

    /**
     * 解析导入文件。
     */
    public List<TaskDefinitionImportRow> decodeRows(byte[] content, String format) {
        if (content == null || content.length == 0) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "导入文件不能为空");
        }
        return "XLSX".equals(format) ? decodeExcel(content) : decodeCsv(content);
    }

    private byte[] encodeCsv(List<SyncTask> tasks) {
        StringBuilder builder = new StringBuilder();
        builder.append('\uFEFF');
        appendCsvLine(builder, HEADERS);
        for (SyncTask task : safeTasks(tasks)) {
            appendCsvLine(builder, taskToCells(task));
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] encodeExcel(List<SyncTask> tasks) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("sync_tasks");
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(HEADERS.get(i));
                cell.setCellStyle(headerStyle);
            }
            int rowIndex = 1;
            for (SyncTask task : safeTasks(tasks)) {
                Row row = sheet.createRow(rowIndex++);
                List<String> cells = taskToCells(task);
                for (int i = 0; i < cells.size(); i++) {
                    row.createCell(i).setCellValue(cells.get(i));
                }
            }
            for (int i = 0; i < HEADERS.size(); i++) {
                sheet.autoSizeColumn(i);
            }
            workbook.write(outputStream);
            return outputStream.toByteArray();
        } catch (IOException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.INTERNAL_ERROR,
                    "生成同步任务 Excel 导出文件失败: " + exception.getMessage());
        }
    }

    private List<TaskDefinitionImportRow> decodeCsv(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        List<List<String>> rawRows = parseCsv(text);
        if (rawRows.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> headerIndex = headerIndex(rawRows.get(0));
        List<TaskDefinitionImportRow> rows = new ArrayList<>();
        for (int i = 1; i < rawRows.size(); i++) {
            List<String> rawRow = rawRows.get(i);
            if (isBlankRow(rawRow)) {
                continue;
            }
            rows.add(toImportRow(i + 1, headerIndex, rawRow));
        }
        return rows;
    }

    private List<TaskDefinitionImportRow> decodeExcel(byte[] content) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null || sheet.getPhysicalNumberOfRows() == 0) {
                return List.of();
            }
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            Map<String, Integer> headerIndex = headerIndex(readExcelCells(headerRow, formatter));
            List<TaskDefinitionImportRow> rows = new ArrayList<>();
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                List<String> rawRow = readExcelCells(row, formatter);
                if (isBlankRow(rawRow)) {
                    continue;
                }
                rows.add(toImportRow(rowIndex + 1, headerIndex, rawRow));
            }
            return rows;
        } catch (IOException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "解析同步任务 Excel 导入文件失败，请确认文件为 xlsx 格式: " + exception.getMessage());
        }
    }

    private List<String> readExcelCells(Row row, DataFormatter formatter) {
        List<String> values = new ArrayList<>();
        if (row == null) {
            return values;
        }
        for (int i = 0; i < HEADERS.size(); i++) {
            Cell cell = row.getCell(i);
            values.add(cell == null ? null : trimToNull(formatter.formatCellValue(cell)));
        }
        return values;
    }

    private Map<String, Integer> headerIndex(List<String> headerCells) {
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < headerCells.size(); i++) {
            String header = normalizeHeader(headerCells.get(i));
            if (header != null) {
                index.put(header, i);
            }
        }
        if (!index.containsKey("templateid") || !index.containsKey("name")) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "同步任务导入文件必须包含 templateId 和 name 两列");
        }
        return index;
    }

    private TaskDefinitionImportRow toImportRow(int rowNumber, Map<String, Integer> headerIndex, List<String> rawRow) {
        Map<String, String> values = new HashMap<>();
        for (String header : HEADERS) {
            values.put(header, readCell(rawRow, headerIndex.get(normalizeHeader(header))));
        }
        return new TaskDefinitionImportRow(
                rowNumber,
                parseLong(values.get("tenantId"), "tenantId", rowNumber, false),
                parseLong(values.get("projectId"), "projectId", rowNumber, false),
                parseLong(values.get("workspaceId"), "workspaceId", rowNumber, false),
                parseLong(values.get("templateId"), "templateId", rowNumber, true),
                values.get("name"),
                values.get("description"),
                values.get("priority"),
                parseLong(values.get("ownerId"), "ownerId", rowNumber, false),
                values.get("groupCode"),
                values.get("groupName"),
                values.get("scheduleConfig"),
                values.get("runMode")
        );
    }

    private Long parseLong(String value, String fieldName, int rowNumber, boolean required) {
        String text = trimToNull(value);
        if (text == null) {
            if (required) {
                throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                        "第 " + rowNumber + " 行缺少必填字段 " + fieldName);
            }
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "第 " + rowNumber + " 行字段 " + fieldName + " 必须是整数，当前值=" + text);
        }
    }

    private List<String> taskToCells(SyncTask task) {
        return List.of(
                text(task.getId()),
                text(task.getTenantId()),
                text(task.getProjectId()),
                text(task.getWorkspaceId()),
                text(task.getTemplateId()),
                text(task.getName()),
                text(task.getDescription()),
                text(task.getPriority()),
                text(task.getOwnerId()),
                text(task.getGroupCode()),
                text(task.getGroupName()),
                text(task.getScheduleConfig()),
                text(task.getRunMode()),
                text(task.getCurrentState()),
                text(task.getApprovalState()),
                text(task.getTriggerType()),
                task.getCreateTime() == null ? "" : TIME_FORMATTER.format(task.getCreateTime()),
                task.getUpdateTime() == null ? "" : TIME_FORMATTER.format(task.getUpdateTime())
        );
    }

    private void appendCsvLine(StringBuilder builder, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(escapeCsv(cells.get(i)));
        }
        builder.append('\n');
    }

    private String escapeCsv(String value) {
        String text = value == null ? "" : value;
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    /**
     * 解析 CSV 文本。
     *
     * <p>这里实现的是最小 RFC4180 兼容解析：支持双引号包裹字段、双引号转义、字段内换行。
     * 之所以没有把 CSV 解析塞进业务 support，是因为 CSV 的状态机和任务状态机是两件完全不同的事。</p>
     */
    private List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> currentRow = new ArrayList<>();
        StringBuilder currentCell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        currentCell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    currentCell.append(ch);
                }
                continue;
            }
            if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                currentRow.add(trimToNull(currentCell.toString()));
                currentCell.setLength(0);
            } else if (ch == '\n') {
                currentRow.add(trimToNull(removeTrailingCarriageReturn(currentCell.toString())));
                rows.add(currentRow);
                currentRow = new ArrayList<>();
                currentCell.setLength(0);
            } else {
                currentCell.append(ch);
            }
        }
        if (inQuotes) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "CSV 文件存在未闭合的双引号，请检查导入文件");
        }
        if (currentCell.length() > 0 || !currentRow.isEmpty()) {
            currentRow.add(trimToNull(removeTrailingCarriageReturn(currentCell.toString())));
            rows.add(currentRow);
        }
        return rows;
    }

    private String readCell(List<String> rawRow, Integer index) {
        if (index == null || index < 0 || index >= rawRow.size()) {
            return null;
        }
        return trimToNull(rawRow.get(index));
    }

    private boolean isBlankRow(List<String> row) {
        if (row == null || row.isEmpty()) {
            return true;
        }
        for (String cell : row) {
            if (cell != null && !cell.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String normalizeHeader(String header) {
        return header == null || header.isBlank() ? null : header.trim().toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String removeTrailingCarriageReturn(String value) {
        if (value != null && value.endsWith("\r")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<SyncTask> safeTasks(List<SyncTask> tasks) {
        return tasks == null ? List.of() : tasks;
    }

    /**
     * 从导入文件解析出的单行任务定义。
     *
     * @param rowNumber 文件行号
     * @param tenantId 文件声明的租户 ID，可为空；服务层会与模板租户和操作者范围再次校验
     * @param projectId 文件声明的项目 ID，可为空；为空时继承模板项目
     * @param workspaceId 文件声明的工作空间 ID，可为空；为空时继承模板工作空间
     * @param templateId 必填，同步任务必须基于已有模板创建
     * @param name 必填，导入唯一键的一部分
     * @param description 任务说明
     * @param priority 任务优先级
     * @param ownerId 负责人 ID，可为空
     * @param groupCode 分组编码，可为空
     * @param groupName 分组展示名，可为空
     * @param scheduleConfig 调度配置，可为空
     * @param runMode 运行模式，可为空
     */
    public record TaskDefinitionImportRow(Integer rowNumber,
                                          Long tenantId,
                                          Long projectId,
                                          Long workspaceId,
                                          Long templateId,
                                          String name,
                                          String description,
                                          String priority,
                                          Long ownerId,
                                          String groupCode,
                                          String groupName,
                                          String scheduleConfig,
                                          String runMode) {
    }
}

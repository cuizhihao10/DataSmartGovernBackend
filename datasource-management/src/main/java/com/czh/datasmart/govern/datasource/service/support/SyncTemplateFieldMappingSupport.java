/**
 * @Author : Cui
 * @Date: 2026/05/05 23:25
 * @Description DataSmart Govern Backend - SyncTemplateFieldMappingSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.datasource.entity.ColumnMetadataSummary;
import com.czh.datasmart.govern.datasource.entity.TableMetadataSummary;
import com.czh.datasmart.govern.datasource.entity.SyncTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 同步模板字段映射校验支持组件。
 *
 * <p>字段映射是同步模板中最容易持续膨胀的逻辑：它既要解析用户配置的 JSON，
 * 又要判断源字段和目标字段是否存在，还要识别类型不兼容、长度截断、必填字段漏映射等执行前风险。
 * 如果这些规则继续堆在 `SyncTemplateServiceImpl` 中，主服务会被细粒度结构校验淹没，
 * 后续新增 PostgreSQL、Kafka、MongoDB、文件、API 等连接器时也会更难扩展。</p>
 *
 * <p>这里把字段映射作为独立能力线，是为了给未来演进预留接口边界：
 * 当前支持关系型表字段映射，后续可以扩展为 schema registry 映射、嵌套 JSON 路径映射、
 * 字段级脱敏映射、类型转换表达式、默认值表达式、异常字段旁路和 AI 辅助映射建议。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncTemplateFieldMappingSupport {

    /**
     * JSON 解析器。
     *
     * <p>当前模板字段映射允许两种结构：直接数组，或 `{ "mappings": [...] }`。
     * 使用 Jackson 是为了容忍后续在同一个 JSON 中继续扩展 transform、defaultValue、required 等字段。</p>
     */
    private final ObjectMapper objectMapper;

    /**
     * 校验字段映射配置并返回摘要。
     *
     * @param template 当前同步模板，提供 fieldMappingConfig 和写入配置上下文。
     * @param sourceTable 源端元数据摘要，提供源字段存在性和类型信息。
     * @param targetTable 目标端元数据摘要，提供目标字段、必填字段、长度和精度约束。
     * @param errors 阻断性错误集合。写入这里的问题会让模板校验不通过。
     * @param warnings 非阻断性风险集合。写入这里的问题提示用户优化，但不直接阻断保存。
     * @return 面向 API 返回的字段映射校验摘要，供前端展示、运维排查和学习理解。
     */
    public Map<String, Object> validateFieldMappings(SyncTemplate template,
                                                     TableMetadataSummary sourceTable,
                                                     TableMetadataSummary targetTable,
                                                     List<String> errors,
                                                     List<String> warnings) {
        ParsedFieldMappings parsed = parseFieldMappings(template.getFieldMappingConfig(), errors, true);
        if (!parsed.valid()) {
            return defaultMappingSummary();
        }

        Map<String, ColumnMetadataSummary> sourceColumns = indexColumnsByName(sourceTable);
        Map<String, ColumnMetadataSummary> targetColumns = indexColumnsByName(targetTable);
        List<String> missingSourceFields = new ArrayList<>();
        List<String> missingTargetFields = new ArrayList<>();
        List<String> typeMismatchMappings = new ArrayList<>();
        List<String> truncationRiskMappings = new ArrayList<>();
        Set<String> mappedTargetFields = parsed.mappings().stream()
                .map(FieldMappingPair::targetField)
                .filter(field -> !isBlank(field))
                .map(this::normalize)
                .collect(Collectors.toSet());

        for (FieldMappingPair mapping : parsed.mappings()) {
            ColumnMetadataSummary sourceColumn = sourceColumns.get(normalize(mapping.sourceField()));
            ColumnMetadataSummary targetColumn = targetColumns.get(normalize(mapping.targetField()));
            if (sourceColumn == null) {
                missingSourceFields.add(mapping.sourceField());
                continue;
            }
            if (targetColumn == null) {
                missingTargetFields.add(mapping.targetField());
                continue;
            }
            if (!isCompatibleType(sourceColumn.getDataTypeName(), targetColumn.getDataTypeName())) {
                typeMismatchMappings.add(mapping.sourceField() + " -> " + mapping.targetField()
                        + "（源类型=" + sourceColumn.getDataTypeName()
                        + "，目标类型=" + targetColumn.getDataTypeName() + "）");
            }
            String truncationRisk = buildTruncationRisk(sourceColumn, targetColumn, mapping);
            if (truncationRisk != null) {
                truncationRiskMappings.add(truncationRisk);
            }
        }

        List<String> unmappedRequiredTargetFields = findUnmappedRequiredTargetFields(targetTable, mappedTargetFields);
        if (!missingSourceFields.isEmpty()) {
            errors.add("字段映射中存在源端不存在的字段: " + missingSourceFields);
        }
        if (!missingTargetFields.isEmpty()) {
            errors.add("字段映射中存在目标端不存在的字段: " + missingTargetFields);
        }
        if (!typeMismatchMappings.isEmpty()) {
            warnings.add("存在字段类型大类不一致的映射，执行前建议补充转换规则: " + typeMismatchMappings);
        }
        if (!truncationRiskMappings.isEmpty()) {
            warnings.add("存在字段长度或精度截断风险: " + truncationRiskMappings);
        }
        if (!unmappedRequiredTargetFields.isEmpty()) {
            errors.add("目标端存在未映射且无默认值的必填字段: " + unmappedRequiredTargetFields);
        }

        Map<String, Object> summary = defaultMappingSummary();
        summary.put("mappingCount", parsed.mappings().size());
        summary.put("validMappingCount", parsed.mappings().size() - missingSourceFields.size() - missingTargetFields.size());
        summary.put("missingSourceFields", missingSourceFields);
        summary.put("missingTargetFields", missingTargetFields);
        summary.put("typeMismatchMappings", typeMismatchMappings);
        summary.put("truncationRiskMappings", truncationRiskMappings);
        summary.put("unmappedRequiredTargetFields", unmappedRequiredTargetFields);
        summary.put("autoMappingSuggestions", buildAutoMappingSuggestions(sourceTable, targetTable, mappedTargetFields));
        return summary;
    }

    /**
     * 根据源字段推导目标字段。
     *
     * <p>UPSERT、INSERT_IGNORE、REPLACE 等策略需要找到目标端冲突键。
     * 如果用户配置了 `sourceField -> targetField`，主键字段可能在源端和目标端不同名，因此需要这里统一解析。</p>
     */
    public String mapSourceFieldToTargetField(String fieldMappingConfig, String sourceField) {
        ParsedFieldMappings parsed = parseFieldMappings(fieldMappingConfig, null, false);
        if (!parsed.valid()) {
            return null;
        }
        return parsed.mappings().stream()
                .filter(mapping -> !isBlank(mapping.sourceField()) && normalize(mapping.sourceField()).equals(normalize(sourceField)))
                .map(FieldMappingPair::targetField)
                .filter(targetField -> !isBlank(targetField))
                .findFirst()
                .orElse(null);
    }

    /**
     * 按字段名建立索引。
     *
     * <p>字段校验会多次按名称查找字段，提前索引可以避免反复遍历字段列表，也让字段名大小写差异集中处理。</p>
     */
    public Map<String, ColumnMetadataSummary> indexColumnsByName(TableMetadataSummary table) {
        return table.getColumns() == null ? Map.of() : table.getColumns().stream()
                .filter(column -> column != null && !isBlank(column.getColumnName()))
                .collect(Collectors.toMap(
                        column -> normalize(column.getColumnName()),
                        column -> column,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    /**
     * 提取标准化字段名集合，供主键、增量字段和冲突键校验复用。
     */
    public Set<String> extractColumnNames(TableMetadataSummary table) {
        return table.getColumns() == null ? Set.of() : table.getColumns().stream()
                .map(ColumnMetadataSummary::getColumnName)
                .filter(columnName -> !isBlank(columnName))
                .map(this::normalize)
                .collect(Collectors.toSet());
    }

    /**
     * 构造空映射摘要。
     *
     * <p>即使源表或目标表不存在，也返回稳定结构，前端不需要为失败场景写额外字段判空逻辑。</p>
     */
    public Map<String, Object> defaultMappingSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("mappingCount", 0);
        summary.put("validMappingCount", 0);
        summary.put("missingSourceFields", List.of());
        summary.put("missingTargetFields", List.of());
        summary.put("typeMismatchMappings", List.of());
        summary.put("truncationRiskMappings", List.of());
        summary.put("unmappedRequiredTargetFields", List.of());
        summary.put("autoMappingSuggestions", List.of());
        return summary;
    }

    public String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ParsedFieldMappings parseFieldMappings(String fieldMappingConfig, List<String> errors, boolean reportErrors) {
        if (isBlank(fieldMappingConfig)) {
            return new ParsedFieldMappings(true, List.of());
        }
        try {
            JsonNode rootNode = objectMapper.readTree(fieldMappingConfig);
            JsonNode mappingsNode = rootNode.isArray() ? rootNode : rootNode.path("mappings");
            if (!mappingsNode.isArray()) {
                if (reportErrors && errors != null) {
                    errors.add("fieldMappingConfig 不是合法映射数组，建议使用数组或 {\"mappings\": [...]} 结构");
                }
                return new ParsedFieldMappings(false, List.of());
            }

            List<FieldMappingPair> mappings = new ArrayList<>();
            for (JsonNode mappingNode : mappingsNode) {
                mappings.add(new FieldMappingPair(textOf(mappingNode, "sourceField"), textOf(mappingNode, "targetField")));
            }
            return new ParsedFieldMappings(true, mappings);
        } catch (Exception exception) {
            if (reportErrors && errors != null) {
                errors.add("fieldMappingConfig 不是合法 JSON: " + exception.getMessage());
            }
            return new ParsedFieldMappings(false, List.of());
        }
    }

    private List<String> buildAutoMappingSuggestions(TableMetadataSummary sourceTable, TableMetadataSummary targetTable,
                                                     Set<String> mappedTargetFields) {
        Map<String, ColumnMetadataSummary> sourceColumns = indexColumnsByName(sourceTable);
        if (targetTable.getColumns() == null) {
            return List.of();
        }
        List<String> suggestions = new ArrayList<>();
        for (ColumnMetadataSummary targetColumn : targetTable.getColumns()) {
            if (targetColumn == null || isBlank(targetColumn.getColumnName())) {
                continue;
            }
            String normalizedTargetField = normalize(targetColumn.getColumnName());
            if (!mappedTargetFields.contains(normalizedTargetField) && sourceColumns.containsKey(normalizedTargetField)) {
                suggestions.add(sourceColumns.get(normalizedTargetField).getColumnName() + " -> " + targetColumn.getColumnName()
                        + "（源端与目标端同名，可作为自动映射候选）");
            }
        }
        return suggestions.stream().limit(20).toList();
    }

    private List<String> findUnmappedRequiredTargetFields(TableMetadataSummary targetTable, Set<String> mappedTargetFields) {
        if (targetTable.getColumns() == null) {
            return List.of();
        }
        return targetTable.getColumns().stream()
                .filter(column -> column != null && !isBlank(column.getColumnName()))
                .filter(column -> !column.isNullable())
                .filter(column -> !column.isAutoIncrement())
                .filter(column -> isBlank(column.getDefaultValue()))
                .map(ColumnMetadataSummary::getColumnName)
                .filter(columnName -> !mappedTargetFields.contains(normalize(columnName)))
                .toList();
    }

    private boolean isCompatibleType(String sourceType, String targetType) {
        String sourceCategory = normalizeTypeCategory(sourceType);
        String targetCategory = normalizeTypeCategory(targetType);
        return sourceCategory.equals(targetCategory) || "unknown".equals(sourceCategory) || "unknown".equals(targetCategory);
    }

    private String normalizeTypeCategory(String dataTypeName) {
        if (isBlank(dataTypeName)) {
            return "unknown";
        }
        String normalized = dataTypeName.toLowerCase(Locale.ROOT);
        if (normalized.contains("char") || normalized.contains("text") || normalized.contains("clob")
                || normalized.contains("json") || normalized.contains("xml")) {
            return "string";
        }
        if (normalized.contains("int") || normalized.contains("number") || normalized.contains("decimal")
                || normalized.contains("numeric") || normalized.contains("double") || normalized.contains("float")
                || normalized.contains("real")) {
            return "numeric";
        }
        if (normalized.contains("date") || normalized.contains("time") || normalized.contains("timestamp")) {
            return "datetime";
        }
        if (normalized.contains("bool") || normalized.contains("bit")) {
            return "boolean";
        }
        if (normalized.contains("binary") || normalized.contains("blob")) {
            return "binary";
        }
        return "unknown";
    }

    private String buildTruncationRisk(ColumnMetadataSummary sourceColumn, ColumnMetadataSummary targetColumn,
                                       FieldMappingPair mapping) {
        String sourceCategory = normalizeTypeCategory(sourceColumn.getDataTypeName());
        String targetCategory = normalizeTypeCategory(targetColumn.getDataTypeName());
        if (!sourceCategory.equals(targetCategory)) {
            return null;
        }
        if ("string".equals(sourceCategory) && sourceColumn.getColumnSize() != null
                && targetColumn.getColumnSize() != null && sourceColumn.getColumnSize() > targetColumn.getColumnSize()) {
            return mapping.sourceField() + " -> " + mapping.targetField()
                    + " 可能发生长度截断（源长度=" + sourceColumn.getColumnSize()
                    + "，目标长度=" + targetColumn.getColumnSize() + "）";
        }
        if ("numeric".equals(sourceCategory) && sourceColumn.getColumnSize() != null
                && targetColumn.getColumnSize() != null && sourceColumn.getColumnSize() > targetColumn.getColumnSize()) {
            return mapping.sourceField() + " -> " + mapping.targetField()
                    + " 可能发生数值精度截断（源精度=" + sourceColumn.getColumnSize()
                    + "，目标精度=" + targetColumn.getColumnSize() + "）";
        }
        if ("numeric".equals(sourceCategory) && sourceColumn.getDecimalDigits() != null
                && targetColumn.getDecimalDigits() != null && sourceColumn.getDecimalDigits() > targetColumn.getDecimalDigits()) {
            return mapping.sourceField() + " -> " + mapping.targetField()
                    + " 可能发生小数位截断（源小数位=" + sourceColumn.getDecimalDigits()
                    + "，目标小数位=" + targetColumn.getDecimalDigits() + "）";
        }
        return null;
    }

    private String textOf(JsonNode mappingNode, String fieldName) {
        JsonNode fieldNode = mappingNode.get(fieldName);
        return fieldNode == null || fieldNode.isNull() ? null : fieldNode.asText();
    }

    private record FieldMappingPair(String sourceField, String targetField) {
    }

    private record ParsedFieldMappings(boolean valid, List<FieldMappingPair> mappings) {
    }
}

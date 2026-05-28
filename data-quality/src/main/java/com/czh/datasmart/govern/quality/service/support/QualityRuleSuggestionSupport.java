/**
 * @Author : Cui
 * @Date: 2026/05/24 22:08
 * @Description DataSmart Govern Backend - QualityRuleSuggestionSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service.support;

import com.czh.datasmart.govern.quality.controller.dto.QualityRuleDraftSuggestion;
import com.czh.datasmart.govern.quality.controller.dto.QualityRuleSuggestionRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityRuleSuggestionResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 质量规则草案建议支撑组件。
 *
 * <p>这个组件是 data-quality 面向 Agent 的第一条“智能建议”能力，但当前故意不依赖真实大模型。
 * 原因是商业化产品不能把核心治理流程完全建立在不稳定模型输出上：即使模型服务不可用，
 * 系统也应该能根据元数据生成一批基础、可解释、可审查的规则草案。</p>
 *
 * <p>第一版生成策略是确定性规则引擎：</p>
 * <p>1. 如果字段是主键，生成唯一性规则；</p>
 * <p>2. 如果字段不可为空或字段名命中关键业务词，生成完整性规则；</p>
 * <p>3. 如果字段名疑似金额、数量、价格，生成有效性规则草案，提醒用户确认阈值；</p>
 * <p>4. 如果元数据不足，返回 warning，而不是伪造看似完整的建议。</p>
 *
 * <p>后续演进方向：</p>
 * <p>1. 接入 Python AI Runtime，让模型根据业务目标、字段语义和历史异常生成更丰富草案；</p>
 * <p>2. 接入数据资产和业务术语，识别手机号、身份证、订单号、金额等字段语义；</p>
 * <p>3. 接入历史质量画像，基于实际分布推荐阈值，而不是固定阈值；</p>
 * <p>4. 接入审批和版本管理，把草案转为 DRAFT 规则后再进入启用流程。</p>
 */
@Slf4j
@Component
public class QualityRuleSuggestionSupport {

    private static final int DEFAULT_MAX_SUGGESTIONS = 8;
    private static final BigDecimal HIGH_CONFIDENCE = new BigDecimal("0.90");
    private static final BigDecimal MEDIUM_CONFIDENCE = new BigDecimal("0.75");
    private static final BigDecimal LOW_CONFIDENCE = new BigDecimal("0.60");

    /**
     * 根据元数据生成质量规则草案。
     *
     * @param request 规则建议请求，包含租户/项目、数据源、治理目标和元数据快照
     * @return 只读草案建议，不落库、不启用、不提交任务
     */
    public QualityRuleSuggestionResponse suggest(QualityRuleSuggestionRequest request) {
        int maxSuggestions = request.getMaxSuggestions() == null
                ? DEFAULT_MAX_SUGGESTIONS
                : Math.min(request.getMaxSuggestions(), 20);
        List<String> warnings = new ArrayList<>();
        List<TableSnapshot> tables = extractTables(request.getMetadata(), request.getTableName(), warnings);
        List<QualityRuleDraftSuggestion> suggestions = new ArrayList<>();
        for (TableSnapshot table : tables) {
            appendTableSuggestions(request, table, suggestions, maxSuggestions);
            if (suggestions.size() >= maxSuggestions) {
                break;
            }
        }
        if (tables.isEmpty()) {
            warnings.add("未从 metadata 中解析到表结构，本次无法生成字段级质量规则草案");
        }
        if (suggestions.size() >= maxSuggestions) {
            warnings.add("建议数量已达到上限 " + maxSuggestions + "，可缩小表名范围或提高 maxSuggestions 后再次生成");
        }

        QualityRuleSuggestionResponse response = new QualityRuleSuggestionResponse();
        response.setDatasourceId(request.getDatasourceId());
        response.setTableName(request.getTableName());
        response.setBusinessGoal(request.getBusinessGoal());
        response.setSuggestionCount(suggestions.size());
        response.setSuggestions(suggestions);
        response.setGenerationStrategy("deterministic-metadata-rule-engine-v1");
        response.setWarnings(warnings);
        response.setRecommendedActions(List.of(
                "请先人工确认草案名称、字段范围和阈值，再保存为 DRAFT 规则。",
                "金额、数量、价格等有效性规则当前只给出基础阈值，生产启用前建议结合历史分布调整。",
                "如果需要更复杂的跨字段一致性、枚举合法性或异常检测，请后续接入 Python AI Runtime 与数据画像。"
        ));
        log.info("质量规则草案建议生成完成，datasourceId={}, tableName={}, suggestions={}",
                request.getDatasourceId(), request.getTableName(), suggestions.size());
        return response;
    }

    private void appendTableSuggestions(QualityRuleSuggestionRequest request,
                                        TableSnapshot table,
                                        List<QualityRuleDraftSuggestion> suggestions,
                                        int maxSuggestions) {
        for (ColumnSnapshot column : table.columns()) {
            if (suggestions.size() >= maxSuggestions) {
                return;
            }
            if (column.primaryKey()) {
                suggestions.add(buildSuggestion(request, table, column,
                        "UNIQUENESS",
                        "RELATIONAL_FIELD",
                        "GTE",
                        BigDecimal.ONE,
                        "CRITICAL",
                        "字段被识别为主键，建议检查唯一性通过率是否达到 100%。",
                        HIGH_CONFIDENCE));
            }
            if (suggestions.size() >= maxSuggestions) {
                return;
            }
            if (!column.nullable() || looksImportantBusinessField(column.columnName())) {
                suggestions.add(buildSuggestion(request, table, column,
                        "COMPLETENESS",
                        "RELATIONAL_FIELD",
                        "GTE",
                        BigDecimal.ONE,
                        "HIGH",
                        "字段不可为空或属于关键业务字段，建议检查非空完整性。",
                        MEDIUM_CONFIDENCE));
            }
            if (suggestions.size() >= maxSuggestions) {
                return;
            }
            if (looksNumericBusinessMetric(column)) {
                suggestions.add(buildSuggestion(request, table, column,
                        "VALIDITY",
                        "RELATIONAL_FIELD",
                        "GTE",
                        BigDecimal.ZERO,
                        "MEDIUM",
                        "字段疑似金额、数量、价格或指标值，建议先检查是否存在负数或非法值。",
                        LOW_CONFIDENCE));
            }
        }
    }

    private QualityRuleDraftSuggestion buildSuggestion(QualityRuleSuggestionRequest request,
                                                       TableSnapshot table,
                                                       ColumnSnapshot column,
                                                       String ruleType,
                                                       String targetType,
                                                       String operator,
                                                       BigDecimal expectedValue,
                                                       String severity,
                                                       String reason,
                                                       BigDecimal confidence) {
        QualityRuleDraftSuggestion suggestion = new QualityRuleDraftSuggestion();
        suggestion.setName(table.tableName() + "." + column.columnName() + " " + ruleType + " 草案");
        suggestion.setRuleType(ruleType);
        suggestion.setTargetObject(buildTargetObject(table, column));
        suggestion.setTargetType(targetType);
        suggestion.setDataSourceId(request.getDatasourceId());
        suggestion.setDatabaseName(table.catalog());
        suggestion.setSchemaName(table.schemaName());
        suggestion.setTableName(table.tableName());
        suggestion.setFieldName(column.columnName());
        suggestion.setComparisonOperator(operator);
        suggestion.setExpectedValue(expectedValue);
        suggestion.setSeverity(severity);
        suggestion.setDescription("根据数据源元数据和治理目标生成的规则草案。治理目标：" + request.getBusinessGoal());
        suggestion.setSuggestionReason(reason);
        suggestion.setConfidence(confidence);
        suggestion.setGovernanceNotes(List.of(
                "该建议尚未落库，需用户确认后再保存为 DRAFT。",
                "当前未读取样本数据，因此无法基于真实分布自动推荐复杂阈值。",
                "启用前应调用目标校验和扫描计划接口，确认数据源、表、字段仍然可访问。"
        ));
        return suggestion;
    }

    private List<TableSnapshot> extractTables(Map<String, Object> metadata, String requestedTableName, List<String> warnings) {
        if (metadata == null || metadata.isEmpty()) {
            return List.of();
        }
        Object tablesValue = metadata.get("tables");
        if (!(tablesValue instanceof List<?> rawTables)) {
            return List.of();
        }
        List<TableSnapshot> tables = new ArrayList<>();
        for (Object rawTable : rawTables) {
            if (!(rawTable instanceof Map<?, ?> tableMap)) {
                continue;
            }
            TableSnapshot table = toTableSnapshot(tableMap);
            if (requestedTableName != null && !requestedTableName.isBlank()
                    && !requestedTableName.equalsIgnoreCase(table.tableName())) {
                continue;
            }
            tables.add(table);
            if (Boolean.TRUE.equals(table.columnsTruncated())) {
                warnings.add("表 " + table.tableName() + " 的字段元数据被截断，生成建议可能不完整");
            }
        }
        return tables;
    }

    private TableSnapshot toTableSnapshot(Map<?, ?> tableMap) {
        String catalog = stringValue(tableMap.get("catalog"));
        String schemaName = stringValue(tableMap.get("schemaName"));
        String tableName = stringValue(tableMap.get("tableName"));
        boolean columnsTruncated = booleanValue(tableMap.get("columnsTruncated"));
        List<String> primaryKeys = stringList(tableMap.get("primaryKeys"));
        List<ColumnSnapshot> columns = new ArrayList<>();
        Object columnsValue = tableMap.get("columns");
        if (columnsValue instanceof List<?> rawColumns) {
            for (Object rawColumn : rawColumns) {
                if (rawColumn instanceof Map<?, ?> columnMap) {
                    columns.add(toColumnSnapshot(columnMap, primaryKeys));
                }
            }
        }
        return new TableSnapshot(catalog, schemaName, tableName, columnsTruncated, columns);
    }

    private ColumnSnapshot toColumnSnapshot(Map<?, ?> columnMap, List<String> primaryKeys) {
        String columnName = stringValue(columnMap.get("columnName"));
        String dataTypeName = stringValue(columnMap.get("dataTypeName"));
        boolean nullable = booleanValue(columnMap.get("nullable"));
        boolean primaryKey = booleanValue(columnMap.get("primaryKey")) || primaryKeys.contains(columnName);
        return new ColumnSnapshot(columnName, dataTypeName, nullable, primaryKey);
    }

    private boolean looksImportantBusinessField(String columnName) {
        String normalized = normalize(columnName);
        return normalized.contains("id")
                || normalized.contains("code")
                || normalized.contains("no")
                || normalized.contains("name")
                || normalized.contains("phone")
                || normalized.contains("email")
                || normalized.contains("status");
    }

    private boolean looksNumericBusinessMetric(ColumnSnapshot column) {
        String normalizedName = normalize(column.columnName());
        String normalizedType = normalize(column.dataTypeName());
        boolean nameMatched = normalizedName.contains("amount")
                || normalizedName.contains("price")
                || normalizedName.contains("qty")
                || normalizedName.contains("quantity")
                || normalizedName.contains("count")
                || normalizedName.contains("score");
        boolean typeMatched = normalizedType.contains("int")
                || normalizedType.contains("decimal")
                || normalizedType.contains("number")
                || normalizedType.contains("double")
                || normalizedType.contains("float");
        return nameMatched && typeMatched;
    }

    private String buildTargetObject(TableSnapshot table, ColumnSnapshot column) {
        List<String> parts = new ArrayList<>();
        if (table.schemaName() != null && !table.schemaName().isBlank()) {
            parts.add(table.schemaName());
        }
        parts.add(table.tableName());
        parts.add(column.columnName());
        return String.join(".", parts);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value instanceof String text && Boolean.parseBoolean(text);
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private record TableSnapshot(String catalog,
                                 String schemaName,
                                 String tableName,
                                 Boolean columnsTruncated,
                                 List<ColumnSnapshot> columns) {
    }

    private record ColumnSnapshot(String columnName,
                                  String dataTypeName,
                                  boolean nullable,
                                  boolean primaryKey) {
    }
}

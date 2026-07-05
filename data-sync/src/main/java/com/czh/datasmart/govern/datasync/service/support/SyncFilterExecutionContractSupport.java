/**
 * @Author : Cui
 * @Date: 2026/07/05 15:30
 * @Description DataSmart Govern Backend - SyncFilterExecutionContractSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * filterConfig 执行契约解析器。
 *
 * <p>本组件把用户配置的过滤条件从“配置 JSON”转换为“执行器可消费的安全条件列表”。
 * 它只支持保守的 AND 条件组合，每个条件最终都会进入 PreparedStatement 参数绑定。</p>
 *
 * <p>为什么不直接允许用户填写 where 字符串：</p>
 * <p>1. where 字符串很容易混入函数、子查询、注释、多语句或 DDL/DML 片段；</p>
 * <p>2. 即使做关键字黑名单，也很难覆盖所有数据库方言和编码绕过；</p>
 * <p>3. 商业化产品需要可解释、可审批、可审计的过滤条件，结构化 JSON 比原始 SQL 更适合作为长期控制面模型。</p>
 *
 * <p>当前兼容两种 JSON 形态：</p>
 * <pre>
 * [
 *   {"field": "status", "operator": "=", "value": "ACTIVE"}
 * ]
 * </pre>
 * <pre>
 * {
 *   "logic": "AND",
 *   "conditions": [
 *     {"column": "biz_date", "op": ">=", "value": "2026-01-01"}
 *   ]
 * }
 * </pre>
 */
@Component
@RequiredArgsConstructor
public class SyncFilterExecutionContractSupport {

    private static final int MAX_FILTER_CONDITIONS = 20;
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Set<String> VALUE_REQUIRED_OPERATORS =
            Set.of("EQ", "NE", "GT", "GTE", "LT", "LTE", "LIKE");

    private final ObjectMapper objectMapper;

    public SyncFilterExecutionContractSupport() {
        this(new ObjectMapper());
    }

    /**
     * 解析模板过滤配置。
     *
     * @param filterConfig 模板中的 filterConfig JSON；为空表示不追加 where 条件。
     * @return 内部执行契约。
     */
    public SyncFilterExecutionContract parse(String filterConfig) {
        if (!hasText(filterConfig)) {
            return new SyncFilterExecutionContract(false, true, List.of(), List.of(), List.of());
        }

        List<String> issueCodes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        JsonNode conditionsNode = readConditionsNode(filterConfig, issueCodes);
        if (conditionsNode == null) {
            return contract(true, false, List.of(), issueCodes, warnings);
        }
        if (!conditionsNode.isArray()) {
            issueCodes.add("FILTER_CONFIG_SCHEMA_UNSUPPORTED");
            return contract(true, false, List.of(), issueCodes, warnings);
        }
        if (conditionsNode.size() > MAX_FILTER_CONDITIONS) {
            issueCodes.add("FILTER_CONDITION_COUNT_EXCEEDED");
            return contract(true, true, List.of(), issueCodes, warnings);
        }

        List<SyncFilterExecutionCondition> conditions = new ArrayList<>();
        for (JsonNode conditionNode : conditionsNode) {
            SyncFilterExecutionCondition condition = parseCondition(conditionNode, issueCodes);
            if (condition != null) {
                conditions.add(condition);
            }
        }
        if (conditions.isEmpty() && issueCodes.isEmpty()) {
            warnings.add("FILTER_CONFIG_DECLARED_BUT_EMPTY");
        }
        return contract(true, true, conditions, issueCodes, warnings);
    }

    private JsonNode readConditionsNode(String filterConfig, List<String> issueCodes) {
        try {
            JsonNode root = objectMapper.readTree(filterConfig);
            if (root.isArray()) {
                return root;
            }
            String logic = text(root, "logic");
            if (hasText(logic) && !"AND".equals(logic.trim().toUpperCase(Locale.ROOT))) {
                issueCodes.add("FILTER_LOGIC_ONLY_AND_SUPPORTED");
                return null;
            }
            return root.path("conditions");
        } catch (Exception exception) {
            issueCodes.add("FILTER_CONFIG_PARSE_FAILED");
            return null;
        }
    }

    private SyncFilterExecutionCondition parseCondition(JsonNode node, List<String> issueCodes) {
        if (node == null || !node.isObject()) {
            issueCodes.add("FILTER_CONDITION_SCHEMA_UNSUPPORTED");
            return null;
        }
        if (node.has("enabled") && !node.path("enabled").asBoolean(true)) {
            return null;
        }
        String column = firstText(node, "field", "column", "sourceField", "sourceColumn");
        String operator = normalizeOperator(firstText(node, "operator", "op"));
        if (!safeIdentifier(column)) {
            issueCodes.add("FILTER_COLUMN_IDENTIFIER_UNSAFE");
            return null;
        }
        if (!hasText(operator)) {
            issueCodes.add("FILTER_OPERATOR_UNSUPPORTED");
            return null;
        }
        boolean valueRequired = VALUE_REQUIRED_OPERATORS.contains(operator);
        Object value = readValue(node.path("value"));
        if (valueRequired && value == null) {
            issueCodes.add("FILTER_VALUE_REQUIRED");
            return null;
        }
        return new SyncFilterExecutionCondition(column.trim(), operator, value, valueRequired);
    }

    private Object readValue(JsonNode valueNode) {
        if (valueNode == null || valueNode.isMissingNode() || valueNode.isNull()) {
            return null;
        }
        if (valueNode.isIntegralNumber()) {
            return valueNode.longValue();
        }
        if (valueNode.isFloatingPointNumber()) {
            return valueNode.decimalValue();
        }
        if (valueNode.isBoolean()) {
            return valueNode.booleanValue();
        }
        if (valueNode.isTextual()) {
            return valueNode.asText();
        }
        if (valueNode.isNumber()) {
            return new BigDecimal(valueNode.asText());
        }
        return valueNode.asText();
    }

    private String normalizeOperator(String value) {
        if (!hasText(value)) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "=", "EQ" -> "EQ";
            case "!=", "<>", "NE" -> "NE";
            case ">", "GT" -> "GT";
            case ">=", "GTE" -> "GTE";
            case "<", "LT" -> "LT";
            case "<=", "LTE" -> "LTE";
            case "LIKE" -> "LIKE";
            case "IS_NULL", "IS NULL" -> "IS_NULL";
            case "IS_NOT_NULL", "IS NOT NULL" -> "IS_NOT_NULL";
            default -> null;
        };
    }

    private SyncFilterExecutionContract contract(boolean declared,
                                                 boolean parseable,
                                                 List<SyncFilterExecutionCondition> conditions,
                                                 List<String> issueCodes,
                                                 List<String> warnings) {
        return new SyncFilterExecutionContract(declared, parseable, conditions, distinct(issueCodes), distinct(warnings));
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        JsonNode fieldNode = node == null ? null : node.get(field);
        return fieldNode == null || fieldNode.isNull() ? null : fieldNode.asText();
    }

    private boolean safeIdentifier(String value) {
        return hasText(value) && SAFE_IDENTIFIER.matcher(value.trim()).matches();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }
}

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
    private static final Pattern BINARY_WHERE_CONDITION = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]{0,127})\\s*(>=|<=|<>|!=|=|>|<|(?i:LIKE))\\s*(.+)$");
    private static final Pattern NULL_WHERE_CONDITION = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]{0,127})\\s+(?i:IS\\s+NULL|IS\\s+NOT\\s+NULL)$");
    private static final Pattern OBJECT_WHERE_DANGEROUS_TOKEN = Pattern.compile(
            "\\b(insert|update|delete|merge|drop|alter|create|truncate|grant|revoke|call|exec|execute|copy|load|replace)\\b",
            Pattern.CASE_INSENSITIVE);
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

    /**
     * 解析对象映射行上的 whereCondition。
     *
     * <p>当前前端创建向导把过滤条件放在“对象映射”列表的每一行，例如某张表配置
     * {@code id > 1;}，另一张表配置 {@code status = 'ACTIVE'}。这类配置比模板级 filterConfig
     * 更贴近用户实际操作，如果执行计划不消费它，就会出现“页面显示已过滤、真实同步却全表读取”的严重偏差。</p>
     *
     * <p>产品语义：对象级 where 是真正面向数据库表筛选的谓词能力，必须支持真实业务里常见的
     * {@code OR}、括号、函数、{@code EXISTS/IN} 子查询等表达式。与此同时，它仍然不是“任意 SQL”：用户只能填写
     * {@code WHERE} 后面的谓词片段，不能填写完整 SELECT、不能多语句、不能 DDL/DML、不能注释逃逸。</p>
     *
     * <p>执行策略分两层：</p>
     * <p>1. 如果 where 能被安全拆成简单 AND 条件，就转换成 {@link SyncFilterExecutionCondition}，继续由
     * datasource-management 使用 PreparedStatement 绑定参数；</p>
     * <p>2. 如果 where 使用了 OR、括号、函数或子查询，则作为受控 {@code wherePredicate} 下发给执行层，
     * datasource-management 会再次做只读谓词校验并拼成 {@code WHERE (...)}。这条路径牺牲了一部分参数化能力，
     * 但换来数据同步工具必须具备的复杂筛选表达能力。</p>
     *
     * @param whereCondition 对象级 where 条件原文。为空表示该对象不追加过滤条件。
     * @return 内部过滤执行契约。issueCodes 非空时调用方必须阻断真实读写。
     */
    public SyncFilterExecutionContract parseObjectWhereCondition(String whereCondition) {
        if (!hasText(whereCondition)) {
            return new SyncFilterExecutionContract(false, true, List.of(), List.of(), List.of());
        }
        List<String> issueCodes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String normalized = normalizeObjectWhere(whereCondition, issueCodes);
        if (!issueCodes.isEmpty()) {
            return contract(true, false, List.of(), null, issueCodes, warnings);
        }
        if (!hasText(normalized)) {
            warnings.add("OBJECT_WHERE_DECLARED_BUT_EMPTY");
            return contract(true, true, List.of(), null, issueCodes, warnings);
        }
        if (requiresSqlPredicatePath(normalized)) {
            warnings.add("OBJECT_WHERE_COMPLEX_PREDICATE_USED_AS_SQL_PREDICATE");
            return contract(true, true, List.of(), normalized, issueCodes, warnings);
        }

        List<String> simpleIssueCodes = new ArrayList<>();
        List<String> clauses = splitAndClauses(normalized, simpleIssueCodes);
        if (clauses.size() > MAX_FILTER_CONDITIONS) {
            simpleIssueCodes.add("OBJECT_WHERE_CONDITION_COUNT_EXCEEDED");
        }

        List<SyncFilterExecutionCondition> conditions = new ArrayList<>();
        for (String clause : clauses) {
            SyncFilterExecutionCondition condition = parseObjectWhereClause(clause, simpleIssueCodes);
            if (condition != null) {
                conditions.add(condition);
            }
        }
        if (simpleIssueCodes.isEmpty() && !conditions.isEmpty()) {
            warnings.add("OBJECT_WHERE_CONDITION_USED_AS_STRUCTURED_FILTER");
            return contract(true, true, conditions, null, issueCodes, warnings);
        }
        /*
         * 只要谓词通过了“单片段 + 只读危险词 + 括号/引号平衡”安全门禁，简单结构化解析失败并不代表任务不能执行。
         * 例如 BETWEEN、IN、COALESCE、DATE()、EXISTS 子查询都不是三段式条件，但它们是用户真实需要的 where 能力。
         * 因此这里回退到 wherePredicate 路径，由数据库方言执行层做二次防护并最终交给源端数据库解析。
         */
        warnings.add("OBJECT_WHERE_FALLBACK_TO_SQL_PREDICATE");
        return contract(true, true, List.of(), normalized, issueCodes, warnings);
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

    /**
     * 将对象级 where 原文规整为可解析表达式。
     *
     * <p>这里做的是“复杂 where 的第一道门禁”，而不是完整 SQL 语法解析。原因是不同客户源库的函数、
     * 日期表达式和子查询方言差异很大，控制面不应试图完整替代数据库解析器。我们只保证它仍然是单个 where 谓词：
     * 允许括号、OR、函数和 SELECT 子查询；拒绝多语句、注释、写操作关键字和括号/引号不平衡。</p>
     */
    private String normalizeObjectWhere(String whereCondition, List<String> issueCodes) {
        String value = whereCondition.trim();
        if (value.regionMatches(true, 0, "WHERE", 0, "WHERE".length())) {
            String afterWhere = value.substring("WHERE".length()).trim();
            if (!afterWhere.isEmpty()) {
                value = afterWhere;
            }
        }
        if (value.endsWith(";")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        if (!hasText(value)) {
            return "";
        }
        if (value.contains(";")) {
            issueCodes.add("OBJECT_WHERE_MULTIPLE_STATEMENTS_UNSUPPORTED");
        }
        if (value.contains("--") || value.contains("/*") || value.contains("*/")) {
            issueCodes.add("OBJECT_WHERE_COMMENT_UNSUPPORTED");
        }
        if (OBJECT_WHERE_DANGEROUS_TOKEN.matcher(value).find()) {
            issueCodes.add("OBJECT_WHERE_DML_DDL_TOKEN_UNSUPPORTED");
        }
        /*
         * 子查询是允许的，但“完整 SELECT/WITH 语句”不是 where 条件。
         *
         * 允许示例：
         * - id IN (SELECT customer_id FROM vip_customer)
         * - EXISTS (SELECT 1 FROM order_item oi WHERE oi.customer_id = id)
         *
         * 拒绝示例：
         * - SELECT * FROM customer
         * - WITH t AS (...) SELECT ...
         *
         * 这样可以满足真实业务里的复杂过滤，同时避免把对象级 where 输入框变成任意 SQL 执行入口。
         */
        if (isTokenAt(value, 0, "SELECT") || isTokenAt(value, 0, "WITH")) {
            issueCodes.add("OBJECT_WHERE_TOP_LEVEL_QUERY_UNSUPPORTED");
        }
        if (!balancedQuotesAndParentheses(value)) {
            issueCodes.add("OBJECT_WHERE_SYNTAX_BOUNDARY_UNBALANCED");
        }
        return value;
    }

    private boolean requiresSqlPredicatePath(String value) {
        return containsLogicalTokenOutsideQuotes(value, "OR")
                || containsLogicalTokenOutsideQuotes(value, "SELECT")
                || containsLogicalTokenOutsideQuotes(value, "EXISTS")
                || containsLogicalTokenOutsideQuotes(value, "IN")
                || value.contains("(")
                || value.contains(")")
                || value.matches("(?s).*[A-Za-z_][A-Za-z0-9_]{0,127}\\s*\\(.*");
    }

    /**
     * 按 AND 拆分条件，同时尊重单引号/双引号中的文本。
     *
     * <p>示例：{@code status = 'A AND B' AND id > 1} 会拆成两段，而不是把字符串字面量里的 AND 当成逻辑运算符。
     * 这类手写扫描比简单正则更啰嗦，但更容易解释边界，也避免无意破坏用户合法字符串值。</p>
     */
    private List<String> splitAndClauses(String value, List<String> issueCodes) {
        List<String> clauses = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int index = 0; index < value.length(); index++) {
            char currentChar = value.charAt(index);
            if (currentChar == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
                current.append(currentChar);
                continue;
            }
            if (currentChar == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                current.append(currentChar);
                continue;
            }
            if (!singleQuoted && !doubleQuoted && isTokenAt(value, index, "AND")) {
                appendClause(clauses, current, issueCodes);
                current.setLength(0);
                index += "AND".length() - 1;
                continue;
            }
            current.append(currentChar);
        }
        if (singleQuoted || doubleQuoted) {
            issueCodes.add("OBJECT_WHERE_QUOTE_NOT_CLOSED");
            return List.of();
        }
        appendClause(clauses, current, issueCodes);
        return clauses;
    }

    private void appendClause(List<String> clauses, StringBuilder current, List<String> issueCodes) {
        String clause = current.toString().trim();
        if (!hasText(clause)) {
            issueCodes.add("OBJECT_WHERE_EMPTY_CONDITION");
            return;
        }
        clauses.add(clause);
    }

    private SyncFilterExecutionCondition parseObjectWhereClause(String clause, List<String> issueCodes) {
        java.util.regex.Matcher nullMatcher = NULL_WHERE_CONDITION.matcher(clause);
        if (nullMatcher.matches()) {
            String column = nullMatcher.group(1);
            String operator = clause.toUpperCase(Locale.ROOT).contains("IS NOT NULL") ? "IS_NOT_NULL" : "IS_NULL";
            return new SyncFilterExecutionCondition(column.trim(), operator, null, false);
        }

        java.util.regex.Matcher binaryMatcher = BINARY_WHERE_CONDITION.matcher(clause);
        if (!binaryMatcher.matches()) {
            issueCodes.add("OBJECT_WHERE_CONDITION_SCHEMA_UNSUPPORTED");
            return null;
        }
        String column = binaryMatcher.group(1);
        String operator = normalizeOperator(binaryMatcher.group(2));
        Object value = parseObjectWhereLiteral(binaryMatcher.group(3), issueCodes);
        if (!safeIdentifier(column)) {
            issueCodes.add("OBJECT_WHERE_COLUMN_IDENTIFIER_UNSAFE");
            return null;
        }
        if (!hasText(operator)) {
            issueCodes.add("OBJECT_WHERE_OPERATOR_UNSUPPORTED");
            return null;
        }
        if (VALUE_REQUIRED_OPERATORS.contains(operator) && value == null) {
            issueCodes.add("OBJECT_WHERE_VALUE_REQUIRED");
            return null;
        }
        return new SyncFilterExecutionCondition(column.trim(), operator, value, true);
    }

    /**
     * 解析最小字面量类型。
     *
     * <p>数字会尽量转换为 Long 或 BigDecimal，字符串需要使用普通单引号/双引号包裹，未加引号的安全 token
     * 仅用于兼容枚举、状态码这类常见输入。所有结果最终仍是 PreparedStatement 参数，不会拼接进 SQL。</p>
     */
    private Object parseObjectWhereLiteral(String rawValue, List<String> issueCodes) {
        if (!hasText(rawValue)) {
            issueCodes.add("OBJECT_WHERE_VALUE_REQUIRED");
            return null;
        }
        String value = rawValue.trim();
        if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        if (value.contains("'") || value.contains("\"") || value.contains("\\") || value.contains(",")) {
            issueCodes.add("OBJECT_WHERE_LITERAL_UNSAFE");
            return null;
        }
        if (value.matches("[-+]?\\d+")) {
            try {
                return Long.valueOf(value);
            } catch (NumberFormatException exception) {
                issueCodes.add("OBJECT_WHERE_NUMERIC_LITERAL_INVALID");
                return null;
            }
        }
        if (value.matches("[-+]?\\d+\\.\\d+")) {
            try {
                return new BigDecimal(value);
            } catch (NumberFormatException exception) {
                issueCodes.add("OBJECT_WHERE_NUMERIC_LITERAL_INVALID");
                return null;
            }
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.valueOf(value);
        }
        if (!value.matches("[A-Za-z0-9_:\\-.]{1,256}")) {
            issueCodes.add("OBJECT_WHERE_LITERAL_UNSAFE");
            return null;
        }
        return value;
    }

    private boolean containsLogicalTokenOutsideQuotes(String value, String token) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        for (int index = 0; index < value.length(); index++) {
            char currentChar = value.charAt(index);
            if (currentChar == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
                continue;
            }
            if (currentChar == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                continue;
            }
            if (!singleQuoted && !doubleQuoted && isTokenAt(value, index, token)) {
                return true;
            }
        }
        return false;
    }

    private boolean balancedQuotesAndParentheses(String value) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        int parenthesesDepth = 0;
        for (int index = 0; index < value.length(); index++) {
            char currentChar = value.charAt(index);
            if (currentChar == '\'' && !doubleQuoted) {
                if (singleQuoted && index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                    index++;
                    continue;
                }
                singleQuoted = !singleQuoted;
                continue;
            }
            if (currentChar == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                continue;
            }
            if (singleQuoted || doubleQuoted) {
                continue;
            }
            if (currentChar == '(') {
                parenthesesDepth++;
            } else if (currentChar == ')') {
                parenthesesDepth--;
                if (parenthesesDepth < 0) {
                    return false;
                }
            }
        }
        return !singleQuoted && !doubleQuoted && parenthesesDepth == 0;
    }

    private boolean isTokenAt(String value, int index, String token) {
        if (index < 0 || index + token.length() > value.length()) {
            return false;
        }
        if (!value.regionMatches(true, index, token, 0, token.length())) {
            return false;
        }
        boolean leftBoundary = index == 0 || !isIdentifierPart(value.charAt(index - 1));
        int rightIndex = index + token.length();
        boolean rightBoundary = rightIndex >= value.length() || !isIdentifierPart(value.charAt(rightIndex));
        return leftBoundary && rightBoundary;
    }

    private boolean isIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
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
        return contract(declared, parseable, conditions, null, issueCodes, warnings);
    }

    private SyncFilterExecutionContract contract(boolean declared,
                                                 boolean parseable,
                                                 List<SyncFilterExecutionCondition> conditions,
                                                 String wherePredicate,
                                                 List<String> issueCodes,
                                                 List<String> warnings) {
        return new SyncFilterExecutionContract(declared, parseable, conditions, wherePredicate,
                distinct(issueCodes), distinct(warnings));
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

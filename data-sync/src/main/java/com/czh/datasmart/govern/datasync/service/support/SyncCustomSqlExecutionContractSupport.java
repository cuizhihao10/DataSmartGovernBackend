/**
 * @Author : Cui
 * @Date: 2026/07/07 23:58
 * @Description DataSmart Govern Backend - SyncCustomSqlExecutionContractSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.support.SyncMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 自定义 SQL 同步的内部执行契约解析器。
 *
 * <p>{@code CUSTOM_SQL_QUERY} 和普通 {@code filterConfig} 的风险级别完全不同：
 * filterConfig 只允许“字段 + 操作符 + 参数值”的结构化条件，最终由 JDBC 方言生成 PreparedStatement；
 * customSqlConfig 则由用户或 Agent 直接声明一条查询语句。如果不单独治理，很容易把 DDL/DML、
 * 多语句、注释逃逸、存储过程、COPY/LOAD 等高风险能力混入数据同步执行器。</p>
 *
 * <p>本组件只做 data-sync 控制面执行前的第一道门禁：</p>
 * <p>1. 只在 syncMode=CUSTOM_SQL_QUERY 时解析 customSqlConfig；其他模式返回空合同；</p>
 * <p>2. 当前 v1 真实执行只支持内联只读 SQL，不支持 statementRef，因为仓库还没有“托管 SQL 仓库/审批版本表”；</p>
 * <p>3. SQL 必须是单条 SELECT/WITH 查询，不允许分号、注释和危险关键字；</p>
 * <p>4. 返回 SQL 指纹用于低敏聚合，SQL 正文只进入 internal run-once 请求，不进入公开响应或日志。</p>
 *
 * <p>这里不是 SQL 解析器的最终形态。生产增强方向应包括：真正的 SQL AST parser、托管 statementRef、
 * 参数 schema、Explain/row estimate、字段血缘、审批版本号和审计回放。</p>
 */
@Component
public class SyncCustomSqlExecutionContractSupport {

    private static final int MAX_INLINE_SQL_LENGTH = 8000;
    private static final Pattern SQL_DANGEROUS_TOKEN = Pattern.compile(
            "\\b(insert|update|delete|merge|drop|alter|create|truncate|grant|revoke|call|exec|execute|copy|load|replace)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * JSON 解析器。
     *
     * <p>customSqlConfig 后续可能扩展 params、statementRef、approvalId、resultSchema 等字段，
     * 因此这里使用 JsonNode 只读取执行 v1 必需的 sql 字段，未知字段不会被当成执行指令。</p>
     */
    private final ObjectMapper objectMapper;

    public SyncCustomSqlExecutionContractSupport() {
        this(new ObjectMapper());
    }

    public SyncCustomSqlExecutionContractSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析自定义 SQL 执行合同。
     *
     * @param syncMode 当前模板同步模式。只有 CUSTOM_SQL_QUERY 会进入 SQL 合同解析。
     * @param customSqlConfig 模板中的 customSqlConfig JSON。
     * @return 自定义 SQL 执行合同；不可执行时只返回 issueCode，不回显 SQL 正文。
     */
    public SyncCustomSqlExecutionContract parse(String syncMode, String customSqlConfig) {
        if (resolveMode(syncMode) != SyncMode.CUSTOM_SQL_QUERY) {
            return new SyncCustomSqlExecutionContract(true, null, null, List.of(), List.of());
        }
        List<String> issueCodes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!hasText(customSqlConfig)) {
            issueCodes.add("CUSTOM_SQL_CONFIG_REQUIRED");
            return contract(null, issueCodes, warnings);
        }
        JsonNode root = readJson(customSqlConfig, issueCodes);
        if (root == null) {
            return contract(null, issueCodes, warnings);
        }
        String sql = text(root, "sql");
        String statementRef = text(root, "statementRef");
        if (!hasText(sql)) {
            issueCodes.add(hasText(statementRef)
                    ? "CUSTOM_SQL_STATEMENT_REF_NOT_EXECUTABLE_BY_INLINE_RUNNER"
                    : "CUSTOM_SQL_QUERY_MISSING");
            warnings.add("当前闭环版本尚未接入托管 SQL 仓库，statementRef 只能用于规划和审批，真实搬运需提供受控内联只读 SQL");
            return contract(null, issueCodes, warnings);
        }
        String normalizedSql = normalizeSql(sql);
        if (!readOnlySql(normalizedSql)) {
            issueCodes.add("CUSTOM_SQL_RAW_SQL_UNSAFE");
            return contract(null, issueCodes, warnings);
        }
        if (normalizedSql.contains("?")) {
            issueCodes.add("CUSTOM_SQL_PARAMETERS_NOT_SUPPORTED_BY_V1_RUNNER");
            warnings.add("当前 v1 run-once 仅支持无参数只读 SQL；后续应通过 params schema 与 PreparedStatement 参数绑定补齐");
            return contract(null, issueCodes, warnings);
        }
        warnings.add("CUSTOM_SQL_INLINE_READ_ONLY_SQL_ACCEPTED_INTERNAL_ONLY");
        return contract(normalizedSql, issueCodes, warnings);
    }

    private SyncCustomSqlExecutionContract contract(String sql, List<String> issueCodes, List<String> warnings) {
        return new SyncCustomSqlExecutionContract(
                issueCodes.isEmpty(),
                sql,
                hasText(sql) ? fingerprint(sql) : null,
                List.copyOf(issueCodes),
                List.copyOf(warnings)
        );
    }

    private JsonNode readJson(String value, List<String> issueCodes) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            issueCodes.add("CUSTOM_SQL_JSON_INVALID");
            return null;
        }
    }

    private String normalizeSql(String sql) {
        return sql == null ? null : sql.strip().replaceAll("\\s+", " ");
    }

    private boolean readOnlySql(String sql) {
        if (!hasText(sql) || sql.length() > MAX_INLINE_SQL_LENGTH) {
            return false;
        }
        String normalized = sql.toLowerCase(Locale.ROOT);
        if (normalized.contains(";")
                || normalized.contains("--")
                || normalized.contains("/*")
                || normalized.contains("*/")) {
            return false;
        }
        if (!normalized.startsWith("select ") && !normalized.startsWith("with ")) {
            return false;
        }
        return !SQL_DANGEROUS_TOKEN.matcher(normalized).find();
    }

    private String fingerprint(String sql) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(sql.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception exception) {
            return "sha256-unavailable";
        }
    }

    private SyncMode resolveMode(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return SyncMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return hasText(text) ? text.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

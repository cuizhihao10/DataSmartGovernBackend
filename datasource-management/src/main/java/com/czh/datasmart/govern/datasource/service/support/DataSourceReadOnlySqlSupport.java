/**
 * @Author : Cui
 * @Date: 2026/05/05 19:15
 * @Description DataSmart Govern Backend - DataSourceReadOnlySqlSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.datasource.config.ReadOnlySqlAuditMaskingProperties;
import com.czh.datasmart.govern.datasource.config.ReadOnlySqlExecutionProperties;
import com.czh.datasmart.govern.datasource.controller.dto.ReadOnlySqlExecutionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.ReadOnlySqlExecutionResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.DataSourceReadOnlySqlExecutionAudit;
import com.czh.datasmart.govern.datasource.mapper.DataSourceReadOnlySqlExecutionAuditMapper;
import com.czh.datasmart.govern.datasource.support.DataSourceStatus;
import com.czh.datasmart.govern.datasource.support.DataSourceType;
import com.czh.datasmart.govern.datasource.support.SyncPermissionAction;
import com.czh.datasmart.govern.datasource.support.SyncPermissionEvaluator;
import com.czh.datasmart.govern.datasource.support.SyncPermissionResource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据源受控只读 SQL 执行支持组件。
 *
 * <p>该组件承载 datasource-management 中最敏感的一条能力线：在平台受控边界内读取客户源库数据。
 * 它从 `DataSourceManagementServiceImpl` 拆出，是为了避免数据源主服务同时承担：
 * 1. 数据源生命周期管理；
 * 2. 连接测试；
 * 3. 元数据发现；
 * 4. 只读 SQL 安全校验；
 * 5. JDBC 查询执行；
 * 6. 审计指纹与脱敏预览；
 * 7. 审计分页检索。
 *
 * <p>商业化产品里，这条能力必须独立看待，因为它触达真实业务数据：
 * 1. 权限必须比普通数据源查看更严格；
 * 2. SQL 必须被限制为短查询、只读查询；
 * 3. 返回行数和执行超时必须由服务端兜底；
 * 4. 审计必须保留可追踪性，同时不能泄露敏感字面量；
 * 5. 后续长查询、导出、分片扫描应走 task-management 异步任务，而不是 HTTP 同步接口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSourceReadOnlySqlSupport {

    /**
     * 只读 SQL 执行边界配置。
     */
    private final ReadOnlySqlExecutionProperties readOnlySqlExecutionProperties;

    /**
     * 只读 SQL 审计预览脱敏配置。
     */
    private final ReadOnlySqlAuditMaskingProperties readOnlySqlAuditMaskingProperties;

    /**
     * 本地权限评估器。
     */
    private final SyncPermissionEvaluator syncPermissionEvaluator;

    /**
     * 只读 SQL 执行审计 Mapper。
     */
    private final DataSourceReadOnlySqlExecutionAuditMapper readOnlySqlExecutionAuditMapper;

    /**
     * 只读 SQL 的危险关键字匹配。
     */
    private static final Pattern FORBIDDEN_SQL_KEYWORD_PATTERN = Pattern.compile(
            "(?i)\\b(insert|update|delete|drop|alter|truncate|create|replace|merge|call|exec|execute|grant|revoke|commit|rollback|use|show|describe|explain)\\b"
    );

    /**
     * 邮箱地址识别规则，用于审计预览脱敏。
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"
    );

    /**
     * 中国大陆手机号识别规则，用于审计预览脱敏。
     */
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    /**
     * 中国大陆居民身份证号识别规则，用于审计预览脱敏。
     */
    private static final Pattern IDENTITY_NUMBER_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9])\\d{17}[\\dXx](?![A-Za-z0-9])"
    );

    /**
     * 常见凭据字段赋值识别规则。
     */
    private static final Pattern CREDENTIAL_ASSIGNMENT_PATTERN = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|token|access_token|refresh_token|secret|api_key|apikey|authorization)\\b\\s*=\\s*('(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|[^\\s,)]+)"
    );

    /**
     * Bearer Token 识别规则。
     */
    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+"
    );

    /**
     * 单引号字符串字面量识别规则。
     */
    private static final Pattern SINGLE_QUOTED_LITERAL_PATTERN = Pattern.compile("'((?:''|[^'])*)'");

    /**
     * 执行受控只读 SQL。
     *
     * <p>该方法会把一次查询拆成几个阶段：
     * 1. 校验模块开关和数据源生命周期；
     * 2. 校验角色权限；
     * 3. 校验 SQL 形态，只接受单条 SELECT；
     * 4. 应用服务端行数和超时上限；
     * 5. 使用只读 JDBC 连接执行；
     * 6. 写入 best-effort 审计记录。
     */
    public ReadOnlySqlExecutionResult executeReadOnlySql(DataSourceConfig config, ReadOnlySqlExecutionRequest request) {
        if (!Boolean.TRUE.equals(readOnlySqlExecutionProperties.getEnabled())) {
            throw new IllegalStateException("受控只读 SQL 执行能力未启用，请检查 datasmart.datasource.read-only-sql.enabled 配置");
        }

        LocalDateTime executedAt = LocalDateTime.now();
        long startTime = System.currentTimeMillis();
        DataSourceType type = null;
        String safeSql = request.getSql();
        int maxRows = 0;
        int queryTimeoutSeconds = 0;
        try {
            ensureActive(config);
            type = DataSourceType.fromValue(config.getType());
            if (!type.isCanRead()) {
                throw new IllegalStateException("当前数据源类型不支持读取: " + type.name());
            }
            if (request.getSql() == null || request.getSql().isBlank()) {
                throw new IllegalArgumentException("SQL 不能为空");
            }
            if (request.getActorRole() == null || request.getActorRole().isBlank()) {
                throw new IllegalArgumentException("操作者角色不能为空，请通过请求体 actorRole 或 X-DataSmart-Actor-Role Header 传入");
            }

            syncPermissionEvaluator.assertAllowed(request.getActorRole(),
                    SyncPermissionResource.DATASOURCE_READONLY_QUERY,
                    SyncPermissionAction.EXECUTE_READ_ONLY_QUERY);

            safeSql = validateAndNormalizeReadOnlySql(request.getSql());
            maxRows = applyPositiveBoundedValue(
                    request.getMaxRows(),
                    readOnlySqlExecutionProperties.getDefaultMaxRows(),
                    readOnlySqlExecutionProperties.getAbsoluteMaxRows());
            queryTimeoutSeconds = applyPositiveBoundedValue(
                    request.getQueryTimeoutSeconds(),
                    readOnlySqlExecutionProperties.getDefaultQueryTimeoutSeconds(),
                    readOnlySqlExecutionProperties.getAbsoluteQueryTimeoutSeconds());
            String boundedSql = buildReadOnlyBoundedSql(type, safeSql, maxRows);

            List<String> warnings = new ArrayList<>();
            warnings.add("当前接口仅用于平台内部短查询、质量扫描统计和异常样本预览，不应用作大规模数据导出通道");
            warnings.add("服务端已对 SQL 二次包裹并强制应用最大返回行数: " + maxRows);
            warnings.add("结果值已统一转换为字符串或 null，避免不同 JDBC 驱动对象影响跨服务 JSON 契约");

            try (Connection connection = openConnection(config);
                 PreparedStatement preparedStatement = connection.prepareStatement(boundedSql)) {
                connection.setReadOnly(true);
                preparedStatement.setQueryTimeout(queryTimeoutSeconds);
                preparedStatement.setMaxRows(maxRows);

                List<String> columns = new ArrayList<>();
                List<Map<String, Object>> rows = new ArrayList<>();
                try (ResultSet resultSet = preparedStatement.executeQuery()) {
                    ResultSetMetaData metaData = resultSet.getMetaData();
                    int columnCount = metaData.getColumnCount();
                    for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++) {
                        columns.add(metaData.getColumnLabel(columnIndex));
                    }

                    while (resultSet.next() && rows.size() < maxRows) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int columnIndex = 1; columnIndex <= columnCount; columnIndex++) {
                            row.put(columns.get(columnIndex - 1), normalizeReadOnlySqlCellValue(resultSet.getObject(columnIndex)));
                        }
                        rows.add(row);
                    }

                    long durationMs = System.currentTimeMillis() - startTime;
                    recordReadOnlySqlExecutionAudit(config, type, request, safeSql, maxRows, queryTimeoutSeconds,
                            rows.size(), columnCount, durationMs, "SUCCESS", null, executedAt);

                    return new ReadOnlySqlExecutionResult(
                            config.getId(),
                            config.getName(),
                            type.name(),
                            request.getPurpose(),
                            true,
                            rows.size(),
                            columnCount,
                            maxRows,
                            queryTimeoutSeconds,
                            durationMs,
                            columns,
                            rows,
                            warnings,
                            LocalDateTime.now(),
                            "受控只读 SQL 执行成功"
                    );
                }
            }
        } catch (ClassNotFoundException | SQLException exception) {
            String message = "受控只读 SQL 执行失败: " + truncateMessage(exception.getMessage());
            recordReadOnlySqlExecutionAudit(config, type, request, safeSql, maxRows, queryTimeoutSeconds,
                    0, 0, System.currentTimeMillis() - startTime, "FAILED", message, executedAt);
            throw new IllegalStateException(message, exception);
        } catch (RuntimeException exception) {
            String message = truncateMessage(exception.getMessage());
            recordReadOnlySqlExecutionAudit(config, type, request, safeSql, maxRows, queryTimeoutSeconds,
                    0, 0, System.currentTimeMillis() - startTime, "FAILED", message, executedAt);
            throw exception;
        }
    }

    /**
     * 分页查询受控只读 SQL 执行审计。
     */
    public IPage<DataSourceReadOnlySqlExecutionAudit> pageReadOnlySqlExecutionAudits(
            Integer current,
            Integer size,
            Long datasourceId,
            String purpose,
            String actorRole,
            Long actorTenantId,
            DatasourceProjectVisibility visibility,
            String executionStatus,
            String sqlFingerprint,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String queryActorRole) {
        if (queryActorRole == null || queryActorRole.isBlank()) {
            throw new IllegalArgumentException("查询审计必须提供 queryActorRole 或 X-DataSmart-Actor-Role Header");
        }
        syncPermissionEvaluator.assertAllowed(queryActorRole,
                SyncPermissionResource.SYNC_PERMISSION_POLICY,
                SyncPermissionAction.VIEW_POLICY);

        if (visibility != null && visibility.projectScopeEnforced() && visibility.authorizedProjectIds().isEmpty()) {
            return new Page<>(safePage(current), safeSize(size));
        }

        LambdaQueryWrapper<DataSourceReadOnlySqlExecutionAudit> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(datasourceId != null, DataSourceReadOnlySqlExecutionAudit::getDatasourceId, datasourceId)
                .eq(hasText(purpose), DataSourceReadOnlySqlExecutionAudit::getPurpose, normalizeUpper(purpose))
                .eq(hasText(actorRole), DataSourceReadOnlySqlExecutionAudit::getActorRole, normalizeUpper(actorRole))
                .eq(actorTenantId != null, DataSourceReadOnlySqlExecutionAudit::getActorTenantId, actorTenantId)
                .eq(visibility != null && visibility.requestedProjectId() != null,
                        DataSourceReadOnlySqlExecutionAudit::getDatasourceProjectId,
                        visibility == null ? null : visibility.requestedProjectId())
                .eq(visibility != null && visibility.requestedWorkspaceId() != null,
                        DataSourceReadOnlySqlExecutionAudit::getDatasourceWorkspaceId,
                        visibility == null ? null : visibility.requestedWorkspaceId())
                .eq(hasText(executionStatus), DataSourceReadOnlySqlExecutionAudit::getExecutionStatus, normalizeUpper(executionStatus))
                .eq(hasText(sqlFingerprint), DataSourceReadOnlySqlExecutionAudit::getSqlFingerprint, sqlFingerprint)
                .ge(startTime != null, DataSourceReadOnlySqlExecutionAudit::getExecutedAt, startTime)
                .le(endTime != null, DataSourceReadOnlySqlExecutionAudit::getExecutedAt, endTime)
                .orderByDesc(DataSourceReadOnlySqlExecutionAudit::getExecutedAt);
        if (visibility != null && visibility.projectScopeEnforced()) {
            wrapper.in(DataSourceReadOnlySqlExecutionAudit::getDatasourceProjectId, visibility.authorizedProjectIds());
        }
        return readOnlySqlExecutionAuditMapper.selectPage(new Page<>(safePage(current), safeSize(size)), wrapper);
    }

    private void recordReadOnlySqlExecutionAudit(DataSourceConfig config,
                                                 DataSourceType dataSourceType,
                                                 ReadOnlySqlExecutionRequest request,
                                                 String sql,
                                                 Integer appliedMaxRows,
                                                 Integer appliedQueryTimeoutSeconds,
                                                 Integer returnedRowCount,
                                                 Integer columnCount,
                                                 Long durationMs,
                                                 String executionStatus,
                                                 String failureMessage,
                                                 LocalDateTime executedAt) {
        try {
            DataSourceReadOnlySqlExecutionAudit audit = new DataSourceReadOnlySqlExecutionAudit();
            audit.setDatasourceId(config.getId());
            audit.setDatasourceName(config.getName());
            audit.setDatasourceType(dataSourceType == null ? config.getType() : dataSourceType.name());
            audit.setDatasourceTenantId(config.getTenantId());
            audit.setDatasourceProjectId(config.getProjectId());
            audit.setDatasourceWorkspaceId(config.getWorkspaceId());
            audit.setPurpose(request.getPurpose());
            audit.setActorTenantId(request.getActorTenantId());
            audit.setActorId(request.getActorId());
            audit.setActorRole(normalizeUpper(request.getActorRole()));
            audit.setActorType(normalizeUpper(request.getActorType()));
            audit.setSourceService(request.getSourceService());
            audit.setTraceId(request.getTraceId());
            audit.setSqlFingerprint(sha256Hex(sql));
            audit.setSqlPreview(truncate(maskReadOnlySqlAuditPreview(sql), 1000));
            audit.setRequestedMaxRows(request.getMaxRows());
            audit.setAppliedMaxRows(appliedMaxRows);
            audit.setRequestedQueryTimeoutSeconds(request.getQueryTimeoutSeconds());
            audit.setAppliedQueryTimeoutSeconds(appliedQueryTimeoutSeconds);
            audit.setReturnedRowCount(returnedRowCount);
            audit.setColumnCount(columnCount);
            audit.setDurationMs(durationMs);
            audit.setExecutionStatus(executionStatus);
            audit.setFailureMessage(truncate(failureMessage, 1000));
            audit.setExecutedAt(executedAt);
            audit.setCreateTime(LocalDateTime.now());
            readOnlySqlExecutionAuditMapper.insert(audit);
        } catch (Exception auditException) {
            log.warn("记录受控只读 SQL 执行审计失败，datasourceId={}, purpose={}, status={}",
                    config.getId(),
                    request.getPurpose(), executionStatus, auditException);
        }
    }

    private void ensureActive(DataSourceConfig config) {
        if (DataSourceStatus.DELETED.equals(config.getStatus())) {
            throw new IllegalStateException("数据源已删除: " + config.getId());
        }
        if (!DataSourceStatus.ACTIVE.equals(config.getStatus())) {
            throw new IllegalStateException("数据源未启用，不能执行只读 SQL: " + config.getId());
        }
    }

    private Connection openConnection(DataSourceConfig config) throws SQLException, ClassNotFoundException {
        Class.forName(config.getDriverClassName());
        DriverManager.setLoginTimeout(5);
        Connection connection = DriverManager.getConnection(config.getJdbcUrl(), config.getUsername(), config.getPassword());
        connection.setReadOnly(true);
        return connection;
    }

    private int applyPositiveBoundedValue(Integer requestedValue, Integer defaultValue, Integer absoluteMaxValue) {
        int value = requestedValue == null ? defaultValue : requestedValue;
        return Math.max(1, Math.min(value, absoluteMaxValue));
    }

    private String validateAndNormalizeReadOnlySql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        String normalizedSql = sql.trim();
        String lowerCaseSql = normalizedSql.toLowerCase();
        if (!lowerCaseSql.startsWith("select ")) {
            throw new IllegalArgumentException("当前只允许执行单条 SELECT 查询");
        }
        if (normalizedSql.contains(";")) {
            throw new IllegalArgumentException("只读 SQL 不允许包含分号或多语句");
        }
        if (normalizedSql.contains("--") || normalizedSql.contains("/*") || normalizedSql.contains("*/")) {
            throw new IllegalArgumentException("只读 SQL 不允许包含注释片段");
        }
        if (FORBIDDEN_SQL_KEYWORD_PATTERN.matcher(normalizedSql).find()) {
            throw new IllegalArgumentException("只读 SQL 包含高风险关键字，请改为纯 SELECT 查询");
        }
        return normalizedSql;
    }

    private String buildReadOnlyBoundedSql(DataSourceType dataSourceType, String sql, int maxRows) {
        if (dataSourceType == DataSourceType.SQLSERVER) {
            return "SELECT TOP (" + maxRows + ") * FROM (" + sql + ") datasmart_safe_query";
        }
        return "SELECT * FROM (" + sql + ") datasmart_safe_query LIMIT " + maxRows;
    }

    private Object normalizeReadOnlySqlCellValue(Object value) {
        if (value == null) { return null; }
        if (value instanceof byte[] bytes) {
            return "[binary:" + bytes.length + " bytes]";
        }
        return String.valueOf(value);
    }

    private String sha256Hex(String value) {
        if (value == null) { return null; }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256 摘要算法", exception);
        }
    }

    private String maskReadOnlySqlAuditPreview(String sql) {
        if (sql == null || !Boolean.TRUE.equals(readOnlySqlAuditMaskingProperties.getEnabled())) {
            return sql;
        }

        String maskedSql = sql;
        if (Boolean.TRUE.equals(readOnlySqlAuditMaskingProperties.getMaskCredentialAssignments())) {
            maskedSql = maskCredentialAssignments(maskedSql);
        }
        if (Boolean.TRUE.equals(readOnlySqlAuditMaskingProperties.getMaskBearerToken())) {
            maskedSql = BEARER_TOKEN_PATTERN.matcher(maskedSql).replaceAll("[MASKED_BEARER_TOKEN]");
        }
        if (Boolean.TRUE.equals(readOnlySqlAuditMaskingProperties.getMaskEmail())) {
            maskedSql = EMAIL_PATTERN.matcher(maskedSql).replaceAll("[MASKED_EMAIL]");
        }
        if (Boolean.TRUE.equals(readOnlySqlAuditMaskingProperties.getMaskPhone())) {
            maskedSql = PHONE_PATTERN.matcher(maskedSql).replaceAll("[MASKED_PHONE]");
        }
        if (Boolean.TRUE.equals(readOnlySqlAuditMaskingProperties.getMaskIdentityNumber())) {
            maskedSql = IDENTITY_NUMBER_PATTERN.matcher(maskedSql).replaceAll("[MASKED_IDENTITY_NUMBER]");
        }
        if (Boolean.TRUE.equals(readOnlySqlAuditMaskingProperties.getMaskQuotedLongText())) {
            maskedSql = maskLongQuotedLiterals(maskedSql);
        }
        return maskedSql;
    }

    private String maskCredentialAssignments(String sql) {
        Matcher matcher = CREDENTIAL_ASSIGNMENT_PATTERN.matcher(sql);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = matcher.group(1) + " = [MASKED_CREDENTIAL]";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String maskLongQuotedLiterals(String sql) {
        Integer configuredThreshold = readOnlySqlAuditMaskingProperties.getMaxQuotedLiteralPreviewLength();
        int threshold = Math.max(1, configuredThreshold == null ? 24 : configuredThreshold);
        Matcher matcher = SINGLE_QUOTED_LITERAL_PATTERN.matcher(sql);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String literal = matcher.group(1);
            if (literal.length() <= threshold) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String replacement = "'[MASKED_LITERAL:" + literal.length() + "]'";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String truncateMessage(String message) {
        if (message == null || message.isBlank()) { return "未知连接错误"; }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) { return value; }
        return value.substring(0, maxLength);
    }
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
    private String normalizeUpper(String value) {
        return hasText(value) ? value.trim().toUpperCase() : value;
    }

    private long safePage(Integer current) {
        return current == null || current <= 0 ? 1L : current.longValue();
    }

    private long safeSize(Integer size) {
        if (size == null || size <= 0) { return 10L; }
        return Math.min(size, 200);
    }
}

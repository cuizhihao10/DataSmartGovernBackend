/**
 * @Author : Cui
 * @Date: 2026/07/08 16:45
 * @Description DataSmart Govern Backend - SyncTaskCustomSqlCheckSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.config.DataSyncDatasourceRunOnceProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskCustomSqlCheckRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskCustomSqlCheckResponse;
import com.czh.datasmart.govern.datasync.integration.datasource.sql.DatasourceReadOnlySqlClient;
import com.czh.datasmart.govern.datasync.integration.datasource.sql.DatasourceReadOnlySqlRequest;
import com.czh.datasmart.govern.datasync.integration.datasource.sql.DatasourceReadOnlySqlResponse;
import com.czh.datasmart.govern.datasync.support.SyncMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL 语句模式创建向导检查支持组件。
 *
 * <p>该组件是 {@code CUSTOM_SQL_QUERY / SQL语句} 模式在“创建任务页面”里的控制面门禁。
 * 它不保存 SQL、不创建模板、不触发 worker、不写目标端，而是完成三件事：</p>
 * <p>1. 本地静态检查：快速拦截空 SQL、多语句、注释、DDL/DML/存储过程等明显危险输入；</p>
 * <p>2. 远程只读探测：在静态检查通过后，委托 datasource-management 使用受控只读连接执行极小行数查询，
 * 验证语法、源端表/字段存在性，并拿到 ResultSet 输出列名；</p>
 * <p>3. 字段映射准备：把 SQL 输出列转换成前端可编辑字段映射表的初始提示，尤其支持 SQL alias 作为源端字段名。</p>
 *
 * <p>为什么这段逻辑放在 data-sync：</p>
 * <p>data-sync 是同步任务创建向导和执行编排的所有者，它知道“SQL 语句模式”在产品流程里处于哪一步；
 * 但它不应该拥有数据源连接和 SQL 执行能力，所以真正的 JDBC 查询仍在 datasource-management 内完成。
 * 这种拆法可以让创建向导体验闭环，同时保持微服务职责清晰。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncTaskCustomSqlCheckSupport {

    /**
     * 创建向导 SQL 最大长度。
     *
     * <p>这个值不是数据库 SQL 长度上限，而是产品侧同步配置的安全上限。
     * 过长 SQL 往往意味着用户把复杂 ETL 脚本塞进了“SQL 语句传输”模式；后续应引导到 ETL 开发、
     * 物化视图、存储过程托管或专用离线作业，而不是让创建向导承载不可维护的大脚本。</p>
     */
    private static final int MAX_SQL_LENGTH = 20_000;

    /**
     * 创建向导列探测默认行数。
     *
     * <p>只需要 ResultSetMetaData 时，1 行足够。即使 SQL 实际没有数据，JDBC 仍能返回列定义。</p>
     */
    private static final int DEFAULT_COLUMN_PROBE_ROWS = 1;

    /**
     * 创建向导列探测最大行数。
     *
     * <p>保留这个上限是为了兼容未来“同时做极小样本校验”的场景；当前响应不会返回 rows。</p>
     */
    private static final int MAX_COLUMN_PROBE_ROWS = 5;

    /**
     * 远程 SQL 探测超时秒数。
     *
     * <p>创建向导是用户同步交互，超时应该比真正数据同步短得多。真实大数据量搬运必须走 worker 和异步 execution。</p>
     */
    private static final int QUERY_TIMEOUT_SECONDS = 10;

    /**
     * 允许的只读 SQL 起始关键字。
     *
     * <p>支持 SELECT 和 WITH。WITH 常用于 CTE，可以让用户把复杂查询拆成更可读的公共表表达式；
     * 但它仍必须最终是只读查询，不能包含 DDL/DML/过程调用。</p>
     */
    private static final Pattern READ_ONLY_SQL_START_PATTERN = Pattern.compile("(?is)^\\s*(select|with)\\b");

    /**
     * 高风险 SQL 关键字。
     *
     * <p>这里采用保守策略：即使某些词出现在字符串字面量里也会被拦截。
     * 对创建向导来说，误杀比误放更安全；真正需要复杂脚本的场景应进入更严格的审批和托管流程。</p>
     */
    private static final Pattern FORBIDDEN_SQL_KEYWORD_PATTERN = Pattern.compile(
            "(?i)\\b(insert|update|delete|drop|alter|truncate|create|replace|merge|call|exec|execute|grant|revoke|commit|rollback|use|show|describe|explain)\\b"
    );

    private final DatasourceReadOnlySqlClient readOnlySqlClient;
    private final DataSyncDatasourceRunOnceProperties properties;

    /**
     * 执行 SQL 语句模式检查。
     *
     * @param request 前端提交的 SQL 检查请求
     * @param actorContext 当前用户/服务调用上下文
     * @return 可被创建向导消费的低敏检查结果
     */
    public SyncTaskCustomSqlCheckResponse check(SyncTaskCustomSqlCheckRequest request, SyncActorContext actorContext) {
        List<String> blocking = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> recommendedActions = new ArrayList<>();
        String sql = request == null ? null : request.getSql();
        String normalizedSql = normalizeSql(sql);

        validateRequestShape(request, normalizedSql, blocking, warnings, recommendedActions);
        boolean staticSafe = blocking.isEmpty();
        String fingerprint = hasText(normalizedSql) ? sha256Hex(normalizedSql) : null;

        if (!staticSafe) {
            return response(false, false, false, request, fingerprint, null,
                    List.of(), List.of(), blocking, warnings, recommendedActions);
        }

        if (Boolean.TRUE.equals(request.getSkipRemoteProbe())) {
            recommendedActions.add("当前只完成静态检查；最终保存或进入字段映射前，请再次调用本接口并启用远程探测，确认 SQL 在源端真实可执行");
            return response(true, true, false, request, fingerprint, null,
                    List.of(), List.of(), blocking, warnings, recommendedActions);
        }

        try {
            DatasourceReadOnlySqlResponse remote = readOnlySqlClient.execute(
                    request.getSourceDatasourceId(),
                    buildRemoteRequest(request, normalizedSql, actorContext),
                    actorContext);
            List<SyncTaskCustomSqlCheckResponse.SqlOutputColumn> outputColumns = buildOutputColumns(normalizedSql, remote);
            List<SyncTaskCustomSqlCheckResponse.FieldMappingHint> hints = buildFieldMappingHints(outputColumns);
            validateRemoteColumns(outputColumns, blocking, warnings, recommendedActions);
            collectRemoteWarnings(remote, warnings);
            recommendedActions.add("请基于 SQL 输出列或别名生成字段映射；如果使用了别名，字段映射源端字段应使用别名而不是原始表字段名");
            if (!hasText(request.getTargetObjectName())) {
                warnings.add("当前请求未携带目标对象名；SQL 本身可以检查通过，但字段映射和目标表约束仍需要在对象映射/预检查阶段确认");
            }
            boolean passed = blocking.isEmpty();
            return response(passed, true, true, request, fingerprint, remote,
                    outputColumns, hints, blocking, warnings, recommendedActions);
        } catch (RuntimeException exception) {
            blocking.add("SQL 在源端受控只读探测失败：可能是语法错误、表/字段不存在、权限不足、查询超时或 datasource-management 暂不可用");
            recommendedActions.add("请先确认 SQL 是单条 SELECT/WITH 查询，并确认引用的库、schema、表和字段在源端数据源中真实存在");
            return response(false, true, false, request, fingerprint, null,
                    List.of(), List.of(), blocking, warnings, recommendedActions);
        }
    }

    /**
     * 校验请求基本形态。
     *
     * <p>这些检查不访问真实数据源，适合在用户输入过程中快速返回。
     * 远程探测前先做静态门禁，可以减少无意义数据库访问，也能在危险 SQL 到达 datasource-management 前就 fail-fast。</p>
     */
    private void validateRequestShape(SyncTaskCustomSqlCheckRequest request,
                                      String normalizedSql,
                                      List<String> blocking,
                                      List<String> warnings,
                                      List<String> recommendedActions) {
        if (request == null) {
            blocking.add("SQL 检查请求体不能为空");
            return;
        }
        if (hasText(request.getSyncMode()) && resolveMode(request.getSyncMode()) != SyncMode.CUSTOM_SQL_QUERY) {
            blocking.add("SQL 检查入口仅适用于 CUSTOM_SQL_QUERY / SQL语句 模式");
        }
        if (request.getSourceDatasourceId() == null) {
            blocking.add("SQL 检查必须提供源端数据源 ID");
        }
        if (!hasText(normalizedSql)) {
            blocking.add("SQL 不能为空");
            return;
        }
        if (normalizedSql.length() > MAX_SQL_LENGTH) {
            blocking.add("SQL 长度超过 " + MAX_SQL_LENGTH + " 字符，请拆分为更小的查询或改用专用 ETL/离线作业流程");
        }
        if (!READ_ONLY_SQL_START_PATTERN.matcher(normalizedSql).find()) {
            blocking.add("SQL 语句模式只允许单条 SELECT 或 WITH 只读查询");
        }
        if (normalizedSql.contains(";")) {
            blocking.add("SQL 不允许包含分号或多语句；请只提交一条完整 SELECT/WITH 查询");
        }
        if (normalizedSql.contains("--") || normalizedSql.contains("/*") || normalizedSql.contains("*/")) {
            blocking.add("SQL 不允许包含注释片段，避免通过注释绕过只读门禁或隐藏多语句意图");
        }
        if (FORBIDDEN_SQL_KEYWORD_PATTERN.matcher(normalizedSql).find()) {
            blocking.add("SQL 包含 DDL/DML/过程调用/会话切换等高风险关键字，请改为纯只读查询");
        }
        if (request.getTargetDatasourceId() == null) {
            warnings.add("未携带目标端数据源 ID；本次可检查源端 SQL，但后续预检查仍需验证目标表和字段映射");
        }
        recommendedActions.add("SQL 自定义传输建议为每个输出表达式设置清晰别名，例如 select user_id as user_id, amount as order_amount");
    }

    /**
     * 构造 datasource-management 只读 SQL 请求。
     */
    private DatasourceReadOnlySqlRequest buildRemoteRequest(SyncTaskCustomSqlCheckRequest request,
                                                            String normalizedSql,
                                                            SyncActorContext actorContext) {
        DatasourceReadOnlySqlRequest remoteRequest = new DatasourceReadOnlySqlRequest();
        remoteRequest.setSql(normalizedSql);
        remoteRequest.setMaxRows(boundProbeRows(request.getMaxRowsForColumnProbe()));
        remoteRequest.setQueryTimeoutSeconds(QUERY_TIMEOUT_SECONDS);
        remoteRequest.setPurpose("DATA_SYNC_CUSTOM_SQL_CREATE_WIZARD_CHECK");
        remoteRequest.setActorTenantId(actorContext == null ? null : actorContext.tenantId());
        remoteRequest.setActorId(actorContext == null ? null : actorContext.actorId());
        remoteRequest.setActorRole(properties.getActorRole());
        remoteRequest.setActorType("SERVICE_ACCOUNT");
        remoteRequest.setSourceService(properties.getSourceService());
        remoteRequest.setTraceId(traceId(actorContext));
        return remoteRequest;
    }

    /**
     * 从远程探测结果提取输出列。
     */
    private List<SyncTaskCustomSqlCheckResponse.SqlOutputColumn> buildOutputColumns(String normalizedSql,
                                                                                    DatasourceReadOnlySqlResponse remote) {
        if (remote == null || remote.getColumns() == null || remote.getColumns().isEmpty()) {
            return List.of();
        }
        List<SyncTaskCustomSqlCheckResponse.SqlOutputColumn> columns = new ArrayList<>();
        for (int index = 0; index < remote.getColumns().size(); index++) {
            String columnName = remote.getColumns().get(index);
            columns.add(new SyncTaskCustomSqlCheckResponse.SqlOutputColumn(
                    index + 1,
                    columnName,
                    null,
                    containsAliasFor(normalizedSql, columnName),
                    "SQL 输出列来自源端 ResultSetMetaData；字段映射应以该列名或 alias 作为源端字段"));
        }
        return List.copyOf(columns);
    }

    /**
     * 为前端生成字段映射初始提示。
     */
    private List<SyncTaskCustomSqlCheckResponse.FieldMappingHint> buildFieldMappingHints(
            List<SyncTaskCustomSqlCheckResponse.SqlOutputColumn> outputColumns) {
        if (outputColumns == null || outputColumns.isEmpty()) {
            return List.of();
        }
        return outputColumns.stream()
                .map(column -> new SyncTaskCustomSqlCheckResponse.FieldMappingHint(
                        column.columnName(),
                        column.columnName(),
                        true,
                        "默认按 SQL 输出列名/别名寻找目标端同名字段；最终是否同步仍由用户在字段映射表中确认"))
                .toList();
    }

    /**
     * 校验远程返回的输出列是否适合进入字段映射。
     */
    private void validateRemoteColumns(List<SyncTaskCustomSqlCheckResponse.SqlOutputColumn> outputColumns,
                                       List<String> blocking,
                                       List<String> warnings,
                                       List<String> recommendedActions) {
        if (outputColumns == null || outputColumns.isEmpty()) {
            blocking.add("SQL 探测未返回任何输出列，无法生成字段映射");
            return;
        }
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new HashSet<>();
        for (SyncTaskCustomSqlCheckResponse.SqlOutputColumn column : outputColumns) {
            String key = column.columnName() == null ? "" : column.columnName().trim().toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                duplicates.add(column.columnName());
            }
        }
        if (!duplicates.isEmpty()) {
            blocking.add("SQL 输出列存在重复列名或重复别名，字段映射会产生歧义: " + duplicates);
            recommendedActions.add("请为重复表达式补充唯一 alias，例如 count(*) as order_count、sum(amount) as total_amount");
        }
        if (outputColumns.size() > 200) {
            warnings.add("SQL 输出列数量较多，建议确认是否需要全部同步；字段过宽会增加目标表写入成本和后续维护成本");
        }
    }

    /**
     * 收集 datasource-management 返回的低敏 warning。
     */
    private void collectRemoteWarnings(DatasourceReadOnlySqlResponse remote, List<String> warnings) {
        if (remote == null || remote.getWarnings() == null || remote.getWarnings().isEmpty()) {
            return;
        }
        warnings.addAll(remote.getWarnings().stream()
                .filter(this::hasText)
                .map(item -> "datasource-management: " + item)
                .toList());
    }

    private SyncTaskCustomSqlCheckResponse response(boolean passed,
                                                   boolean staticSafe,
                                                   boolean remoteProbeExecuted,
                                                   SyncTaskCustomSqlCheckRequest request,
                                                   String fingerprint,
                                                   DatasourceReadOnlySqlResponse remote,
                                                   List<SyncTaskCustomSqlCheckResponse.SqlOutputColumn> outputColumns,
                                                   List<SyncTaskCustomSqlCheckResponse.FieldMappingHint> hints,
                                                   List<String> blocking,
                                                   List<String> warnings,
                                                   List<String> recommendedActions) {
        return new SyncTaskCustomSqlCheckResponse(
                passed,
                staticSafe,
                remoteProbeExecuted,
                request == null ? null : request.getSourceDatasourceId(),
                request == null ? null : request.getTargetDatasourceId(),
                request == null ? null : request.getTargetSchemaName(),
                request == null ? null : request.getTargetObjectName(),
                fingerprint,
                outputColumns == null ? 0 : outputColumns.size(),
                remote == null ? null : remote.getReturnedRowCount(),
                remote == null ? null : remote.getDurationMs(),
                outputColumns == null ? List.of() : List.copyOf(outputColumns),
                hints == null ? List.of() : List.copyOf(hints),
                List.copyOf(blocking),
                List.copyOf(warnings),
                List.copyOf(recommendedActions));
    }

    private SyncMode resolveMode(String syncMode) {
        try {
            return SyncMode.valueOf(syncMode.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private int boundProbeRows(Integer requestedRows) {
        int value = requestedRows == null ? DEFAULT_COLUMN_PROBE_ROWS : requestedRows;
        return Math.max(1, Math.min(value, MAX_COLUMN_PROBE_ROWS));
    }

    private String normalizeSql(String sql) {
        return sql == null ? null : sql.strip();
    }

    private boolean containsAliasFor(String sql, String columnName) {
        if (!hasText(sql) || !hasText(columnName)) {
            return false;
        }
        Pattern aliasPattern = Pattern.compile("(?i)\\bas\\s+[`\"\\[]?" + Pattern.quote(columnName) + "[`\"\\]]?\\b");
        return aliasPattern.matcher(sql).find();
    }

    private String traceId(SyncActorContext actorContext) {
        if (actorContext == null || actorContext.traceId() == null || actorContext.traceId().isBlank()) {
            return "data-sync-custom-sql-check";
        }
        return actorContext.traceId();
    }

    private String sha256Hex(String value) {
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

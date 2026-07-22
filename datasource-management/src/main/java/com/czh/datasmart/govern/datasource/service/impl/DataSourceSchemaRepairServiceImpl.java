package com.czh.datasmart.govern.datasource.service.impl;

import com.czh.datasmart.govern.datasource.controller.dto.DataSourceSchemaRepairApplyRequest;
import com.czh.datasmart.govern.datasource.controller.dto.DataSourceSchemaRepairPreviewRequest;
import com.czh.datasmart.govern.datasource.controller.dto.DataSourceSchemaRepairResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.DataSourceSchemaRepairPlan;
import com.czh.datasmart.govern.datasource.mapper.DataSourceSchemaRepairPlanMapper;
import com.czh.datasmart.govern.datasource.service.DataSourceSchemaRepairService;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncJdbcConnectionProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Applies only a small allow-list of reversible or monotonic schema changes.
 * The model never supplies executable SQL; SQL is generated after metadata is revalidated.
 */
@Service
@RequiredArgsConstructor
public class DataSourceSchemaRepairServiceImpl implements DataSourceSchemaRepairService {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[\\p{L}_][\\p{L}\\p{N}_$]*$");
    private static final int MAX_VARCHAR_LENGTH = 65_535;
    private static final int STATEMENT_TIMEOUT_SECONDS = 30;

    private final SyncJdbcConnectionProvider connectionProvider;
    private final DataSourceSchemaRepairPlanMapper planMapper;

    @Override
    @Transactional
    public DataSourceSchemaRepairResult preview(DataSourceConfig datasource,
                                                DataSourceSchemaRepairPreviewRequest request,
                                                Long actorId) {
        requireDatasource(datasource);
        RepairOperation operation = RepairOperation.from(request.getOperation());
        String tableName = requireIdentifier(request.getTableName(), "tableName");
        String columnName = requireIdentifier(request.getColumnName(), "columnName");
        String requestedType = normalizeRequestedType(operation, request.getRequestedType());
        Integer requestedLength = normalizeRequestedLength(operation, request.getRequestedLength());

        try (Connection connection = connectionProvider.openConnection(datasource.getId(), false)) {
            DatabaseFamily family = DatabaseFamily.from(connection.getMetaData().getDatabaseProductName());
            TableLocation location = resolveTable(connection, family, request.getSchemaName(), tableName);
            ColumnSnapshot current = findColumn(connection.getMetaData(), location, columnName);
            validateRepair(operation, family, current, requestedType, requestedLength);

            String metadataDigest = metadataDigest(location, columnName, current);
            String planRef = UUID.randomUUID().toString();
            String impactSummary = impactSummary(operation, location, columnName, current,
                    requestedType, requestedLength);
            String confirmationDigest = digest(String.join("|",
                    planRef,
                    String.valueOf(datasource.getTenantId()),
                    String.valueOf(datasource.getProjectId()),
                    String.valueOf(datasource.getId()),
                    operation.name(),
                    location.displayName(),
                    columnName,
                    metadataDigest,
                    requestedDefinition(operation, requestedType, requestedLength)));

            DataSourceSchemaRepairPlan plan = new DataSourceSchemaRepairPlan();
            plan.setPlanRef(planRef);
            plan.setTenantId(datasource.getTenantId());
            plan.setProjectId(datasource.getProjectId());
            plan.setDatasourceId(datasource.getId());
            plan.setDatasourceType(datasource.getType());
            plan.setOperation(operation.name());
            plan.setSchemaName(location.namespace());
            plan.setTableName(location.tableName());
            plan.setColumnName(columnName);
            plan.setCurrentType(current == null ? null : current.typeName());
            plan.setCurrentLength(current == null ? null : current.columnSize());
            plan.setCurrentNullable(current == null ? null : current.nullable());
            plan.setRequestedType(requestedType);
            plan.setRequestedLength(requestedLength);
            plan.setMetadataDigest(metadataDigest);
            plan.setImpactSummary(impactSummary);
            plan.setConfirmationDigest(confirmationDigest);
            plan.setPlanStatus(PlanStatus.PREVIEWED.name());
            plan.setCreatedBy(requireActor(actorId));
            plan.setCreateTime(LocalDateTime.now());
            plan.setUpdateTime(plan.getCreateTime());
            planMapper.insert(plan);
            return toResult(plan, current, true);
        } catch (SQLException | ClassNotFoundException exception) {
            throw new IllegalStateException("无法读取目标数据源元数据，未生成结构修复计划: "
                    + safeDatabaseFailure(exception), exception);
        }
    }

    @Override
    // Preserve STALE/FAILED terminal evidence even when the external database
    // rejects the DDL.  The target JDBC transaction has already been rolled back;
    // only the platform repair-plan ledger must commit for audit and diagnosis.
    @Transactional(noRollbackFor = IllegalStateException.class)
    public DataSourceSchemaRepairResult apply(DataSourceConfig datasource,
                                              DataSourceSchemaRepairApplyRequest request,
                                              Long actorId) {
        requireDatasource(datasource);
        if (!request.isConfirmed()) {
            throw new IllegalArgumentException("结构修复属于外部数据库变更，必须先确认预览结果");
        }
        DataSourceSchemaRepairPlan plan = planMapper.selectById(request.getPlanId());
        if (plan == null || !datasource.getId().equals(plan.getDatasourceId())
                || !datasource.getTenantId().equals(plan.getTenantId())
                || !datasource.getProjectId().equals(plan.getProjectId())) {
            throw new NoSuchElementException("结构修复计划不存在或不属于当前数据源");
        }
        if (!PlanStatus.PREVIEWED.name().equals(plan.getPlanStatus())) {
            throw new IllegalStateException("结构修复计划当前不可执行，状态为 " + plan.getPlanStatus());
        }
        if (!constantTimeEquals(plan.getConfirmationDigest(), request.getConfirmationDigest())) {
            throw new IllegalArgumentException("确认摘要与预览不一致，请重新预览后再确认");
        }

        RepairOperation operation = RepairOperation.from(plan.getOperation());
        try (Connection connection = connectionProvider.openConnection(datasource.getId(), false)) {
            DatabaseFamily family = DatabaseFamily.from(connection.getMetaData().getDatabaseProductName());
            TableLocation location = resolveTable(connection, family, plan.getSchemaName(), plan.getTableName());
            ColumnSnapshot current = findColumn(connection.getMetaData(), location, plan.getColumnName());
            validateRepair(operation, family, current, plan.getRequestedType(), plan.getRequestedLength());
            String currentDigest = metadataDigest(location, plan.getColumnName(), current);
            if (!constantTimeEquals(plan.getMetadataDigest(), currentDigest)) {
                markTerminal(plan, PlanStatus.STALE, "METADATA_CHANGED", null);
                throw new IllegalStateException("目标表结构自预览后已发生变化，请重新生成修复预览");
            }

            String ddl = buildDdl(connection.getMetaData(), family, location, plan, current);
            boolean previousAutoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
                    statement.execute(ddl);
                }
                connection.commit();
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                markTerminal(plan, PlanStatus.FAILED, sqlFailureCode(exception), null);
                throw exception;
            } finally {
                restoreAutoCommitQuietly(connection, previousAutoCommit);
            }

            markTerminal(plan, PlanStatus.APPLIED, null, requireActor(actorId));
            ColumnSnapshot after = findColumn(connection.getMetaData(), location, plan.getColumnName());
            return toResult(plan, after, false);
        } catch (SQLException | ClassNotFoundException exception) {
            throw new IllegalStateException("目标数据源结构修复未完成: " + safeDatabaseFailure(exception), exception);
        }
    }

    private void validateRepair(RepairOperation operation,
                                DatabaseFamily family,
                                ColumnSnapshot current,
                                String requestedType,
                                Integer requestedLength) {
        if (operation == RepairOperation.ADD_NULLABLE_COLUMN) {
            if (current != null) {
                throw new IllegalArgumentException("目标字段已存在，不能执行新增可空字段修复");
            }
            validateSupportedType(family, requestedType, requestedLength);
            return;
        }
        if (current == null) {
            throw new IllegalArgumentException("目标字段不存在，不能执行 " + operation.name() + " 修复");
        }
        if (operation == RepairOperation.WIDEN_VARCHAR) {
            if (!current.isCharacterType()) {
                throw new IllegalArgumentException("扩大长度只支持字符字段，当前字段类型为 " + current.typeName());
            }
            if (requestedLength == null || current.columnSize() == null
                    || requestedLength <= current.columnSize()) {
                throw new IllegalArgumentException("新字符长度必须大于当前长度 " + current.columnSize());
            }
            rejectUnsafeMySqlModify(family, current);
            return;
        }
        if (current.nullable()) {
            throw new IllegalArgumentException("目标字段当前已经允许为空，无需解除非空约束");
        }
        rejectUnsafeMySqlModify(family, current);
    }

    private void rejectUnsafeMySqlModify(DatabaseFamily family, ColumnSnapshot current) {
        if (family == DatabaseFamily.MYSQL
                && (hasText(current.defaultValue()) || current.autoIncrement())) {
            throw new IllegalArgumentException(
                    "该 MySQL 字段包含默认值或自增属性，自动 MODIFY 可能改变附加属性，请由管理员人工处理");
        }
    }

    private String buildDdl(DatabaseMetaData metadata,
                            DatabaseFamily family,
                            TableLocation location,
                            DataSourceSchemaRepairPlan plan,
                            ColumnSnapshot current) throws SQLException {
        String table = quoteQualified(metadata, location);
        String column = quote(metadata, plan.getColumnName());
        RepairOperation operation = RepairOperation.from(plan.getOperation());
        if (operation == RepairOperation.ADD_NULLABLE_COLUMN) {
            return "ALTER TABLE " + table + " ADD COLUMN " + column + " "
                    + typeSql(family, plan.getRequestedType(), plan.getRequestedLength()) + " NULL";
        }
        if (operation == RepairOperation.WIDEN_VARCHAR) {
            if (family == DatabaseFamily.POSTGRESQL) {
                return "ALTER TABLE " + table + " ALTER COLUMN " + column + " TYPE VARCHAR("
                        + plan.getRequestedLength() + ")";
            }
            return "ALTER TABLE " + table + " MODIFY COLUMN " + column + " VARCHAR("
                    + plan.getRequestedLength() + ") " + (current.nullable() ? "NULL" : "NOT NULL");
        }
        if (family == DatabaseFamily.POSTGRESQL) {
            return "ALTER TABLE " + table + " ALTER COLUMN " + column + " DROP NOT NULL";
        }
        return "ALTER TABLE " + table + " MODIFY COLUMN " + column + " "
                + existingTypeSql(current) + " NULL";
    }

    private TableLocation resolveTable(Connection connection,
                                       DatabaseFamily family,
                                       String requestedNamespace,
                                       String requestedTable) throws SQLException {
        String namespace = hasText(requestedNamespace)
                ? requireIdentifier(requestedNamespace, "schemaName")
                : family == DatabaseFamily.MYSQL ? connection.getCatalog() : connection.getSchema();
        if (!hasText(namespace) && family == DatabaseFamily.POSTGRESQL) {
            namespace = "public";
        }
        if (!hasText(namespace)) {
            throw new IllegalArgumentException("无法确定目标 database/schema，请在修复计划中明确提供");
        }
        namespace = requireIdentifier(namespace, "schemaName");
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = family == DatabaseFamily.MYSQL ? namespace : null;
        String schema = family == DatabaseFamily.POSTGRESQL ? namespace : null;
        try (ResultSet resultSet = metadata.getTables(catalog, schema, requestedTable, new String[]{"TABLE"})) {
            while (resultSet.next()) {
                String found = resultSet.getString("TABLE_NAME");
                if (requestedTable.equalsIgnoreCase(found)) {
                    return new TableLocation(family, namespace, requireIdentifier(found, "tableName"));
                }
            }
        }
        throw new NoSuchElementException("目标表不存在: " + namespace + "." + requestedTable);
    }

    private ColumnSnapshot findColumn(DatabaseMetaData metadata,
                                      TableLocation location,
                                      String requestedColumn) throws SQLException {
        String catalog = location.family() == DatabaseFamily.MYSQL ? location.namespace() : null;
        String schema = location.family() == DatabaseFamily.POSTGRESQL ? location.namespace() : null;
        try (ResultSet columns = metadata.getColumns(catalog, schema, location.tableName(), requestedColumn)) {
            while (columns.next()) {
                String found = columns.getString("COLUMN_NAME");
                if (!requestedColumn.equalsIgnoreCase(found)) {
                    continue;
                }
                return new ColumnSnapshot(
                        found,
                        columns.getInt("DATA_TYPE"),
                        columns.getString("TYPE_NAME"),
                        nullableInteger(columns, "COLUMN_SIZE"),
                        nullableInteger(columns, "DECIMAL_DIGITS"),
                        columns.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                        columns.getString("COLUMN_DEF"),
                        "YES".equalsIgnoreCase(safeString(columns, "IS_AUTOINCREMENT"))
                );
            }
        }
        return null;
    }

    private String metadataDigest(TableLocation location, String columnName, ColumnSnapshot snapshot) {
        return digest(location.displayName() + "|" + columnName + "|"
                + (snapshot == null ? "ABSENT" : snapshot.stableValue()));
    }

    private String impactSummary(RepairOperation operation,
                                 TableLocation location,
                                 String columnName,
                                 ColumnSnapshot current,
                                 String requestedType,
                                 Integer requestedLength) {
        return switch (operation) {
            case ADD_NULLABLE_COLUMN -> "将在 " + location.displayName() + " 新增可空字段 " + columnName
                    + "，类型为 " + requestedDefinition(operation, requestedType, requestedLength)
                    + "；不会回填或删除已有数据。";
            case WIDEN_VARCHAR -> "将 " + location.displayName() + "." + columnName + " 从 "
                    + current.definition() + " 扩大为 VARCHAR(" + requestedLength + ")；不会缩窄字段。";
            case DROP_NOT_NULL -> "将解除 " + location.displayName() + "." + columnName
                    + " 的 NOT NULL 约束；不会修改已有字段值。";
        };
    }

    private DataSourceSchemaRepairResult toResult(DataSourceSchemaRepairPlan plan,
                                                  ColumnSnapshot snapshot,
                                                  boolean requiresConfirmation) {
        return DataSourceSchemaRepairResult.builder()
                .planId(plan.getId())
                .planRef(plan.getPlanRef())
                .datasourceId(plan.getDatasourceId())
                .operation(plan.getOperation())
                .objectLocator(joinObject(plan.getSchemaName(), plan.getTableName()))
                .columnName(plan.getColumnName())
                .currentDefinition(snapshot == null ? "ABSENT" : snapshot.definition())
                .requestedDefinition(requestedDefinition(
                        RepairOperation.from(plan.getOperation()), plan.getRequestedType(), plan.getRequestedLength()))
                .impactSummary(plan.getImpactSummary())
                .planStatus(plan.getPlanStatus())
                .requiresConfirmation(requiresConfirmation)
                .confirmationDigest(requiresConfirmation ? plan.getConfirmationDigest() : null)
                .appliedAt(plan.getAppliedAt())
                .safetyConstraints(List.of(
                        "仅执行白名单结构变更",
                        "应用前重新校验元数据摘要",
                        "不保存或返回原始 DDL",
                        "不删除表、字段或源端数据"))
                .build();
    }

    private void markTerminal(DataSourceSchemaRepairPlan plan,
                              PlanStatus status,
                              String failureCode,
                              Long actorId) {
        plan.setPlanStatus(status.name());
        plan.setFailureCode(failureCode);
        plan.setAppliedBy(actorId);
        plan.setUpdateTime(LocalDateTime.now());
        if (status == PlanStatus.APPLIED) {
            plan.setAppliedAt(plan.getUpdateTime());
        }
        planMapper.updateById(plan);
    }

    private void validateSupportedType(DatabaseFamily family, String type, Integer length) {
        if ("VARCHAR".equals(type)) {
            if (length == null || length < 1 || length > MAX_VARCHAR_LENGTH) {
                throw new IllegalArgumentException("VARCHAR 长度必须在 1 到 " + MAX_VARCHAR_LENGTH + " 之间");
            }
            return;
        }
        if (!List.of("INTEGER", "BIGINT", "BOOLEAN", "DATE", "TIMESTAMP", "TEXT").contains(type)) {
            throw new IllegalArgumentException("不支持自动新增该字段类型: " + type);
        }
        if (family == DatabaseFamily.MYSQL && "BOOLEAN".equals(type)) {
            return;
        }
    }

    private String typeSql(DatabaseFamily family, String type, Integer length) {
        if ("VARCHAR".equals(type)) {
            return "VARCHAR(" + length + ")";
        }
        if (family == DatabaseFamily.MYSQL && "BOOLEAN".equals(type)) {
            return "BOOLEAN";
        }
        return type;
    }

    private String existingTypeSql(ColumnSnapshot current) {
        if (current.isCharacterType() && current.columnSize() != null) {
            return current.typeName() + "(" + current.columnSize() + ")";
        }
        if ((current.jdbcType() == Types.DECIMAL || current.jdbcType() == Types.NUMERIC)
                && current.columnSize() != null && current.decimalDigits() != null) {
            return current.typeName() + "(" + current.columnSize() + "," + current.decimalDigits() + ")";
        }
        return current.typeName();
    }

    private String normalizeRequestedType(RepairOperation operation, String value) {
        if (operation != RepairOperation.ADD_NULLABLE_COLUMN) {
            return operation == RepairOperation.WIDEN_VARCHAR ? "VARCHAR" : null;
        }
        if (!hasText(value)) {
            throw new IllegalArgumentException("新增字段修复必须提供 requestedType");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private Integer normalizeRequestedLength(RepairOperation operation, Integer value) {
        if (operation == RepairOperation.WIDEN_VARCHAR && value == null) {
            throw new IllegalArgumentException("扩大字符字段必须提供 requestedLength");
        }
        return value;
    }

    private String requestedDefinition(RepairOperation operation, String type, Integer length) {
        if (operation == RepairOperation.DROP_NOT_NULL) {
            return "NULLABLE";
        }
        return "VARCHAR".equals(type) ? "VARCHAR(" + length + ")" : type;
    }

    private String quoteQualified(DatabaseMetaData metadata, TableLocation location) throws SQLException {
        return quote(metadata, location.namespace()) + "." + quote(metadata, location.tableName());
    }

    private String quote(DatabaseMetaData metadata, String identifier) throws SQLException {
        String safe = requireIdentifier(identifier, "identifier");
        String quote = metadata.getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) {
            throw new IllegalStateException("目标数据库未提供安全的标识符引用符号");
        }
        quote = quote.trim();
        return quote + safe.replace(quote, quote + quote) + quote;
    }

    private String requireIdentifier(String value, String field) {
        if (!hasText(value) || !SAFE_IDENTIFIER.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException(field + " 不是受支持的数据库标识符");
        }
        return value.trim();
    }

    private void requireDatasource(DataSourceConfig datasource) {
        if (datasource == null || datasource.getId() == null
                || datasource.getTenantId() == null || datasource.getProjectId() == null) {
            throw new IllegalArgumentException("结构修复需要明确的数据源、租户和项目范围");
        }
    }

    private Long requireActor(Long actorId) {
        if (actorId == null || actorId <= 0) {
            throw new IllegalArgumentException("结构修复需要可审计的操作人身份");
        }
        return actorId;
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private String safeDatabaseFailure(Exception exception) {
        if (exception instanceof SQLException sqlException) {
            return "数据库拒绝操作，SQLState=" + safeToken(sqlException.getSQLState())
                    + "，errorCode=" + sqlException.getErrorCode();
        }
        return "数据库驱动不可用或连接失败";
    }

    private String sqlFailureCode(SQLException exception) {
        return "SQLSTATE_" + safeToken(exception.getSQLState());
    }

    private String safeToken(String value) {
        if (!hasText(value)) {
            return "UNKNOWN";
        }
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private String safeString(ResultSet resultSet, String column) {
        try {
            return resultSet.getString(column);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original database failure is more useful than a rollback failure.
        }
    }

    private void restoreAutoCommitQuietly(Connection connection, boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException ignored) {
            // The connection is about to be closed.
        }
    }

    private String joinObject(String namespace, String table) {
        return hasText(namespace) ? namespace + "." + table : table;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private enum RepairOperation {
        ADD_NULLABLE_COLUMN,
        WIDEN_VARCHAR,
        DROP_NOT_NULL;

        private static RepairOperation from(String value) {
            try {
                return RepairOperation.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "operation 仅支持 ADD_NULLABLE_COLUMN、WIDEN_VARCHAR、DROP_NOT_NULL");
            }
        }
    }

    private enum PlanStatus {
        PREVIEWED,
        APPLIED,
        FAILED,
        STALE
    }

    private enum DatabaseFamily {
        MYSQL,
        POSTGRESQL;

        private static DatabaseFamily from(String productName) {
            String normalized = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
            if (normalized.contains("mysql") || normalized.contains("mariadb")) {
                return MYSQL;
            }
            if (normalized.contains("postgresql")) {
                return POSTGRESQL;
            }
            throw new IllegalArgumentException("当前仅支持 MySQL 与 PostgreSQL 的受控结构修复");
        }
    }

    private record TableLocation(DatabaseFamily family, String namespace, String tableName) {
        private String displayName() {
            return namespace + "." + tableName;
        }
    }

    private record ColumnSnapshot(String columnName,
                                  int jdbcType,
                                  String typeName,
                                  Integer columnSize,
                                  Integer decimalDigits,
                                  boolean nullable,
                                  String defaultValue,
                                  boolean autoIncrement) {
        private boolean isCharacterType() {
            return List.of(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                    Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR).contains(jdbcType);
        }

        private String definition() {
            String size = isCharacterType() && columnSize != null ? "(" + columnSize + ")" : "";
            return typeName + size + (nullable ? " NULL" : " NOT NULL");
        }

        private String stableValue() {
            return String.join("|",
                    columnName,
                    String.valueOf(jdbcType),
                    String.valueOf(typeName),
                    String.valueOf(columnSize),
                    String.valueOf(decimalDigits),
                    String.valueOf(nullable),
                    String.valueOf(defaultValue),
                    String.valueOf(autoIncrement));
        }
    }
}

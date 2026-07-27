package com.czh.datasmart.govern.datasource.service.impl;

import com.czh.datasmart.govern.datasource.controller.dto.DataSourceSchemaRepairApplyRequest;
import com.czh.datasmart.govern.datasource.controller.dto.DataSourceSchemaRepairPreviewRequest;
import com.czh.datasmart.govern.datasource.controller.dto.DataSourceSchemaRepairResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.DataSourceSchemaRepairPlan;
import com.czh.datasmart.govern.datasource.mapper.DataSourceSchemaRepairPlanMapper;
import com.czh.datasmart.govern.datasource.service.DataSourceSchemaRepairService;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncJdbcConnectionProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.StringJoiner;
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
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public DataSourceSchemaRepairResult preview(DataSourceConfig datasource,
                                                DataSourceSchemaRepairPreviewRequest request,
                                                Long actorId) {
        requireDatasource(datasource);
        RepairOperation operation = RepairOperation.from(request.getOperation());
        String tableName = requireIdentifier(request.getTableName(), "tableName");
        String columnName = operation == RepairOperation.CREATE_TABLE
                ? null
                : requireIdentifier(request.getColumnName(), "columnName");
        String requestedType = normalizeRequestedType(operation, request.getRequestedType());
        Integer requestedLength = normalizeRequestedLength(operation, request.getRequestedLength());
        List<CreateColumn> createColumns = operation == RepairOperation.CREATE_TABLE
                ? normalizeCreateColumns(request.getColumns())
                : List.of();

        try (Connection connection = connectionProvider.openConnection(datasource.getId(), false)) {
            DatabaseFamily family = DatabaseFamily.from(connection.getMetaData().getDatabaseProductName());
            TableLocation location = operation == RepairOperation.CREATE_TABLE
                    ? resolveNewTable(connection, family, request.getSchemaName(), tableName)
                    : resolveTable(connection, family, request.getSchemaName(), tableName);
            ColumnSnapshot current = operation == RepairOperation.CREATE_TABLE
                    ? null
                    : findColumn(connection.getMetaData(), location, columnName);
            if (operation == RepairOperation.CREATE_TABLE) {
                validateCreateColumns(family, createColumns);
            } else {
                validateRepair(operation, family, current, requestedType, requestedLength);
            }

            String columnsJson = operation == RepairOperation.CREATE_TABLE ? serializeColumns(createColumns) : null;
            String metadataDigest = operation == RepairOperation.CREATE_TABLE
                    ? digest(location.displayName() + "|TABLE_ABSENT|" + columnsJson)
                    : metadataDigest(location, columnName, current);
            String planRef = UUID.randomUUID().toString();
            String impactSummary = operation == RepairOperation.CREATE_TABLE
                    ? "将在 " + location.displayName() + " 创建包含 " + createColumns.size()
                    + " 个字段的新空表；不会读取、覆盖或删除目标端已有数据。"
                    : impactSummary(operation, location, columnName, current, requestedType, requestedLength);
            String confirmationDigest = digest(String.join("|",
                    planRef,
                    String.valueOf(datasource.getTenantId()),
                    String.valueOf(datasource.getProjectId()),
                    String.valueOf(datasource.getId()),
                    operation.name(),
                    location.displayName(),
                    String.valueOf(columnName),
                    metadataDigest,
                    operation == RepairOperation.CREATE_TABLE
                            ? columnsJson
                            : requestedDefinition(operation, requestedType, requestedLength)));

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
            plan.setColumnsJson(columnsJson);
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
            List<CreateColumn> createColumns = operation == RepairOperation.CREATE_TABLE
                    ? deserializeColumns(plan.getColumnsJson())
                    : List.of();
            TableLocation location;
            try {
                location = operation == RepairOperation.CREATE_TABLE
                        ? resolveNewTable(connection, family, plan.getSchemaName(), plan.getTableName())
                        : resolveTable(connection, family, plan.getSchemaName(), plan.getTableName());
            } catch (IllegalArgumentException | NoSuchElementException exception) {
                if (operation == RepairOperation.CREATE_TABLE) {
                    markTerminal(plan, PlanStatus.STALE, "TARGET_LOCATION_CHANGED", null);
                    throw new IllegalStateException(
                            "目标表或目标命名空间自预览后已发生变化，请重新生成创建目标表预览: "
                                    + exception.getMessage(), exception);
                }
                throw exception;
            }
            ColumnSnapshot current = operation == RepairOperation.CREATE_TABLE
                    ? null
                    : findColumn(connection.getMetaData(), location, plan.getColumnName());
            if (operation == RepairOperation.CREATE_TABLE) {
                validateCreateColumns(family, createColumns);
            } else {
                validateRepair(operation, family, current, plan.getRequestedType(), plan.getRequestedLength());
            }
            String currentDigest = operation == RepairOperation.CREATE_TABLE
                    ? digest(location.displayName() + "|TABLE_ABSENT|" + serializeColumns(createColumns))
                    : metadataDigest(location, plan.getColumnName(), current);
            if (!constantTimeEquals(plan.getMetadataDigest(), currentDigest)) {
                markTerminal(plan, PlanStatus.STALE, "METADATA_CHANGED", null);
                throw new IllegalStateException("目标表结构自预览后已发生变化，请重新生成修复预览");
            }

            String ddl = buildDdl(connection.getMetaData(), family, location, plan, current, createColumns);
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

            ColumnSnapshot after;
            if (operation == RepairOperation.CREATE_TABLE) {
                TableLocation createdTable = resolveTable(
                        connection, family, plan.getSchemaName(), plan.getTableName());
                validateCreatedColumns(connection.getMetaData(), createdTable, createColumns);
                after = null;
            } else {
                after = findColumn(connection.getMetaData(), location, plan.getColumnName());
            }
            markTerminal(plan, PlanStatus.APPLIED, null, requireActor(actorId));
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

    private List<CreateColumn> normalizeCreateColumns(
            List<DataSourceSchemaRepairPreviewRequest.CreateTableColumn> requestedColumns) {
        if (requestedColumns == null || requestedColumns.isEmpty()) {
            throw new IllegalArgumentException("创建目标表至少需要一个来自源表元数据的字段");
        }
        if (requestedColumns.size() > 160) {
            throw new IllegalArgumentException("单次自动创建目标表最多支持 160 个字段");
        }
        List<CreateColumn> normalized = new ArrayList<>(requestedColumns.size());
        Set<String> names = new HashSet<>();
        for (DataSourceSchemaRepairPreviewRequest.CreateTableColumn requested : requestedColumns) {
            if (requested == null) {
                throw new IllegalArgumentException("创建目标表的字段定义不能为空");
            }
            String columnName = requireIdentifier(requested.getColumnName(), "columns.columnName");
            if (!names.add(columnName.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("创建目标表的字段名重复: " + columnName);
            }
            String dataType = normalizeCreateType(requested.getDataType());
            Integer length = "VARCHAR".equals(dataType) ? requested.getLength() : null;
            Integer precision = "DECIMAL".equals(dataType) ? requested.getPrecision() : null;
            Integer scale = "DECIMAL".equals(dataType) ? requested.getScale() : null;
            normalized.add(new CreateColumn(
                    columnName,
                    dataType,
                    length,
                    precision,
                    scale,
                    requested.isPrimaryKey() ? false : requested.isNullable(),
                    requested.isPrimaryKey()));
        }
        return List.copyOf(normalized);
    }

    private String normalizeCreateType(String value) {
        if (!hasText(value)) {
            throw new IllegalArgumentException("创建目标表的字段必须包含 dataType");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        return switch (normalized) {
            case "CHAR", "CHARACTER", "CHARACTER VARYING", "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2" ->
                    "VARCHAR";
            case "TINYINT", "SMALLINT", "INT2" -> "SMALLINT";
            case "INT", "INTEGER", "INT4", "SERIAL" -> "INTEGER";
            case "BIGINT", "INT8", "BIGSERIAL" -> "BIGINT";
            case "DECIMAL", "NUMERIC", "NUMBER" -> "DECIMAL";
            case "FLOAT", "REAL", "DOUBLE", "DOUBLE PRECISION" -> "DOUBLE";
            case "BOOL", "BOOLEAN" -> "BOOLEAN";
            case "DATE" -> "DATE";
            case "TIME", "TIME WITHOUT TIME ZONE", "TIME WITH TIME ZONE" -> "TIME";
            case "DATETIME", "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE", "TIMESTAMP WITH TIME ZONE" ->
                    "TIMESTAMP";
            case "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "CLOB" -> "TEXT";
            case "BINARY", "VARBINARY", "BLOB", "BYTEA", "LONGBLOB" -> "BINARY";
            case "JSON", "JSONB" -> "JSON";
            case "UUID" -> "UUID";
            default -> throw new IllegalArgumentException("不支持自动创建目标表的字段类型: " + value);
        };
    }

    private void validateCreateColumns(DatabaseFamily family, List<CreateColumn> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("创建目标表缺少字段定义");
        }
        Set<String> names = new HashSet<>();
        for (CreateColumn column : columns) {
            String columnName = requireIdentifier(column.columnName(), "columns.columnName");
            if (!names.add(columnName.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("创建目标表的字段名重复: " + columnName);
            }
            validateCreateType(family, column);
            if (column.primaryKey() && column.nullable()) {
                throw new IllegalArgumentException("主键字段不能允许 NULL: " + columnName);
            }
        }
    }

    private void validateCreateType(DatabaseFamily family, CreateColumn column) {
        switch (column.dataType()) {
            case "VARCHAR" -> {
                if (column.length() == null || column.length() < 1 || column.length() > MAX_VARCHAR_LENGTH) {
                    throw new IllegalArgumentException(
                            "字段 " + column.columnName() + " 的 VARCHAR 长度必须在 1 到 "
                                    + MAX_VARCHAR_LENGTH + " 之间");
                }
            }
            case "DECIMAL" -> {
                int maximumPrecision = family == DatabaseFamily.MYSQL ? 65 : 1_000;
                if (column.precision() == null || column.precision() < 1
                        || column.precision() > maximumPrecision) {
                    throw new IllegalArgumentException(
                            "字段 " + column.columnName() + " 的 DECIMAL precision 必须在 1 到 "
                                    + maximumPrecision + " 之间");
                }
                int scale = column.scale() == null ? 0 : column.scale();
                if (scale < 0 || scale > column.precision()) {
                    throw new IllegalArgumentException(
                            "字段 " + column.columnName() + " 的 DECIMAL scale 必须在 0 到 precision 之间");
                }
            }
            case "SMALLINT", "INTEGER", "BIGINT", "DOUBLE", "BOOLEAN", "DATE", "TIME",
                    "TIMESTAMP", "TEXT", "BINARY", "JSON", "UUID" -> {
                // The exact vendor spelling is selected later by createTypeSql.
            }
            default -> throw new IllegalArgumentException(
                    "字段 " + column.columnName() + " 使用了未准入的类型 " + column.dataType());
        }
    }

    private void validateCreatedColumns(DatabaseMetaData metadata,
                                        TableLocation location,
                                        List<CreateColumn> expectedColumns) throws SQLException {
        for (CreateColumn expected : expectedColumns) {
            if (findColumn(metadata, location, expected.columnName()) == null) {
                throw new IllegalStateException(
                        "目标表已创建，但执行后未读取到预期字段 " + location.displayName()
                                + "." + expected.columnName());
            }
        }
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
                            ColumnSnapshot current,
                            List<CreateColumn> createColumns) throws SQLException {
        String table = quoteQualified(metadata, location);
        RepairOperation operation = RepairOperation.from(plan.getOperation());
        if (operation == RepairOperation.CREATE_TABLE) {
            return buildCreateTableDdl(metadata, family, table, createColumns);
        }
        String column = quote(metadata, plan.getColumnName());
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

    private String buildCreateTableDdl(DatabaseMetaData metadata,
                                       DatabaseFamily family,
                                       String quotedTable,
                                       List<CreateColumn> columns) throws SQLException {
        StringJoiner definitions = new StringJoiner(", ");
        List<String> primaryKeys = new ArrayList<>();
        for (CreateColumn column : columns) {
            StringBuilder definition = new StringBuilder()
                    .append(quote(metadata, column.columnName()))
                    .append(' ')
                    .append(createTypeSql(family, column));
            if (!column.nullable() || column.primaryKey()) {
                definition.append(" NOT NULL");
            }
            definitions.add(definition.toString());
            if (column.primaryKey()) {
                primaryKeys.add(quote(metadata, column.columnName()));
            }
        }
        if (!primaryKeys.isEmpty()) {
            definitions.add("PRIMARY KEY (" + String.join(", ", primaryKeys) + ")");
        }
        return "CREATE TABLE " + quotedTable + " (" + definitions + ")";
    }

    private TableLocation resolveTable(Connection connection,
                                       DatabaseFamily family,
                                       String requestedNamespace,
                                       String requestedTable) throws SQLException {
        String namespace = resolveNamespace(connection, family, requestedNamespace);
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

    /**
     * Resolves an existing namespace while requiring the requested target table to remain absent.
     * This is used both at preview time and immediately before apply, so a table created by another
     * actor after preview invalidates the plan rather than being overwritten.
     */
    private TableLocation resolveNewTable(Connection connection,
                                          DatabaseFamily family,
                                          String requestedNamespace,
                                          String requestedTable) throws SQLException {
        String namespace = resolveNamespace(connection, family, requestedNamespace);
        DatabaseMetaData metadata = connection.getMetaData();
        ensureNamespaceExists(connection, metadata, family, namespace);
        String catalog = family == DatabaseFamily.MYSQL ? namespace : null;
        String schema = family == DatabaseFamily.POSTGRESQL ? namespace : null;
        try (ResultSet resultSet = metadata.getTables(catalog, schema, requestedTable, null)) {
            while (resultSet.next()) {
                String found = resultSet.getString("TABLE_NAME");
                if (requestedTable.equalsIgnoreCase(found)) {
                    throw new IllegalArgumentException(
                            "目标表已存在，不能执行创建目标表修复: " + namespace + "." + found);
                }
            }
        }
        return new TableLocation(family, namespace, requireIdentifier(requestedTable, "tableName"));
    }

    private String resolveNamespace(Connection connection,
                                    DatabaseFamily family,
                                    String requestedNamespace) throws SQLException {
        String namespace = hasText(requestedNamespace)
                ? requireIdentifier(requestedNamespace, "schemaName")
                : family == DatabaseFamily.MYSQL ? connection.getCatalog() : connection.getSchema();
        if (!hasText(namespace) && family == DatabaseFamily.POSTGRESQL) {
            namespace = "public";
        }
        if (!hasText(namespace)) {
            throw new IllegalArgumentException("无法确定目标 database/schema，请明确提供目标命名空间");
        }
        return requireIdentifier(namespace, "schemaName");
    }

    private void ensureNamespaceExists(Connection connection,
                                       DatabaseMetaData metadata,
                                       DatabaseFamily family,
                                       String namespace) throws SQLException {
        String current = family == DatabaseFamily.MYSQL ? connection.getCatalog() : connection.getSchema();
        if (hasText(current) && current.equalsIgnoreCase(namespace)) {
            return;
        }
        try (ResultSet namespaces = family == DatabaseFamily.MYSQL
                ? metadata.getCatalogs()
                : metadata.getSchemas(null, namespace)) {
            while (namespaces.next()) {
                String found = namespaces.getString(family == DatabaseFamily.MYSQL
                        ? "TABLE_CAT"
                        : "TABLE_SCHEM");
                if (namespace.equalsIgnoreCase(found)) {
                    return;
                }
            }
        }
        throw new NoSuchElementException("目标 database/schema 不存在: " + namespace);
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
            case CREATE_TABLE -> "将在 " + location.displayName() + " 创建新的空目标表";
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
        RepairOperation operation = RepairOperation.from(plan.getOperation());
        boolean createTable = operation == RepairOperation.CREATE_TABLE;
        return DataSourceSchemaRepairResult.builder()
                .planId(plan.getId())
                .planRef(plan.getPlanRef())
                .datasourceId(plan.getDatasourceId())
                .operation(plan.getOperation())
                .objectLocator(joinObject(plan.getSchemaName(), plan.getTableName()))
                .columnName(plan.getColumnName())
                .currentDefinition(createTable
                        ? (requiresConfirmation ? "TABLE_ABSENT" : "TABLE_CREATED")
                        : snapshot == null ? "ABSENT" : snapshot.definition())
                .requestedDefinition(createTable
                        ? createColumnsDefinition(plan.getColumnsJson())
                        : requestedDefinition(operation, plan.getRequestedType(), plan.getRequestedLength()))
                .impactSummary(plan.getImpactSummary())
                .planStatus(plan.getPlanStatus())
                .requiresConfirmation(requiresConfirmation)
                .confirmationDigest(requiresConfirmation ? plan.getConfirmationDigest() : null)
                .appliedAt(plan.getAppliedAt())
                .safetyConstraints(createTable
                        ? List.of(
                                "只允许在目标表不存在时创建新空表",
                                "字段定义来自可信元数据并经过类型白名单转换",
                                "应用前重新校验命名空间、表缺失状态和字段摘要",
                                "不保存或返回原始 DDL，不复制默认表达式、触发器或源端数据")
                        : List.of(
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

    private String createTypeSql(DatabaseFamily family, CreateColumn column) {
        return switch (column.dataType()) {
            case "VARCHAR" -> "VARCHAR(" + column.length() + ")";
            case "DECIMAL" -> "DECIMAL(" + column.precision() + ","
                    + (column.scale() == null ? 0 : column.scale()) + ")";
            case "DOUBLE" -> family == DatabaseFamily.POSTGRESQL ? "DOUBLE PRECISION" : "DOUBLE";
            case "BINARY" -> family == DatabaseFamily.POSTGRESQL ? "BYTEA" : "LONGBLOB";
            case "JSON" -> family == DatabaseFamily.POSTGRESQL ? "JSONB" : "JSON";
            case "UUID" -> family == DatabaseFamily.POSTGRESQL ? "UUID" : "CHAR(36)";
            default -> column.dataType();
        };
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
        if (operation == RepairOperation.CREATE_TABLE) {
            return "NEW EMPTY TABLE";
        }
        if (operation == RepairOperation.DROP_NOT_NULL) {
            return "NULLABLE";
        }
        return "VARCHAR".equals(type) ? "VARCHAR(" + length + ")" : type;
    }

    private String serializeColumns(List<CreateColumn> columns) {
        try {
            return objectMapper.writeValueAsString(columns);
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化受控目标表字段定义", exception);
        }
    }

    private List<CreateColumn> deserializeColumns(String columnsJson) {
        if (!hasText(columnsJson)) {
            throw new IllegalStateException("创建目标表计划缺少受控字段定义");
        }
        try {
            List<CreateColumn> columns = objectMapper.readValue(
                    columnsJson, new TypeReference<List<CreateColumn>>() { });
            return columns == null ? List.of() : List.copyOf(columns);
        } catch (Exception exception) {
            throw new IllegalStateException("创建目标表计划的字段定义无法读取", exception);
        }
    }

    private String createColumnsDefinition(String columnsJson) {
        List<CreateColumn> columns = deserializeColumns(columnsJson);
        StringJoiner summary = new StringJoiner(", ");
        for (CreateColumn column : columns) {
            String definition = column.columnName() + " " + canonicalTypeDefinition(column)
                    + ((!column.nullable() || column.primaryKey()) ? " NOT NULL" : " NULL")
                    + (column.primaryKey() ? " PRIMARY KEY" : "");
            summary.add(definition);
        }
        return summary.toString();
    }

    private String canonicalTypeDefinition(CreateColumn column) {
        return switch (column.dataType()) {
            case "VARCHAR" -> "VARCHAR(" + column.length() + ")";
            case "DECIMAL" -> "DECIMAL(" + column.precision() + ","
                    + (column.scale() == null ? 0 : column.scale()) + ")";
            default -> column.dataType();
        };
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
        CREATE_TABLE,
        ADD_NULLABLE_COLUMN,
        WIDEN_VARCHAR,
        DROP_NOT_NULL;

        private static RepairOperation from(String value) {
            try {
                return RepairOperation.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "operation 仅支持 CREATE_TABLE、ADD_NULLABLE_COLUMN、WIDEN_VARCHAR、DROP_NOT_NULL");
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

    private record CreateColumn(String columnName,
                                String dataType,
                                Integer length,
                                Integer precision,
                                Integer scale,
                                boolean nullable,
                                boolean primaryKey) {
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

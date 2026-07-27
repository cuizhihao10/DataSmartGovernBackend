/**
 * @Author : Cui
 * @Date: 2026/07/27 20:00
 * @Description DataSmart Govern Backend - DataSourceCdcReadinessServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.impl;

import com.czh.datasmart.govern.datasource.controller.dto.DataSourceCdcReadinessRequest;
import com.czh.datasmart.govern.datasource.controller.dto.DataSourceCdcReadinessResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.service.DataSourceCdcReadinessService;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncJdbcConnectionProvider;
import com.czh.datasmart.govern.datasource.service.support.CdcInfrastructureReadinessProbe;
import com.czh.datasmart.govern.datasource.support.DataSourceStatus;
import com.czh.datasmart.govern.datasource.support.DataSourceType;
import com.czh.datasmart.govern.datasource.support.DataSourceUsagePurpose;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates database and infrastructure prerequisites for CDC without changing user data.
 *
 * <p>This service intentionally distinguishes prerequisite discovery from an executable CDC
 * implementation. The repository does not yet provision Debezium connectors, persist offsets,
 * consume change events, or apply them through a sink runtime. Consequently the runtime check is
 * an explicit blocker even if every database and Kafka prerequisite passes.</p>
 */
@Service
@RequiredArgsConstructor
public class DataSourceCdcReadinessServiceImpl implements DataSourceCdcReadinessService {

    private static final String CATEGORY_SCOPE = "SCOPE";
    private static final String CATEGORY_SOURCE = "SOURCE_DATABASE";
    private static final String CATEGORY_TARGET = "TARGET_DATABASE";
    private static final String CATEGORY_RUNTIME = "RUNTIME";

    private final SyncJdbcConnectionProvider connectionProvider;
    private final CdcInfrastructureReadinessProbe infrastructureProbe;

    @Override
    public DataSourceCdcReadinessResult check(DataSourceConfig sourceDatasource,
                                              DataSourceConfig targetDatasource,
                                              DataSourceCdcReadinessRequest request) {
        List<DataSourceCdcReadinessResult.CheckItem> checks = new ArrayList<>();
        validateResourceScope(sourceDatasource, targetDatasource, checks);

        DataSourceType sourceType = parseType(sourceDatasource, "源端", checks);
        DataSourceType targetType = parseType(targetDatasource, "目标端", checks);
        validateDatasourceLifecycleAndPurpose(sourceDatasource, true, checks);
        validateDatasourceLifecycleAndPurpose(targetDatasource, false, checks);

        if (sourceType != null && targetType != null && noScopeFailure(checks)) {
            probeDatabases(sourceDatasource, targetDatasource, sourceType, targetType,
                    request.getObjectMappings(), checks);
        }
        checks.addAll(infrastructureProbe.probe());

        // This is a code capability fact, not a deployment switch. It must remain a blocker until
        // connector provisioning, offset/checkpoint ownership and sink execution are implemented.
        checks.add(DataSourceCdcReadinessResult.CheckItem.failed(
                "CDC_PIPELINE_RUNTIME_NOT_IMPLEMENTED", CATEGORY_RUNTIME,
                "当前版本尚未实现可生产运行的 Debezium CDC 流水线。",
                "需要补齐连接器配置与回收、offset/checkpoint 持久化、变更事件消费、目标端幂等写入、失败恢复和运行日志后，才能开放实时任务。",
                Map.of("referenceRuntime", "DEBEZIUM_KAFKA_CONNECT_CDC_PIPELINE")));

        int failedCount = (int) checks.stream().filter(item -> "FAILED".equals(item.status())).count();
        int warningCount = (int) checks.stream().filter(item -> "WARNING".equals(item.status())).count();
        int passedCount = checks.size() - failedCount - warningCount;
        List<String> issueCodes = checks.stream()
                .filter(item -> "FAILED".equals(item.status()))
                .map(DataSourceCdcReadinessResult.CheckItem::code)
                .distinct()
                .toList();
        List<String> recommendations = checks.stream()
                .filter(item -> !"PASSED".equals(item.status()))
                .map(DataSourceCdcReadinessResult.CheckItem::recommendation)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        return new DataSourceCdcReadinessResult(
                "datasmart.datasource.cdc-readiness.v1",
                failedCount == 0,
                failedCount == 0 ? "READY" : "BLOCKED",
                sourceDatasource.getId(),
                targetDatasource.getId(),
                sourceType == null ? safeType(sourceDatasource) : sourceType.name(),
                targetType == null ? safeType(targetDatasource) : targetType.name(),
                passedCount,
                warningCount,
                failedCount,
                issueCodes,
                recommendations,
                List.copyOf(checks),
                LocalDateTime.now()
        );
    }

    private void validateResourceScope(DataSourceConfig source,
                                       DataSourceConfig target,
                                       List<DataSourceCdcReadinessResult.CheckItem> checks) {
        if (source.getTenantId() == null || !source.getTenantId().equals(target.getTenantId())) {
            checks.add(DataSourceCdcReadinessResult.CheckItem.failed(
                    "CDC_CROSS_TENANT_FORBIDDEN", CATEGORY_SCOPE,
                    "源端和目标端不属于同一租户，不能创建实时同步任务。",
                    "请在当前租户和项目中重新选择源端、目标端数据源。", Map.of()));
        } else {
            checks.add(DataSourceCdcReadinessResult.CheckItem.passed(
                    "CDC_TENANT_SCOPE_MATCHED", CATEGORY_SCOPE,
                    "源端和目标端属于同一租户。", Map.of()));
        }
        if (source.getProjectId() == null || !source.getProjectId().equals(target.getProjectId())) {
            checks.add(DataSourceCdcReadinessResult.CheckItem.failed(
                    "CDC_CROSS_PROJECT_FORBIDDEN", CATEGORY_SCOPE,
                    "源端和目标端不属于同一项目，不能跨项目建立 CDC 链路。",
                    "请切换到正确项目，或在同一项目内登记并授权所需数据源。", Map.of()));
        } else {
            checks.add(DataSourceCdcReadinessResult.CheckItem.passed(
                    "CDC_PROJECT_SCOPE_MATCHED", CATEGORY_SCOPE,
                    "源端和目标端属于同一项目。", Map.of()));
        }
    }

    private DataSourceType parseType(DataSourceConfig datasource,
                                     String side,
                                     List<DataSourceCdcReadinessResult.CheckItem> checks) {
        try {
            DataSourceType type = DataSourceType.fromValue(datasource.getType());
            boolean supported = type == DataSourceType.MYSQL || type == DataSourceType.POSTGRESQL;
            if (!supported) {
                checks.add(DataSourceCdcReadinessResult.CheckItem.failed(
                        "CDC_" + sideCode(side) + "_CONNECTOR_UNSUPPORTED", sideCategory(side),
                        side + "连接器 " + type.name() + " 尚未实现 CDC 准入与运行适配。",
                        "当前请选择 MySQL 或 PostgreSQL，其他连接器需要先实现对应的日志读取和位点管理。",
                        Map.of("connectorType", type.name())));
            } else {
                checks.add(DataSourceCdcReadinessResult.CheckItem.passed(
                        "CDC_" + sideCode(side) + "_CONNECTOR_SUPPORTED", sideCategory(side),
                        side + "连接器具备 CDC 前置条件探测能力。",
                        Map.of("connectorType", type.name())));
            }
            return supported ? type : null;
        } catch (IllegalArgumentException exception) {
            checks.add(DataSourceCdcReadinessResult.CheckItem.failed(
                    "CDC_" + sideCode(side) + "_CONNECTOR_INVALID", sideCategory(side),
                    side + "数据源类型无效，无法执行 CDC 准入检查。",
                    "请修正数据源类型后重新检查。", Map.of()));
            return null;
        }
    }

    private void validateDatasourceLifecycleAndPurpose(
            DataSourceConfig datasource,
            boolean source,
            List<DataSourceCdcReadinessResult.CheckItem> checks) {
        String side = source ? "SOURCE" : "TARGET";
        String category = source ? CATEGORY_SOURCE : CATEGORY_TARGET;
        if (!DataSourceStatus.ACTIVE.equals(datasource.getStatus())) {
            checks.add(DataSourceCdcReadinessResult.CheckItem.failed(
                    "CDC_" + side + "_DATASOURCE_INACTIVE", category,
                    (source ? "源端" : "目标端") + "数据源未启用。",
                    "请先启用数据源并确认连接测试成功。", Map.of()));
        } else {
            checks.add(DataSourceCdcReadinessResult.CheckItem.passed(
                    "CDC_" + side + "_DATASOURCE_ACTIVE", category,
                    (source ? "源端" : "目标端") + "数据源已启用。", Map.of()));
        }
        String requiredPurpose = source
                ? DataSourceUsagePurpose.SOURCE.name()
                : DataSourceUsagePurpose.TARGET.name();
        if (!requiredPurpose.equalsIgnoreCase(String.valueOf(datasource.getUsagePurpose()))) {
            checks.add(DataSourceCdcReadinessResult.CheckItem.failed(
                    "CDC_" + side + "_USAGE_INVALID", category,
                    (source ? "源端" : "目标端") + "数据源用途不是 " + requiredPurpose + "。",
                    "请重新选择用途正确的数据源；系统不允许一条连接同时作为源端和目标端。", Map.of()));
        } else {
            checks.add(DataSourceCdcReadinessResult.CheckItem.passed(
                    "CDC_" + side + "_USAGE_VALID", category,
                    (source ? "源端" : "目标端") + "数据源用途正确。", Map.of()));
        }
    }

    private void probeDatabases(DataSourceConfig source,
                                DataSourceConfig target,
                                DataSourceType sourceType,
                                DataSourceType targetType,
                                List<DataSourceCdcReadinessRequest.ObjectMapping> mappings,
                                List<DataSourceCdcReadinessResult.CheckItem> checks) {
        try (Connection sourceConnection = connectionProvider.openConnection(source.getId(), true)) {
            checks.add(DataSourceCdcReadinessResult.CheckItem.passed(
                    "CDC_SOURCE_CONNECTION_READY", CATEGORY_SOURCE,
                    "源端数据库连接成功。", Map.of()));
            probeSourceServer(sourceConnection, sourceType, checks);
            probeSourceTables(sourceConnection, sourceType, mappings, checks);
        } catch (Exception exception) {
            checks.add(DataSourceCdcReadinessResult.CheckItem.failed(
                    "CDC_SOURCE_CONNECTION_FAILED", CATEGORY_SOURCE,
                    "无法连接源端数据库，未能检查日志配置和源表主键。",
                    "请检查数据源连接、账号权限和网络后重新测试。",
                    Map.of("errorType", exception.getClass().getSimpleName())));
        }

        try (Connection targetConnection = connectionProvider.openConnection(target.getId(), false)) {
            checks.add(DataSourceCdcReadinessResult.CheckItem.passed(
                    "CDC_TARGET_CONNECTION_READY", CATEGORY_TARGET,
                    "目标端数据库连接成功。", Map.of()));
            if (targetConnection.isReadOnly()) {
                checks.add(DataSourceCdcReadinessResult.CheckItem.failed(
                        "CDC_TARGET_CONNECTION_READ_ONLY", CATEGORY_TARGET,
                        "目标端连接处于只读状态，无法应用实时变更。",
                        "请为目标端配置具备 INSERT/UPDATE/DELETE 权限的专用写入账号。", Map.of()));
            } else {
                checks.add(DataSourceCdcReadinessResult.CheckItem.passed(
                        "CDC_TARGET_CONNECTION_WRITABLE", CATEGORY_TARGET,
                        "目标端连接未被数据库标记为只读。", Map.of()));
            }
            probeTargetTables(targetConnection, targetType, mappings, checks);
        } catch (Exception exception) {
            checks.add(DataSourceCdcReadinessResult.CheckItem.failed(
                    "CDC_TARGET_CONNECTION_FAILED", CATEGORY_TARGET,
                    "无法连接目标端数据库，未能检查目标表和冲突键。",
                    "请检查数据源连接、写入账号权限和网络后重新测试。",
                    Map.of("errorType", exception.getClass().getSimpleName())));
        }
    }

    private void probeSourceServer(Connection connection,
                                   DataSourceType sourceType,
                                   List<DataSourceCdcReadinessResult.CheckItem> checks) throws SQLException {
        if (sourceType == DataSourceType.MYSQL) {
            Map<String, String> variables = new LinkedHashMap<>();
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (ResultSet resultSet = statement.executeQuery(
                        "SHOW VARIABLES WHERE Variable_name IN ('log_bin','binlog_format','binlog_row_image')")) {
                    while (resultSet.next()) {
                        variables.put(resultSet.getString(1).toLowerCase(Locale.ROOT), resultSet.getString(2));
                    }
                }
            }
            addSettingCheck(checks, "CDC_MYSQL_LOG_BIN", "log_bin", variables.get("log_bin"), "ON",
                    "MySQL binlog 已开启。", "MySQL 未开启 binlog。",
                    "请由 DBA 设置 log_bin=ON 并按数据库要求重启实例。");
            addSettingCheck(checks, "CDC_MYSQL_BINLOG_FORMAT", "binlog_format", variables.get("binlog_format"), "ROW",
                    "MySQL binlog_format=ROW。", "MySQL binlog_format 不是 ROW。",
                    "请由 DBA 设置 binlog_format=ROW，避免基于语句的变更无法可靠还原行事件。");
            addSettingCheck(checks, "CDC_MYSQL_BINLOG_ROW_IMAGE", "binlog_row_image",
                    variables.get("binlog_row_image"), "FULL",
                    "MySQL binlog_row_image=FULL。", "MySQL binlog_row_image 不是 FULL。",
                    "请由 DBA 设置 binlog_row_image=FULL，确保更新前后字段足以构造幂等变更。");
            return;
        }

        String walLevel = scalar(connection, "SELECT current_setting('wal_level')");
        addSettingCheck(checks, "CDC_POSTGRES_WAL_LEVEL", "wal_level", walLevel, "logical",
                "PostgreSQL wal_level=logical。", "PostgreSQL wal_level 不是 logical。",
                "请由 DBA 设置 wal_level=logical，并按数据库要求重启实例。");
        boolean replicationAllowed = Boolean.parseBoolean(scalar(connection,
                "SELECT (rolreplication OR rolsuper)::text FROM pg_roles WHERE rolname = current_user"));
        checks.add(replicationAllowed
                ? DataSourceCdcReadinessResult.CheckItem.passed(
                "CDC_POSTGRES_REPLICATION_PRIVILEGE", CATEGORY_SOURCE,
                "PostgreSQL 源端账号具备逻辑复制权限。", Map.of())
                : DataSourceCdcReadinessResult.CheckItem.failed(
                "CDC_POSTGRES_REPLICATION_PRIVILEGE", CATEGORY_SOURCE,
                "PostgreSQL 源端账号不具备逻辑复制权限。",
                "请由 DBA 为专用 CDC 账号授予 REPLICATION，或使用受控的复制角色。", Map.of()));
    }

    private void probeSourceTables(Connection connection,
                                   DataSourceType type,
                                   List<DataSourceCdcReadinessRequest.ObjectMapping> mappings,
                                   List<DataSourceCdcReadinessResult.CheckItem> checks) throws SQLException {
        for (DataSourceCdcReadinessRequest.ObjectMapping mapping : mappings) {
            JdbcObject object = resolveObject(connection, type, mapping.getSourceSchemaName(),
                    mapping.getSourceObjectName());
            if (!tableExists(connection.getMetaData(), object)) {
                checks.add(DataSourceCdcReadinessResult.CheckItem.failed(
                        "CDC_SOURCE_TABLE_NOT_FOUND", CATEGORY_SOURCE,
                        "源表 " + object.displayName() + " 不存在或当前账号不可见。",
                        "请修正对象映射，或为源端账号授予该表的结构读取权限。",
                        Map.of("objectName", object.displayName())));
                continue;
            }
            List<String> primaryKeys = primaryKeys(connection.getMetaData(), object);
            checks.add(primaryKeys.isEmpty()
                    ? DataSourceCdcReadinessResult.CheckItem.failed(
                    "CDC_SOURCE_PRIMARY_KEY_MISSING", CATEGORY_SOURCE,
                    "源表 " + object.displayName() + " 没有主键，无法可靠识别更新和删除事件。",
                    "请为源表建立稳定主键，或不要把该表纳入实时同步。",
                    Map.of("objectName", object.displayName()))
                    : DataSourceCdcReadinessResult.CheckItem.passed(
                    "CDC_SOURCE_PRIMARY_KEY_PRESENT", CATEGORY_SOURCE,
                    "源表 " + object.displayName() + " 具有主键。",
                    Map.of("objectName", object.displayName(), "primaryKeyColumnCount", primaryKeys.size())));
        }
    }

    private void probeTargetTables(Connection connection,
                                   DataSourceType type,
                                   List<DataSourceCdcReadinessRequest.ObjectMapping> mappings,
                                   List<DataSourceCdcReadinessResult.CheckItem> checks) throws SQLException {
        for (DataSourceCdcReadinessRequest.ObjectMapping mapping : mappings) {
            JdbcObject object = resolveObject(connection, type, mapping.getTargetSchemaName(),
                    mapping.getTargetObjectName());
            if (!tableExists(connection.getMetaData(), object)) {
                checks.add(DataSourceCdcReadinessResult.CheckItem.failed(
                        "CDC_TARGET_TABLE_NOT_FOUND", CATEGORY_TARGET,
                        "目标表 " + object.displayName() + " 不存在或当前账号不可见。",
                        "请先创建并确认目标表结构，或修改对象映射后重新检查。",
                        Map.of("objectName", object.displayName())));
                continue;
            }
            int keyCount = primaryKeys(connection.getMetaData(), object).size();
            boolean hasUniqueKey = keyCount > 0 || hasUniqueIndex(connection.getMetaData(), object);
            checks.add(hasUniqueKey
                    ? DataSourceCdcReadinessResult.CheckItem.passed(
                    "CDC_TARGET_CONFLICT_KEY_PRESENT", CATEGORY_TARGET,
                    "目标表 " + object.displayName() + " 具有主键或唯一键，可进行幂等 MERGE。",
                    Map.of("objectName", object.displayName(), "primaryKeyColumnCount", keyCount))
                    : DataSourceCdcReadinessResult.CheckItem.failed(
                    "CDC_TARGET_CONFLICT_KEY_MISSING", CATEGORY_TARGET,
                    "目标表 " + object.displayName() + " 没有主键或唯一键，无法稳定执行 MERGE。",
                    "请为目标表配置与业务标识匹配的主键或唯一键，再重新检查。",
                    Map.of("objectName", object.displayName())));
        }
    }

    private JdbcObject resolveObject(Connection connection,
                                     DataSourceType type,
                                     String schemaName,
                                     String tableName) throws SQLException {
        if (type == DataSourceType.MYSQL) {
            return new JdbcObject(firstNonBlank(schemaName, connection.getCatalog()), null, tableName);
        }
        return new JdbcObject(null, firstNonBlank(schemaName, "public"), tableName);
    }

    private boolean tableExists(DatabaseMetaData metadata, JdbcObject object) throws SQLException {
        try (ResultSet resultSet = metadata.getTables(
                object.catalog(), object.schema(), object.table(), new String[]{"TABLE"})) {
            while (resultSet.next()) {
                if (object.table().equalsIgnoreCase(resultSet.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> primaryKeys(DatabaseMetaData metadata, JdbcObject object) throws SQLException {
        Set<String> keys = new LinkedHashSet<>();
        try (ResultSet resultSet = metadata.getPrimaryKeys(object.catalog(), object.schema(), object.table())) {
            while (resultSet.next()) {
                String column = resultSet.getString("COLUMN_NAME");
                if (column != null && !column.isBlank()) {
                    keys.add(column);
                }
            }
        }
        return List.copyOf(keys);
    }

    private boolean hasUniqueIndex(DatabaseMetaData metadata, JdbcObject object) throws SQLException {
        try (ResultSet resultSet = metadata.getIndexInfo(
                object.catalog(), object.schema(), object.table(), true, false)) {
            while (resultSet.next()) {
                String indexName = resultSet.getString("INDEX_NAME");
                String columnName = resultSet.getString("COLUMN_NAME");
                if (indexName != null && columnName != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                return resultSet.next() ? String.valueOf(resultSet.getObject(1)) : "";
            }
        }
    }

    private void addSettingCheck(List<DataSourceCdcReadinessResult.CheckItem> checks,
                                 String code,
                                 String settingName,
                                 String actualValue,
                                 String expectedValue,
                                 String passedMessage,
                                 String failedMessage,
                                 String recommendation) {
        boolean passed = expectedValue.equalsIgnoreCase(String.valueOf(actualValue));
        Map<String, Object> details = Map.of(
                "setting", settingName,
                "expected", expectedValue,
                "actual", actualValue == null ? "UNAVAILABLE" : actualValue);
        checks.add(passed
                ? DataSourceCdcReadinessResult.CheckItem.passed(code, CATEGORY_SOURCE, passedMessage, details)
                : DataSourceCdcReadinessResult.CheckItem.failed(
                code, CATEGORY_SOURCE, failedMessage, recommendation, details));
    }

    private boolean noScopeFailure(List<DataSourceCdcReadinessResult.CheckItem> checks) {
        return checks.stream().noneMatch(item -> "FAILED".equals(item.status()));
    }

    private String sideCode(String side) {
        return "源端".equals(side) ? "SOURCE" : "TARGET";
    }

    private String sideCategory(String side) {
        return "源端".equals(side) ? CATEGORY_SOURCE : CATEGORY_TARGET;
    }

    private String safeType(DataSourceConfig datasource) {
        return datasource.getType() == null ? "UNKNOWN" : datasource.getType();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private record JdbcObject(String catalog, String schema, String table) {
        private String displayName() {
            return String.join(".", java.util.stream.Stream.of(catalog, schema, table)
                    .filter(value -> value != null && !value.isBlank())
                    .toList());
        }
    }
}

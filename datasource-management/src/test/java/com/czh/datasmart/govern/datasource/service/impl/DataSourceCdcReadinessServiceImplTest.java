/**
 * @Author : Cui
 * @Date: 2026/07/27 20:00
 * @Description DataSmart Govern Backend - DataSourceCdcReadinessServiceImplTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.impl;

import com.czh.datasmart.govern.datasource.controller.dto.DataSourceCdcReadinessRequest;
import com.czh.datasmart.govern.datasource.controller.dto.DataSourceCdcReadinessResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncJdbcConnectionProvider;
import com.czh.datasmart.govern.datasource.service.support.CdcInfrastructureReadinessProbe;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies that observable prerequisites pass but the missing CDC runtime remains fail-closed. */
class DataSourceCdcReadinessServiceImplTest {

    @Test
    void shouldProbeDatabaseKeysAndSettingsButBlockMissingPipelineRuntime() throws Exception {
        SyncJdbcConnectionProvider connections = mock(SyncJdbcConnectionProvider.class);
        CdcInfrastructureReadinessProbe infrastructure = mock(CdcInfrastructureReadinessProbe.class);
        Connection sourceConnection = mock(Connection.class);
        Connection targetConnection = mock(Connection.class);
        DatabaseMetaData sourceMetadata = mock(DatabaseMetaData.class);
        DatabaseMetaData targetMetadata = mock(DatabaseMetaData.class);
        Statement sourceStatement = mock(Statement.class);
        ResultSet variables = rows(
                new String[][]{{"log_bin", "ON"}, {"binlog_format", "ROW"}, {"binlog_row_image", "FULL"}});
        ResultSet sourceTable = table("customer_source");
        ResultSet sourcePrimaryKey = primaryKey("id");
        ResultSet targetTable = table("customer_target");
        ResultSet targetPrimaryKey = primaryKey("id");

        when(connections.openConnection(11L, true)).thenReturn(sourceConnection);
        when(connections.openConnection(22L, false)).thenReturn(targetConnection);
        when(sourceConnection.getCatalog()).thenReturn("sales");
        when(sourceConnection.getMetaData()).thenReturn(sourceMetadata);
        when(sourceConnection.createStatement()).thenReturn(sourceStatement);
        when(sourceStatement.executeQuery(anyString())).thenReturn(variables);
        when(targetConnection.getMetaData()).thenReturn(targetMetadata);
        when(targetConnection.isReadOnly()).thenReturn(false);

        when(sourceMetadata.getTables(eq("sales"), eq(null), eq("customer_source"), any(String[].class)))
                .thenReturn(sourceTable);
        when(sourceMetadata.getPrimaryKeys("sales", null, "customer_source"))
                .thenReturn(sourcePrimaryKey);
        when(targetMetadata.getTables(eq(null), eq("public"), eq("customer_target"), any(String[].class)))
                .thenReturn(targetTable);
        when(targetMetadata.getPrimaryKeys(null, "public", "customer_target"))
                .thenReturn(targetPrimaryKey);
        when(infrastructure.probe()).thenReturn(List.of(
                DataSourceCdcReadinessResult.CheckItem.passed(
                        "CDC_KAFKA_REACHABLE", "INFRASTRUCTURE", "Kafka 可达", Map.of()),
                DataSourceCdcReadinessResult.CheckItem.passed(
                        "CDC_DEBEZIUM_PLUGIN_AVAILABLE", "INFRASTRUCTURE", "Debezium 可用", Map.of())
        ));

        DataSourceCdcReadinessServiceImpl service =
                new DataSourceCdcReadinessServiceImpl(connections, infrastructure);
        DataSourceCdcReadinessResult result = service.check(
                datasource(11L, "MYSQL", "SOURCE"),
                datasource(22L, "POSTGRESQL", "TARGET"),
                request());

        assertFalse(result.ready());
        assertTrue(result.issueCodes().contains("CDC_PIPELINE_RUNTIME_NOT_IMPLEMENTED"));
        assertTrue(result.checks().stream().anyMatch(item ->
                "CDC_SOURCE_PRIMARY_KEY_PRESENT".equals(item.code()) && "PASSED".equals(item.status())));
        assertTrue(result.checks().stream().anyMatch(item ->
                "CDC_TARGET_CONFLICT_KEY_PRESENT".equals(item.code()) && "PASSED".equals(item.status())));
        assertTrue(result.checks().stream().anyMatch(item ->
                "CDC_MYSQL_BINLOG_FORMAT".equals(item.code()) && "PASSED".equals(item.status())));
    }

    private DataSourceConfig datasource(Long id, String type, String purpose) {
        DataSourceConfig config = new DataSourceConfig();
        config.setId(id);
        config.setTenantId(10L);
        config.setProjectId(101L);
        config.setType(type);
        config.setUsagePurpose(purpose);
        config.setStatus("ACTIVE");
        return config;
    }

    private DataSourceCdcReadinessRequest request() {
        DataSourceCdcReadinessRequest.ObjectMapping mapping = new DataSourceCdcReadinessRequest.ObjectMapping();
        mapping.setSourceObjectName("customer_source");
        mapping.setTargetSchemaName("public");
        mapping.setTargetObjectName("customer_target");
        DataSourceCdcReadinessRequest request = new DataSourceCdcReadinessRequest();
        request.setTargetDatasourceId(22L);
        request.setObjectMappings(List.of(mapping));
        return request;
    }

    private ResultSet rows(String[][] rows) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        Boolean[] next = new Boolean[rows.length + 1];
        for (int index = 0; index < rows.length; index++) {
            next[index] = true;
        }
        next[rows.length] = false;
        when(resultSet.next()).thenReturn(next[0], java.util.Arrays.copyOfRange(next, 1, next.length));
        String[] first = java.util.Arrays.stream(rows).map(row -> row[0]).toArray(String[]::new);
        String[] second = java.util.Arrays.stream(rows).map(row -> row[1]).toArray(String[]::new);
        when(resultSet.getString(1)).thenReturn(first[0], java.util.Arrays.copyOfRange(first, 1, first.length));
        when(resultSet.getString(2)).thenReturn(second[0], java.util.Arrays.copyOfRange(second, 1, second.length));
        return resultSet;
    }

    private ResultSet table(String tableName) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("TABLE_NAME")).thenReturn(tableName);
        return resultSet;
    }

    private ResultSet primaryKey(String columnName) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("COLUMN_NAME")).thenReturn(columnName);
        return resultSet;
    }
}

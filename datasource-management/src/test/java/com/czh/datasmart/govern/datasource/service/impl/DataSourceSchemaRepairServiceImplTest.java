/**
 * @Author : Cui
 * @Date: 2026/07/27 18:10
 * @Description DataSmart Govern Backend - DataSourceSchemaRepairServiceImplTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.impl;

import com.czh.datasmart.govern.datasource.controller.dto.DataSourceSchemaRepairApplyRequest;
import com.czh.datasmart.govern.datasource.controller.dto.DataSourceSchemaRepairPreviewRequest;
import com.czh.datasmart.govern.datasource.controller.dto.DataSourceSchemaRepairResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.DataSourceSchemaRepairPlan;
import com.czh.datasmart.govern.datasource.mapper.DataSourceSchemaRepairPlanMapper;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncJdbcConnectionProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies digest-bound CREATE_TABLE SQL generation without a live customer database. */
class DataSourceSchemaRepairServiceImplTest {

    @Test
    void shouldCreateOnlyThePreviewedEmptyTableFromAllowListedColumns() throws Exception {
        SyncJdbcConnectionProvider connectionProvider = mock(SyncJdbcConnectionProvider.class);
        DataSourceSchemaRepairPlanMapper mapper = mock(DataSourceSchemaRepairPlanMapper.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Statement statement = mock(Statement.class);
        AtomicReference<DataSourceSchemaRepairPlan> persistedPlan = new AtomicReference<>();

        when(connectionProvider.openConnection(22L, false)).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getSchema()).thenReturn("public");
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(metadata.getIdentifierQuoteString()).thenReturn("\"");
        when(metadata.getTables(
                isNull(String.class),
                eq("public"),
                eq("customer_target"),
                isNull(String[].class))).thenAnswer(invocation -> emptyResultSet());
        when(metadata.getTables(
                isNull(String.class),
                eq("public"),
                eq("customer_target"),
                argThat(types -> Arrays.equals(types, new String[]{"TABLE"}))))
                .thenAnswer(invocation -> tableResultSet("customer_target"));
        when(metadata.getColumns(isNull(), eq("public"), eq("customer_target"), eq("id")))
                .thenAnswer(invocation -> columnResultSet("id", Types.BIGINT, "int8", 19, false));
        when(metadata.getColumns(isNull(), eq("public"), eq("customer_target"), eq("name")))
                .thenAnswer(invocation -> columnResultSet("name", Types.VARCHAR, "varchar", 120, true));
        when(mapper.insert(any(DataSourceSchemaRepairPlan.class))).thenAnswer(invocation -> {
            DataSourceSchemaRepairPlan plan = invocation.getArgument(0);
            plan.setId(91L);
            persistedPlan.set(plan);
            return 1;
        });
        when(mapper.selectById(91L)).thenAnswer(invocation -> persistedPlan.get());
        when(mapper.updateById(any(DataSourceSchemaRepairPlan.class))).thenReturn(1);

        DataSourceSchemaRepairServiceImpl service = new DataSourceSchemaRepairServiceImpl(
                connectionProvider,
                mapper,
                new ObjectMapper().findAndRegisterModules());
        DataSourceSchemaRepairResult preview = service.preview(
                datasource(), previewRequest(), 1001L);

        assertThat(preview.getPlanStatus()).isEqualTo("PREVIEWED");
        assertThat(preview.getCurrentDefinition()).isEqualTo("TABLE_ABSENT");
        assertThat(preview.getRequestedDefinition())
                .contains("id BIGINT NOT NULL PRIMARY KEY")
                .contains("name VARCHAR(120) NULL");
        assertThat(persistedPlan.get().getColumnsJson()).doesNotContain("ddl", "AUTO_INCREMENT");

        DataSourceSchemaRepairApplyRequest applyRequest = new DataSourceSchemaRepairApplyRequest();
        applyRequest.setPlanId(preview.getPlanId());
        applyRequest.setConfirmationDigest(preview.getConfirmationDigest());
        applyRequest.setConfirmed(true);
        DataSourceSchemaRepairResult applied = service.apply(datasource(), applyRequest, 1001L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement).execute(sql.capture());
        assertThat(sql.getValue()).isEqualTo(
                "CREATE TABLE \"public\".\"customer_target\" "
                        + "(\"id\" BIGINT NOT NULL, \"name\" VARCHAR(120), PRIMARY KEY (\"id\"))");
        assertThat(sql.getValue()).doesNotContain("DROP", "DEFAULT", "AUTO_INCREMENT");
        assertThat(applied.getPlanStatus()).isEqualTo("APPLIED");
        assertThat(applied.getCurrentDefinition()).isEqualTo("TABLE_CREATED");
    }

    private DataSourceConfig datasource() {
        DataSourceConfig datasource = new DataSourceConfig();
        datasource.setId(22L);
        datasource.setTenantId(10L);
        datasource.setProjectId(101L);
        datasource.setType("POSTGRESQL");
        return datasource;
    }

    private DataSourceSchemaRepairPreviewRequest previewRequest() {
        DataSourceSchemaRepairPreviewRequest request = new DataSourceSchemaRepairPreviewRequest();
        request.setOperation("CREATE_TABLE");
        request.setSchemaName("public");
        request.setTableName("customer_target");

        DataSourceSchemaRepairPreviewRequest.CreateTableColumn id =
                new DataSourceSchemaRepairPreviewRequest.CreateTableColumn();
        id.setColumnName("id");
        id.setDataType("BIGINT");
        id.setNullable(false);
        id.setPrimaryKey(true);

        DataSourceSchemaRepairPreviewRequest.CreateTableColumn name =
                new DataSourceSchemaRepairPreviewRequest.CreateTableColumn();
        name.setColumnName("name");
        name.setDataType("VARCHAR");
        name.setLength(120);
        name.setNullable(true);
        request.setColumns(java.util.List.of(id, name));
        return request;
    }

    private ResultSet emptyResultSet() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenReturn(false);
        return resultSet;
    }

    private ResultSet tableResultSet(String tableName) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("TABLE_NAME")).thenReturn(tableName);
        return resultSet;
    }

    private ResultSet columnResultSet(String name,
                                      int jdbcType,
                                      String typeName,
                                      int size,
                                      boolean nullable) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("COLUMN_NAME")).thenReturn(name);
        when(resultSet.getInt("DATA_TYPE")).thenReturn(jdbcType);
        when(resultSet.getString("TYPE_NAME")).thenReturn(typeName);
        when(resultSet.getInt("COLUMN_SIZE")).thenReturn(size);
        when(resultSet.getInt("DECIMAL_DIGITS")).thenReturn(0);
        when(resultSet.getInt("NULLABLE")).thenReturn(
                nullable ? DatabaseMetaData.columnNullable : DatabaseMetaData.columnNoNulls);
        when(resultSet.wasNull()).thenReturn(false);
        when(resultSet.getString("IS_AUTOINCREMENT")).thenReturn("NO");
        return resultSet;
    }
}

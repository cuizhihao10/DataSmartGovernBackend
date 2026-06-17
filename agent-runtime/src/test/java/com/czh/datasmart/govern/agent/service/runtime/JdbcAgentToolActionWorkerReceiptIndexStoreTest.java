/**
 * @Author : Cui
 * @Date: 2026/06/18 00:00
 * @Description DataSmart Govern Backend - JdbcAgentToolActionWorkerReceiptIndexStoreTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MySQL 版 worker receipt 低敏索引仓储测试。
 *
 * <p>这组测试不启动真实 MySQL，而是通过 Mockito 模拟 JDBC 对象，重点保护 Java 仓储层契约：</p>
 * <p>1. 无效 receipt 不写库；</p>
 * <p>2. upsert 只绑定低敏字段，不出现 message、payload、SQL、prompt、工具参数等额外列；</p>
 * <p>3. 查询时 tenant/project/actor/run/session/toolCode/authorizedProjectIds 会下沉为 SQL 参数；</p>
 * <p>4. PROJECT 授权项目为空时直接短路，避免越权场景下仍访问数据库；</p>
 * <p>5. size 诊断只读取数量，不读取任何 receipt 明细。</p>
 *
 * <p>真实唯一索引、执行计划和 MySQL 方言由 migration 脚本承载。后续如果项目引入 Testcontainers，
 * 可以再补集成测试验证 `ON DUPLICATE KEY UPDATE` 与组合索引在真实 MySQL 8.0 下的行为。</p>
 */
class JdbcAgentToolActionWorkerReceiptIndexStoreTest {

    @Test
    void upsertShouldSkipUnindexableRecord() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        JdbcAgentToolActionWorkerReceiptIndexStore store = store(dataSource);

        boolean inserted = store.upsert(new AgentToolActionWorkerReceiptIndexRecord(
                "receipt-unindexable",
                null,
                "10",
                "20",
                "1001",
                "run-a",
                "session-a",
                "datasource.metadata.read",
                "RUNNING",
                "DRY_RUN_PASSED",
                true,
                false,
                null,
                7L,
                Instant.parse("2026-06-18T00:00:00Z"),
                Instant.parse("2026-06-18T00:00:01Z")
        ));

        assertFalse(inserted);
        verify(dataSource, never()).getConnection();
    }

    @Test
    void upsertShouldBindOnlyLowSensitiveReceiptFields() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        JdbcAgentToolActionWorkerReceiptIndexStore store = store(dataSource);

        boolean inserted = store.upsert(record());

        assertTrue(inserted);
        verify(statement).setString(1, "receipt-ready");
        verify(statement).setString(2, "command-a");
        verify(statement).setString(3, "10");
        verify(statement).setString(4, "20");
        verify(statement).setString(5, "1001");
        verify(statement).setString(8, "datasource.metadata.read");
        verify(statement).setString(9, "RUNNING");
        verify(statement).setString(10, "DRY_RUN_PASSED");
        verify(statement).setBoolean(11, true);
        verify(statement).setBoolean(12, false);
        verify(statement).setLong(14, 7L);
        verify(statement).setTimestamp(eq(15), any(Timestamp.class));
        verify(statement).setTimestamp(eq(16), any(Timestamp.class));
        verify(statement, never()).setString(eq(17), anyString());
        verify(connection).close();
    }

    @Test
    void upsertShouldReturnFalseWhenMySqlReportsDuplicateUpdate() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(2);
        JdbcAgentToolActionWorkerReceiptIndexStore store = store(dataSource);

        boolean inserted = store.upsert(record());

        assertFalse(inserted);
    }

    @Test
    void queryByCommandIdShouldApplyScopeAndRestoreRecords() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        stubRow(resultSet);
        JdbcAgentToolActionWorkerReceiptIndexStore store = store(dataSource);

        List<AgentToolActionWorkerReceiptIndexRecord> records = store.queryByCommandId(query());

        assertEquals(1, records.size());
        assertEquals("command-a", records.getFirst().commandId());
        assertEquals("DRY_RUN_PASSED", records.getFirst().outcome());
        assertEquals("10", records.getFirst().tenantId());
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sqlCaptor.capture());
        assertTrue(sqlCaptor.getValue().contains("agent_tool_action_worker_receipt_index"));
        assertTrue(sqlCaptor.getValue().contains("project_id IN (?)"));
        assertTrue(sqlCaptor.getValue().contains("tool_code = ? OR tool_code IS NULL"));
        verify(statement).setString(1, "command-a");
        verify(statement).setString(2, "20");
        verify(statement).setString(3, "10");
        verify(statement).setString(4, "20");
        verify(statement).setString(5, "1001");
        verify(statement).setString(6, "run-a");
        verify(statement).setString(7, "session-a");
        verify(statement).setString(8, "datasource.metadata.read");
        verify(statement).setInt(9, 25);
    }

    @Test
    void queryByCommandIdShouldReturnEmptyWhenProjectScopeIsEmpty() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        JdbcAgentToolActionWorkerReceiptIndexStore store = store(dataSource);

        List<AgentToolActionWorkerReceiptIndexRecord> records = store.queryByCommandId(new AgentToolActionWorkerReceiptIndexQuery(
                "command-a",
                "datasource.metadata.read",
                "10",
                "20",
                "1001",
                "run-a",
                "session-a",
                List.of(),
                25
        ));

        assertTrue(records.isEmpty());
        verify(dataSource, never()).getConnection();
    }

    @Test
    void sizeShouldReturnDatabaseCountWithoutReadingReceiptDetails() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong(1)).thenReturn(42L);
        JdbcAgentToolActionWorkerReceiptIndexStore store = store(dataSource);

        int size = store.size();

        assertEquals(42, size);
        verify(resultSet, never()).getString(anyString());
    }

    private JdbcAgentToolActionWorkerReceiptIndexStore store(DataSource dataSource) {
        AgentRuntimePersistenceProperties properties = new AgentRuntimePersistenceProperties();
        properties.setDatabaseEnabled(true);
        properties.getJdbc().setMaxQueryLimit(1000);
        AgentRuntimeJdbcConnectionManager connectionManager =
                new AgentRuntimeJdbcConnectionManager(dataSource, properties);
        return new JdbcAgentToolActionWorkerReceiptIndexStore(connectionManager, properties);
    }

    private AgentToolActionWorkerReceiptIndexQuery query() {
        return new AgentToolActionWorkerReceiptIndexQuery(
                "command-a",
                "datasource.metadata.read",
                "10",
                "20",
                "1001",
                "run-a",
                "session-a",
                List.of("20"),
                25
        );
    }

    private AgentToolActionWorkerReceiptIndexRecord record() {
        return new AgentToolActionWorkerReceiptIndexRecord(
                "receipt-ready",
                "command-a",
                "10",
                "20",
                "1001",
                "run-a",
                "session-a",
                "datasource.metadata.read",
                "RUNNING",
                "DRY_RUN_PASSED",
                true,
                false,
                null,
                7L,
                Instant.parse("2026-06-18T00:00:00Z"),
                Instant.parse("2026-06-18T00:00:01Z")
        );
    }

    private void stubRow(ResultSet resultSet) throws SQLException {
        when(resultSet.getString("event_identity_key")).thenReturn("receipt-ready");
        when(resultSet.getString("command_id")).thenReturn("command-a");
        when(resultSet.getString("tenant_id")).thenReturn("10");
        when(resultSet.getString("project_id")).thenReturn("20");
        when(resultSet.getString("actor_id")).thenReturn("1001");
        when(resultSet.getString("run_id")).thenReturn("run-a");
        when(resultSet.getString("session_id")).thenReturn("session-a");
        when(resultSet.getString("tool_code")).thenReturn("datasource.metadata.read");
        when(resultSet.getString("task_status")).thenReturn("RUNNING");
        when(resultSet.getString("outcome")).thenReturn("DRY_RUN_PASSED");
        when(resultSet.getBoolean("pre_check_passed")).thenReturn(true);
        when(resultSet.getBoolean("side_effect_executed")).thenReturn(false);
        when(resultSet.getString("error_code")).thenReturn(null);
        when(resultSet.getLong("replay_sequence")).thenReturn(7L);
        when(resultSet.wasNull()).thenReturn(false);
        when(resultSet.getTimestamp("consumed_at"))
                .thenReturn(Timestamp.from(Instant.parse("2026-06-18T00:00:00Z")));
        when(resultSet.getTimestamp("indexed_at"))
                .thenReturn(Timestamp.from(Instant.parse("2026-06-18T00:00:01Z")));
    }
}

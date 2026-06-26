/**
 * @Author : Cui
 * @Date: 2026/06/27 00:31
 * @Description DataSmart Govern Backend - JdbcAgentToolActionArtifactBodyReadGrantStoreTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MySQL 版 artifact 正文读取 grant fact 仓储测试。
 *
 * <p>测试使用 Mockito 模拟 JDBC 对象，不启动真实 MySQL。这样可以快速验证仓储契约：
 * 1. save 只绑定低敏控制面字段；
 * 2. findByReference 能从 ResultSet 还原 record；
 * 3. query 会把 tenant/project/authorizedProjectIds/status/limit 下沉到 SQL 参数；
 * 4. revoke 在事务中先查可撤销事实，再把状态更新为 REVOKED；
 * 5. size 只做数量诊断，不读取事实详情。</p>
 */
class JdbcAgentToolActionArtifactBodyReadGrantStoreTest {

    @Test
    void saveShouldBindLowSensitiveGrantFactFields() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        JdbcAgentToolActionArtifactBodyReadGrantStore store = store(dataSource);

        store.save(record());

        verify(statement).setString(1, "artifact-body-grant-decision:sha256:grant-a");
        verify(statement).setString(2, "cmd-worker-001");
        verify(statement).setString(3, "agent-artifact:run-command/receipt-001");
        verify(statement).setString(4, "MINIO_OBJECT");
        verify(statement).setString(5, "TASK_RESULT_VIEW");
        verify(statement).setString(6, "OBJECT_STORE_BODY_READ_AFTER_STORE_POLICY");
        verify(statement).setInt(7, 65536);
        verify(statement).setString(8, "10");
        verify(statement).setString(9, "20");
        verify(statement).setString(10, "30");
        verify(statement).setString(13, "command.run-program");
        verify(statement).setString(14, "receipt-fingerprint-a");
        verify(statement).setLong(15, 101L);
        verify(statement).setString(16, "EXECUTION_SUCCEEDED");
        verify(statement).setTimestamp(eq(17), any(Timestamp.class));
        verify(statement).setTimestamp(eq(18), any(Timestamp.class));
        verify(statement).setString(19, "ACTIVE");
        verify(statement, never()).setString(eq(23), anyString());
        verify(connection).close();
    }

    @Test
    void findByReferenceShouldRestoreGrantFact() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        stubRow(resultSet);
        JdbcAgentToolActionArtifactBodyReadGrantStore store = store(dataSource);

        Optional<AgentToolActionArtifactBodyReadGrantRecord> record =
                store.findByReference("artifact-body-grant-decision:sha256:grant-a");

        assertTrue(record.isPresent());
        assertEquals("cmd-worker-001", record.get().commandId());
        assertEquals("20", record.get().projectId());
        assertEquals(AgentToolActionArtifactBodyReadGrantStatus.ACTIVE, record.get().status());
        verify(statement).setString(1, "artifact-body-grant-decision:sha256:grant-a");
        verify(statement).setInt(2, 1);
    }

    @Test
    void queryShouldBindScopeFiltersAndLimit() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);
        JdbcAgentToolActionArtifactBodyReadGrantStore store = store(dataSource);

        List<AgentToolActionArtifactBodyReadGrantRecord> records = store.query(
                new AgentToolActionArtifactBodyReadGrantQuery(
                        null,
                        "cmd-worker-001",
                        null,
                        "10",
                        "20",
                        null,
                        null,
                        null,
                        "command.run-program",
                        "ACTIVE",
                        List.of("20"),
                        20
                ),
                20
        );

        assertTrue(records.isEmpty());
        verify(statement).setString(1, "cmd-worker-001");
        verify(statement).setString(2, "10");
        verify(statement).setString(3, "20");
        verify(statement).setString(4, "20");
        verify(statement).setString(5, "command.run-program");
        verify(statement).setString(6, "ACTIVE");
        verify(statement).setInt(7, 20);
    }

    @Test
    void revokeShouldUpdateStatusInsideTransaction() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement selectStatement = mock(PreparedStatement.class);
        PreparedStatement upsertStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(selectStatement, upsertStatement);
        when(selectStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        stubRow(resultSet);
        when(upsertStatement.executeUpdate()).thenReturn(2);
        JdbcAgentToolActionArtifactBodyReadGrantStore store = store(dataSource);

        Optional<AgentToolActionArtifactBodyReadGrantRecord> revoked = store.revoke(
                "artifact-body-grant-decision:sha256:grant-a",
                "30",
                "RISK_POLICY_CHANGED",
                1_780_000_100_000L
        );

        assertTrue(revoked.isPresent());
        assertEquals(AgentToolActionArtifactBodyReadGrantStatus.REVOKED, revoked.get().status());
        assertEquals("30", revoked.get().revokedBy());
        verify(upsertStatement).setString(19, "REVOKED");
        verify(upsertStatement).setTimestamp(eq(20), any(Timestamp.class));
        verify(upsertStatement).setString(21, "30");
        verify(upsertStatement).setString(22, "RISK_POLICY_CHANGED");
        verify(connection).setAutoCommit(false);
        verify(connection).commit();
        verify(connection).setAutoCommit(true);
    }

    @Test
    void sizeShouldReturnDatabaseCountWithoutReadingDetails() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong(1)).thenReturn(12L);
        JdbcAgentToolActionArtifactBodyReadGrantStore store = store(dataSource);

        int size = store.size();

        assertEquals(12, size);
        verify(resultSet, never()).getString(anyString());
    }

    private JdbcAgentToolActionArtifactBodyReadGrantStore store(DataSource dataSource) {
        AgentRuntimePersistenceProperties properties = new AgentRuntimePersistenceProperties();
        properties.setDatabaseEnabled(true);
        properties.getJdbc().setMaxQueryLimit(1000);
        AgentRuntimeJdbcConnectionManager connectionManager =
                new AgentRuntimeJdbcConnectionManager(dataSource, properties);
        return new JdbcAgentToolActionArtifactBodyReadGrantStore(connectionManager, properties);
    }

    private AgentToolActionArtifactBodyReadGrantRecord record() {
        return new AgentToolActionArtifactBodyReadGrantRecord(
                "artifact-body-grant-decision:sha256:grant-a",
                "cmd-worker-001",
                "agent-artifact:run-command/receipt-001",
                "MINIO_OBJECT",
                "TASK_RESULT_VIEW",
                "OBJECT_STORE_BODY_READ_AFTER_STORE_POLICY",
                65536,
                "10",
                "20",
                "30",
                "run-command",
                "session-command",
                "command.run-program",
                "receipt-fingerprint-a",
                101L,
                "EXECUTION_SUCCEEDED",
                1_780_000_000_000L,
                1_780_000_600_000L,
                AgentToolActionArtifactBodyReadGrantStatus.ACTIVE,
                null,
                null,
                null
        );
    }

    private void stubRow(ResultSet resultSet) throws SQLException {
        when(resultSet.getString("grant_decision_reference"))
                .thenReturn("artifact-body-grant-decision:sha256:grant-a");
        when(resultSet.getString("command_id")).thenReturn("cmd-worker-001");
        when(resultSet.getString("artifact_reference")).thenReturn("agent-artifact:run-command/receipt-001");
        when(resultSet.getString("artifact_reference_type")).thenReturn("MINIO_OBJECT");
        when(resultSet.getString("read_purpose")).thenReturn("TASK_RESULT_VIEW");
        when(resultSet.getString("requested_content_mode"))
                .thenReturn("OBJECT_STORE_BODY_READ_AFTER_STORE_POLICY");
        when(resultSet.getInt("max_readable_bytes")).thenReturn(65536);
        when(resultSet.getString("tenant_id")).thenReturn("10");
        when(resultSet.getString("project_id")).thenReturn("20");
        when(resultSet.getString("actor_id")).thenReturn("30");
        when(resultSet.getString("run_id")).thenReturn("run-command");
        when(resultSet.getString("session_id")).thenReturn("session-command");
        when(resultSet.getString("tool_code")).thenReturn("command.run-program");
        when(resultSet.getString("matched_receipt_fingerprint")).thenReturn("receipt-fingerprint-a");
        when(resultSet.getLong("replay_sequence")).thenReturn(101L);
        when(resultSet.wasNull()).thenReturn(false);
        when(resultSet.getString("receipt_outcome")).thenReturn("EXECUTION_SUCCEEDED");
        when(resultSet.getTimestamp("issued_at"))
                .thenReturn(Timestamp.from(Instant.ofEpochMilli(1_780_000_000_000L)));
        when(resultSet.getTimestamp("expires_at"))
                .thenReturn(Timestamp.from(Instant.ofEpochMilli(1_780_000_600_000L)));
        when(resultSet.getString("status")).thenReturn("ACTIVE");
        when(resultSet.getTimestamp("revoked_at")).thenReturn(null);
        when(resultSet.getString("revoked_by")).thenReturn(null);
        when(resultSet.getString("revoke_reason_code")).thenReturn(null);
    }
}

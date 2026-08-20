/**
 * @Author : Cui
 * @Date: 2026/08/20 00:20
 * @Description DataSmart Govern Backend - JdbcAgentCommandTaskFinalStateCallbackJobStoreTest.java
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PostgreSQL callback job 仓储的最小幂等回归。
 *
 * <p>测试不启动真实数据库，只验证 Java Store 对 PostgreSQL 唯一键冲突的业务解释：重复 receipt
 * 代表另一个实例已经完成发现，不应让后台调度器报错或重新触发副作用。</p>
 */
class JdbcAgentCommandTaskFinalStateCallbackJobStoreTest {

    /**
     * callback 候选必须使用数据库 id 作为 durable sequence，而不是会在 JVM 重启后归零的 projection sequence。
     */
    @Test
    void shouldProjectDatabaseIdentityAsDurableReceiptSequence() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        stubTerminalReceipt(resultSet);
        JdbcAgentCommandTaskFinalStateCallbackJobStore store = new JdbcAgentCommandTaskFinalStateCallbackJobStore(
                new AgentRuntimeJdbcConnectionManager(dataSource, new AgentRuntimePersistenceProperties())
        );

        List<AgentToolActionWorkerReceiptIndexRecord> receipts =
                store.listUnregisteredTerminalReceiptCandidates(10);

        assertEquals(1, receipts.size());
        assertEquals(901L, receipts.getFirst().replaySequence());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("r.id AS replay_sequence"));
        assertTrue(sql.getValue().contains("ORDER BY r.id"));
    }

    @Test
    void shouldTreatPostgreSqlUniqueViolationAsAnAlreadyRegisteredReceipt() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement insert = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(insert);
        when(insert.executeUpdate()).thenThrow(new SQLException("duplicate receipt", "23505"));

        JdbcAgentCommandTaskFinalStateCallbackJobStore store = new JdbcAgentCommandTaskFinalStateCallbackJobStore(
                new AgentRuntimeJdbcConnectionManager(dataSource, new AgentRuntimePersistenceProperties())
        );

        boolean appended = store.append(job(), "CALLBACK_DISCOVERED", "JAVA_TERMINAL_WORKER_RECEIPT",
                Instant.parse("2026-08-20T00:20:00Z"));

        assertFalse(appended);
        verify(connection).rollback();
        verify(connection).close();
    }

    /**
     * 旧 owner/token 在 lease 到期后不能续租；SQL 必须把到期时间纳入同一次条件 UPDATE。
     */
    @Test
    void shouldFenceHeartbeatWithAnUnexpiredLeaseCondition() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement update = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(update);
        when(update.executeUpdate()).thenReturn(0);
        JdbcAgentCommandTaskFinalStateCallbackJobStore store = new JdbcAgentCommandTaskFinalStateCallbackJobStore(
                new AgentRuntimeJdbcConnectionManager(dataSource, new AgentRuntimePersistenceProperties())
        );
        Instant now = Instant.parse("2026-08-20T00:20:00Z");

        boolean renewed = store.heartbeat(
                "job-expired", "old-worker", "old-token", now.plusSeconds(60), now);

        assertFalse(renewed);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("lease_expires_at > ?"));
        verify(connection).commit();
        verify(connection).close();
    }

    private AgentCommandTaskFinalStateCallbackJob job() {
        return AgentCommandTaskFinalStateCallbackJobFactory.create(
                new AgentToolActionWorkerReceiptIndexRecord(
                        "receipt-jdbc-duplicate",
                        "cmd-jdbc-duplicate",
                        9001L,
                        9101L,
                        "worker-jdbc",
                        "audit-jdbc",
                        "10",
                        "20",
                        "1001",
                        "run-jdbc",
                        "session-jdbc",
                        "command.run-program",
                        "SUCCEEDED",
                        "EXECUTION_SUCCEEDED",
                        true,
                        true,
                        "EXECUTION_SUCCEEDED",
                        1L,
                        Instant.parse("2026-08-20T00:19:00Z"),
                        Instant.parse("2026-08-20T00:19:01Z")
                ),
                Instant.parse("2026-08-20T00:20:00Z")
        ).orElseThrow();
    }

    /**
     * 构造一条真实执行成功 receipt；901 模拟数据库自增 id，和任何进程内序号都无关。
     */
    private void stubTerminalReceipt(ResultSet resultSet) throws SQLException {
        when(resultSet.getString("event_identity_key")).thenReturn("receipt-durable-sequence");
        when(resultSet.getString("command_id")).thenReturn("cmd-durable-sequence");
        when(resultSet.getLong("task_id")).thenReturn(9001L);
        when(resultSet.getLong("task_run_id")).thenReturn(9101L);
        when(resultSet.getString("executor_id")).thenReturn("worker-jdbc");
        when(resultSet.getString("audit_id")).thenReturn("audit-jdbc");
        when(resultSet.getString("tenant_id")).thenReturn("10");
        when(resultSet.getString("project_id")).thenReturn("20");
        when(resultSet.getString("actor_id")).thenReturn("1001");
        when(resultSet.getString("run_id")).thenReturn("run-jdbc");
        when(resultSet.getString("session_id")).thenReturn("session-jdbc");
        when(resultSet.getString("tool_code")).thenReturn("command.run-program");
        when(resultSet.getString("task_status")).thenReturn("SUCCEEDED");
        when(resultSet.getString("outcome")).thenReturn("EXECUTION_SUCCEEDED");
        when(resultSet.getBoolean("pre_check_passed")).thenReturn(true);
        when(resultSet.getBoolean("side_effect_executed")).thenReturn(true);
        when(resultSet.getString("error_code")).thenReturn("EXECUTION_SUCCEEDED");
        when(resultSet.getLong("replay_sequence")).thenReturn(901L);
        when(resultSet.wasNull()).thenReturn(false);
        when(resultSet.getTimestamp("consumed_at"))
                .thenReturn(Timestamp.from(Instant.parse("2026-08-20T00:19:00Z")));
        when(resultSet.getTimestamp("indexed_at"))
                .thenReturn(Timestamp.from(Instant.parse("2026-08-20T00:19:01Z")));
    }
}

/**
 * @Author : Cui
 * @Date: 2026/05/31 18:00
 * @Description DataSmart Govern Backend - JdbcAgentAsyncTaskCommandOutboxStoreTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import com.czh.datasmart.govern.agent.config.AgentAsyncTaskCommandOutboxProperties;
import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MySQL 版 Agent 异步命令 outbox 仓储测试。
 *
 * <p>测试不连接真实 MySQL，而是模拟 JDBC Connection/PreparedStatement/ResultSet。
 * 这样既能保护 Store 的关键状态机 SQL 行为，又不会让默认单测依赖本地数据库。</p>
 */
class JdbcAgentAsyncTaskCommandOutboxStoreTest {

    @Test
    void appendShouldReturnFalseWhenDuplicateCommandAlreadyExists() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenThrow(new SQLIntegrityConstraintViolationException(
                "Duplicate entry 'command-001' for key 'uk_agent_async_command_id'",
                "23000"
        ));
        JdbcAgentAsyncTaskCommandOutboxStore store = store(dataSource);

        boolean appended = store.append(record(AgentAsyncTaskCommandOutboxStatus.PENDING, 0, null));

        assertFalse(appended);
        verify(connection).close();
    }

    @Test
    void markPublishingShouldClaimPublishableRecordAndReturnLatestDatabaseRow() throws SQLException {
        Instant now = Instant.parse("2026-05-31T10:10:00Z");
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement updateStatement = mock(PreparedStatement.class);
        PreparedStatement selectStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(updateStatement, selectStatement);
        when(updateStatement.executeUpdate()).thenReturn(1);
        when(selectStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        stubClaimedRow(resultSet, now);
        JdbcAgentAsyncTaskCommandOutboxStore store = store(dataSource);

        Optional<AgentAsyncTaskCommandOutboxRecord> claimed =
                store.markPublishing("async-command-outbox:command-001", now);

        assertTrue(claimed.isPresent());
        assertEquals(AgentAsyncTaskCommandOutboxStatus.PUBLISHING, claimed.get().status());
        assertEquals(1, claimed.get().attemptCount());
        assertEquals(now, claimed.get().updatedAt());
        verify(updateStatement).executeUpdate();
        verify(selectStatement).executeQuery();
    }

    private JdbcAgentAsyncTaskCommandOutboxStore store(DataSource dataSource) {
        AgentRuntimeJdbcConnectionManager connectionManager =
                new AgentRuntimeJdbcConnectionManager(dataSource, mysqlPersistenceProperties());
        return new JdbcAgentAsyncTaskCommandOutboxStore(connectionManager, outboxProperties());
    }

    private AgentRuntimePersistenceProperties mysqlPersistenceProperties() {
        AgentRuntimePersistenceProperties properties = new AgentRuntimePersistenceProperties();
        properties.setDatabaseEnabled(true);
        properties.setAuditStore("mysql");
        properties.setOutboxStore("mysql");
        return properties;
    }

    private AgentAsyncTaskCommandOutboxProperties outboxProperties() {
        AgentAsyncTaskCommandOutboxProperties properties = new AgentAsyncTaskCommandOutboxProperties();
        properties.setStore("mysql");
        properties.setMaxCommandsPerRun(500);
        properties.setMaxTotalRecords(5000);
        return properties;
    }

    private AgentAsyncTaskCommandOutboxRecord record(AgentAsyncTaskCommandOutboxStatus status,
                                                     int attemptCount,
                                                     Instant nextRetryAt) {
        Instant now = Instant.parse("2026-05-31T10:00:00Z");
        return new AgentAsyncTaskCommandOutboxRecord(
                "async-command-outbox:command-001",
                "command-001",
                "idem-session-run-audit",
                "datasmart.agent.async-task-command.v1",
                "AGENT_TOOL_ASYNC_TASK_REQUESTED",
                "run-command",
                "datasmart.agent.tool.async.commands",
                "task-management",
                "session-command",
                "run-command",
                "audit-command",
                "data-sync.execute",
                "data-sync",
                "/sync-tasks",
                10L,
                20L,
                30L,
                "actor-command",
                "trace-command",
                "agent-tool-audit://session-command/run-command/audit-command/plan-arguments",
                status,
                attemptCount,
                now,
                now,
                nextRetryAt,
                null,
                "",
                256,
                false,
                "{\"schemaVersion\":\"datasmart.agent.async-task-command.v1\"}"
        );
    }

    private void stubClaimedRow(ResultSet resultSet, Instant updatedAt) throws SQLException {
        Instant createdAt = Instant.parse("2026-05-31T10:00:00Z");
        when(resultSet.getString("outbox_id")).thenReturn("async-command-outbox:command-001");
        when(resultSet.getString("command_id")).thenReturn("command-001");
        when(resultSet.getString("idempotency_key")).thenReturn("idem-session-run-audit");
        when(resultSet.getString("schema_version")).thenReturn("datasmart.agent.async-task-command.v1");
        when(resultSet.getString("command_type")).thenReturn("AGENT_TOOL_ASYNC_TASK_REQUESTED");
        when(resultSet.getString("partition_key")).thenReturn("run-command");
        when(resultSet.getString("command_topic")).thenReturn("datasmart.agent.tool.async.commands");
        when(resultSet.getString("consumer_service")).thenReturn("task-management");
        when(resultSet.getString("session_id")).thenReturn("session-command");
        when(resultSet.getString("run_id")).thenReturn("run-command");
        when(resultSet.getString("audit_id")).thenReturn("audit-command");
        when(resultSet.getString("tool_code")).thenReturn("data-sync.execute");
        when(resultSet.getString("target_service")).thenReturn("data-sync");
        when(resultSet.getString("target_endpoint")).thenReturn("/sync-tasks");
        when(resultSet.getLong("tenant_id")).thenReturn(10L);
        when(resultSet.getLong("project_id")).thenReturn(20L);
        when(resultSet.getLong("workspace_id")).thenReturn(30L);
        when(resultSet.wasNull()).thenReturn(false, false, false);
        when(resultSet.getString("actor_id")).thenReturn("actor-command");
        when(resultSet.getString("trace_id")).thenReturn("trace-command");
        when(resultSet.getString("payload_reference"))
                .thenReturn("agent-tool-audit://session-command/run-command/audit-command/plan-arguments");
        when(resultSet.getString("status")).thenReturn(AgentAsyncTaskCommandOutboxStatus.PUBLISHING.name());
        when(resultSet.getInt("attempt_count")).thenReturn(1);
        when(resultSet.getTimestamp("create_time")).thenReturn(Timestamp.from(createdAt));
        when(resultSet.getTimestamp("update_time")).thenReturn(Timestamp.from(updatedAt));
        when(resultSet.getTimestamp("next_retry_at")).thenReturn(null);
        when(resultSet.getTimestamp("published_at")).thenReturn(null);
        when(resultSet.getString("last_error")).thenReturn("");
        when(resultSet.getInt("payload_size_bytes")).thenReturn(256);
        when(resultSet.getBoolean("payload_truncated")).thenReturn(false);
        when(resultSet.getString("payload_json"))
                .thenReturn("{\"schemaVersion\":\"datasmart.agent.async-task-command.v1\"}");
    }
}

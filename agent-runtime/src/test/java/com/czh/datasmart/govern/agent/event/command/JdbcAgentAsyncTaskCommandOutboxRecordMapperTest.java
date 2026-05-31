/**
 * @Author : Cui
 * @Date: 2026/05/31 18:00
 * @Description DataSmart Govern Backend - JdbcAgentAsyncTaskCommandOutboxRecordMapperTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 异步命令 outbox JDBC 映射测试。
 *
 * <p>这组测试保护“数据库列 <-> 领域 record”的字段契约。command outbox 的字段很多，
 * 一旦映射顺序错位，表现出来可能不是马上编译失败，而是 dispatcher 把错误的 topic、payloadReference 或租户边界
 * 投递给下游，排查成本会很高。因此我们用轻量 Mockito 测试覆盖关键字段和 null 语义。</p>
 */
class JdbcAgentAsyncTaskCommandOutboxRecordMapperTest {

    @Test
    void toRecordShouldMapDatabaseColumnsToDomainRecord() throws SQLException {
        Instant createdAt = Instant.parse("2026-05-31T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-05-31T10:01:00Z");
        Instant nextRetryAt = Instant.parse("2026-05-31T10:02:00Z");
        ResultSet resultSet = mock(ResultSet.class);
        stubResultSet(resultSet, createdAt, updatedAt, nextRetryAt);

        AgentAsyncTaskCommandOutboxRecord record =
                JdbcAgentAsyncTaskCommandOutboxRecordMapper.toRecord(resultSet);

        assertEquals("async-command-outbox:command-001", record.outboxId());
        assertEquals("command-001", record.commandId());
        assertEquals("idem-session-run-audit", record.idempotencyKey());
        assertEquals("datasmart.agent.tool.async.commands", record.commandTopic());
        assertEquals("run-command", record.partitionKey());
        assertEquals(10L, record.tenantId());
        assertNull(record.projectId());
        assertEquals(30L, record.workspaceId());
        assertEquals(AgentAsyncTaskCommandOutboxStatus.FAILED, record.status());
        assertEquals(2, record.attemptCount());
        assertEquals(createdAt, record.createdAt());
        assertEquals(updatedAt, record.updatedAt());
        assertEquals(nextRetryAt, record.nextRetryAt());
        assertTrue(record.payloadJson().contains("payloadReference"));
    }

    @Test
    void bindParametersShouldKeepNullAndInstantSemantics() throws SQLException {
        java.sql.PreparedStatement statement = mock(java.sql.PreparedStatement.class);
        Instant now = Instant.parse("2026-05-31T10:03:00Z");

        JdbcAgentAsyncTaskCommandOutboxRecordMapper.bindParameters(statement, java.util.List.of(
                AgentAsyncTaskCommandOutboxStatus.PUBLISHING.name(),
                now,
                5,
                10L,
                true
        ));

        verify(statement).setString(1, AgentAsyncTaskCommandOutboxStatus.PUBLISHING.name());
        verify(statement).setTimestamp(2, Timestamp.from(now));
        verify(statement).setInt(3, 5);
        verify(statement).setLong(4, 10L);
        verify(statement).setBoolean(5, true);
    }

    private void stubResultSet(ResultSet resultSet,
                               Instant createdAt,
                               Instant updatedAt,
                               Instant nextRetryAt) throws SQLException {
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
        when(resultSet.getLong("project_id")).thenReturn(0L);
        when(resultSet.getLong("workspace_id")).thenReturn(30L);
        when(resultSet.wasNull()).thenReturn(false, true, false);
        when(resultSet.getString("actor_id")).thenReturn("actor-command");
        when(resultSet.getString("trace_id")).thenReturn("trace-command");
        when(resultSet.getString("payload_reference"))
                .thenReturn("agent-tool-audit://session-command/run-command/audit-command/plan-arguments");
        when(resultSet.getString("status")).thenReturn(AgentAsyncTaskCommandOutboxStatus.FAILED.name());
        when(resultSet.getInt("attempt_count")).thenReturn(2);
        when(resultSet.getTimestamp("create_time")).thenReturn(Timestamp.from(createdAt));
        when(resultSet.getTimestamp("update_time")).thenReturn(Timestamp.from(updatedAt));
        when(resultSet.getTimestamp("next_retry_at")).thenReturn(Timestamp.from(nextRetryAt));
        when(resultSet.getTimestamp("published_at")).thenReturn(null);
        when(resultSet.getString("last_error")).thenReturn("模拟 Kafka broker 暂时不可用");
        when(resultSet.getInt("payload_size_bytes")).thenReturn(256);
        when(resultSet.getBoolean("payload_truncated")).thenReturn(false);
        when(resultSet.getString("payload_json"))
                .thenReturn("{\"payloadReference\":\"agent-tool-audit://session-command/run-command/audit-command/plan-arguments\"}");
    }
}

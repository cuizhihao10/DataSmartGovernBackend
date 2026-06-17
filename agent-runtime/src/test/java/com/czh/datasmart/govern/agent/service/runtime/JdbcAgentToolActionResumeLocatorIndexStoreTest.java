/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - JdbcAgentToolActionResumeLocatorIndexStoreTest.java
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
import java.sql.Types;
import java.time.Instant;
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
 * MySQL 版 checkpoint/thread 恢复 locator index 仓储测试。
 *
 * <p>测试使用 Mockito 模拟 JDBC 对象，而不是启动真实 MySQL。这样可以在普通单元测试中验证 Store 的核心契约：
 * 1. 无效 locator 不写库；
 * 2. upsert 只绑定低敏定位符；
 * 3. checkpoint/thread 查询可以从 ResultSet 还原记录；
 * 4. 空查询条件不会触发数据库访问；
 * 5. size 诊断只返回数量，不泄露任何 locator 明细。</p>
 *
 * <p>真实 SQL 方言、唯一索引和执行计划由 migration 脚本承载，后续如果引入 Testcontainers，
 * 可以再补一组 MySQL 集成测试。本单测先保证 Java 代码层面的仓储边界稳定。</p>
 */
class JdbcAgentToolActionResumeLocatorIndexStoreTest {

    @Test
    void upsertShouldSkipUnindexableRecord() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        JdbcAgentToolActionResumeLocatorIndexStore store = store(dataSource);

        store.upsert(new AgentToolActionResumeLocatorIndexRecord(
                "checkpoint-only",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.parse("2026-06-17T00:00:00Z")
        ));

        verify(dataSource, never()).getConnection();
    }

    @Test
    void upsertShouldBindLowSensitiveLocatorFields() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        JdbcAgentToolActionResumeLocatorIndexStore store = store(dataSource);

        store.upsert(record());

        verify(statement).setString(1, "checkpoint-a");
        verify(statement).setString(2, "thread-a");
        verify(statement).setString(5, "command-a");
        verify(statement).setString(6, "outbox-a");
        verify(statement).setString(7, "approval-fact-a");
        verify(statement).setString(9, "task.draft.persist");
        verify(statement).setString(11, "tenant-a");
        verify(statement).setTimestamp(eq(14), any(Timestamp.class));
        verify(statement, never()).setString(eq(15), anyString());
        verify(connection).close();
    }

    @Test
    void findByCheckpointIdShouldRestoreLocatorRecord() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        stubRow(resultSet);
        JdbcAgentToolActionResumeLocatorIndexStore store = store(dataSource);

        Optional<AgentToolActionResumeLocatorIndexRecord> record = store.findByCheckpointId("checkpoint-a");

        assertTrue(record.isPresent());
        assertEquals("command-a", record.get().commandId());
        assertEquals("approval-fact-a", record.get().approvalFactId());
        assertEquals("tenant-a", record.get().tenantId());
        verify(statement).setString(1, "checkpoint-a");
        verify(statement).setInt(2, 1);
    }

    @Test
    void findByThreadIdShouldReturnEmptyWhenThreadIsBlank() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        JdbcAgentToolActionResumeLocatorIndexStore store = store(dataSource);

        Optional<AgentToolActionResumeLocatorIndexRecord> record = store.findByThreadId(" ");

        assertTrue(record.isEmpty());
        verify(dataSource, never()).getConnection();
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
        when(resultSet.getLong(1)).thenReturn(42L);
        JdbcAgentToolActionResumeLocatorIndexStore store = store(dataSource);

        int size = store.size();

        assertEquals(42, size);
        verify(resultSet, never()).getString(anyString());
    }

    private JdbcAgentToolActionResumeLocatorIndexStore store(DataSource dataSource) {
        AgentRuntimePersistenceProperties properties = new AgentRuntimePersistenceProperties();
        properties.setDatabaseEnabled(true);
        properties.getJdbc().setMaxQueryLimit(1000);
        AgentRuntimeJdbcConnectionManager connectionManager =
                new AgentRuntimeJdbcConnectionManager(dataSource, properties);
        return new JdbcAgentToolActionResumeLocatorIndexStore(connectionManager, properties);
    }

    private AgentToolActionResumeLocatorIndexRecord record() {
        return new AgentToolActionResumeLocatorIndexRecord(
                "checkpoint-a",
                "thread-a",
                "session-a",
                "run-a",
                "command-a",
                "outbox-a",
                "approval-fact-a",
                "clarification-fact-a",
                "task.draft.persist",
                "policy-v1",
                "tenant-a",
                "project-a",
                "actor-a",
                Instant.parse("2026-06-17T00:00:00Z")
        );
    }

    private void stubRow(ResultSet resultSet) throws SQLException {
        when(resultSet.getString("checkpoint_id")).thenReturn("checkpoint-a");
        when(resultSet.getString("thread_id")).thenReturn("thread-a");
        when(resultSet.getString("session_id")).thenReturn("session-a");
        when(resultSet.getString("run_id")).thenReturn("run-a");
        when(resultSet.getString("command_id")).thenReturn("command-a");
        when(resultSet.getString("outbox_id")).thenReturn("outbox-a");
        when(resultSet.getString("approval_fact_id")).thenReturn("approval-fact-a");
        when(resultSet.getString("clarification_fact_id")).thenReturn("clarification-fact-a");
        when(resultSet.getString("tool_code")).thenReturn("task.draft.persist");
        when(resultSet.getString("requested_policy_version")).thenReturn("policy-v1");
        when(resultSet.getString("tenant_id")).thenReturn("tenant-a");
        when(resultSet.getString("project_id")).thenReturn("project-a");
        when(resultSet.getString("actor_id")).thenReturn("actor-a");
        when(resultSet.getTimestamp("updated_at"))
                .thenReturn(Timestamp.from(Instant.parse("2026-06-17T00:00:00Z")));
    }
}

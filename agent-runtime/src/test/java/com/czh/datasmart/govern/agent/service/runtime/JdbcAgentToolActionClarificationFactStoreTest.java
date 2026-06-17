/**
 * @Author : Cui
 * @Date: 2026/06/18 00:00
 * @Description DataSmart Govern Backend - JdbcAgentToolActionClarificationFactStoreTest.java
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
 * MySQL 版澄清事实仓储测试。
 *
 * <p>测试使用 Mockito 模拟 JDBC 对象，不启动真实 MySQL。这样可以快速验证 Java 仓储契约：
 * 1. 无效事实不写库；
 * 2. upsert 只绑定低敏元数据和低敏 code；
 * 3. findByFactId 能从 ResultSet 还原 record；
 * 4. 空 factId 不访问数据库；
 * 5. size 诊断只返回数量，不读取事实详情。</p>
 */
class JdbcAgentToolActionClarificationFactStoreTest {

    @Test
    void upsertShouldSkipUnindexableRecord() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        JdbcAgentToolActionClarificationFactStore store = store(dataSource);

        store.upsert(new AgentToolActionClarificationFactRecord(
                "clarification-only",
                null,
                null,
                null,
                null,
                null,
                "10",
                null,
                "1001",
                AgentToolActionClarificationFactRecord.STATUS_AVAILABLE,
                java.util.List.of(),
                java.util.List.of(),
                Instant.parse("2026-06-18T02:00:00Z"),
                Instant.parse("2026-06-18T01:00:00Z"),
                Instant.parse("2026-06-18T01:00:00Z")
        ));

        verify(dataSource, never()).getConnection();
    }

    @Test
    void upsertShouldBindLowSensitiveFieldsAndCodeArrays() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        JdbcAgentToolActionClarificationFactStore store = store(dataSource);

        store.upsert(record());

        verify(statement).setString(1, "clarification-fact-a");
        verify(statement).setString(2, "session-a");
        verify(statement).setString(3, "run-a");
        verify(statement).setString(4, "command-a");
        verify(statement).setString(5, "task.draft.persist");
        verify(statement).setString(6, "policy-v1");
        verify(statement).setString(7, "10");
        verify(statement).setString(8, "20");
        verify(statement).setString(9, "1001");
        verify(statement).setString(10, "AVAILABLE");
        verify(statement).setString(11, "[\"USER_CLARIFICATION_CAPTURED\",\"CLARIFICATION_FACT_CONTENT_NOT_STORED\"]");
        verify(statement).setString(12, "[\"MISSING_PROJECT_ID\"]");
        verify(statement).setTimestamp(eq(13), any(Timestamp.class));
        verify(statement).setTimestamp(eq(14), any(Timestamp.class));
        verify(statement).setTimestamp(eq(15), any(Timestamp.class));
        verify(statement, never()).setString(eq(16), anyString());
        verify(connection).close();
    }

    @Test
    void findByFactIdShouldRestoreClarificationFact() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        stubRow(resultSet);
        JdbcAgentToolActionClarificationFactStore store = store(dataSource);

        Optional<AgentToolActionClarificationFactRecord> record = store.findByFactId("clarification-fact-a");

        assertTrue(record.isPresent());
        assertEquals("clarification-fact-a", record.get().clarificationFactId());
        assertEquals("command-a", record.get().commandId());
        assertEquals("20", record.get().projectId());
        assertEquals(2, record.get().evidenceCodes().size());
        assertEquals("MISSING_PROJECT_ID", record.get().issueCodes().getFirst());
        verify(statement).setString(1, "clarification-fact-a");
        verify(statement).setInt(2, 1);
    }

    @Test
    void findByFactIdShouldReturnEmptyWhenIdIsBlank() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        JdbcAgentToolActionClarificationFactStore store = store(dataSource);

        Optional<AgentToolActionClarificationFactRecord> record = store.findByFactId(" ");

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
        when(resultSet.getLong(1)).thenReturn(12L);
        JdbcAgentToolActionClarificationFactStore store = store(dataSource);

        int size = store.size();

        assertEquals(12, size);
        verify(resultSet, never()).getString(anyString());
    }

    private JdbcAgentToolActionClarificationFactStore store(DataSource dataSource) {
        AgentRuntimePersistenceProperties properties = new AgentRuntimePersistenceProperties();
        properties.setDatabaseEnabled(true);
        properties.getJdbc().setMaxQueryLimit(1000);
        AgentRuntimeJdbcConnectionManager connectionManager =
                new AgentRuntimeJdbcConnectionManager(dataSource, properties);
        return new JdbcAgentToolActionClarificationFactStore(connectionManager, properties);
    }

    private AgentToolActionClarificationFactRecord record() {
        return new AgentToolActionClarificationFactRecord(
                "clarification-fact-a",
                "session-a",
                "run-a",
                "command-a",
                "task.draft.persist",
                "policy-v1",
                "10",
                "20",
                "1001",
                AgentToolActionClarificationFactRecord.STATUS_AVAILABLE,
                java.util.List.of("USER_CLARIFICATION_CAPTURED", "CLARIFICATION_FACT_CONTENT_NOT_STORED"),
                java.util.List.of("MISSING_PROJECT_ID"),
                Instant.parse("2026-06-18T02:00:00Z"),
                Instant.parse("2026-06-18T01:00:00Z"),
                Instant.parse("2026-06-18T01:10:00Z")
        );
    }

    private void stubRow(ResultSet resultSet) throws SQLException {
        when(resultSet.getString("clarification_fact_id")).thenReturn("clarification-fact-a");
        when(resultSet.getString("session_id")).thenReturn("session-a");
        when(resultSet.getString("run_id")).thenReturn("run-a");
        when(resultSet.getString("command_id")).thenReturn("command-a");
        when(resultSet.getString("tool_code")).thenReturn("task.draft.persist");
        when(resultSet.getString("requested_policy_version")).thenReturn("policy-v1");
        when(resultSet.getString("tenant_id")).thenReturn("10");
        when(resultSet.getString("project_id")).thenReturn("20");
        when(resultSet.getString("actor_id")).thenReturn("1001");
        when(resultSet.getString("status")).thenReturn("AVAILABLE");
        when(resultSet.getString("evidence_codes_json"))
                .thenReturn("[\"USER_CLARIFICATION_CAPTURED\",\"CLARIFICATION_FACT_CONTENT_NOT_STORED\"]");
        when(resultSet.getString("issue_codes_json")).thenReturn("[\"MISSING_PROJECT_ID\"]");
        when(resultSet.getTimestamp("expires_at"))
                .thenReturn(Timestamp.from(Instant.parse("2026-06-18T02:00:00Z")));
        when(resultSet.getTimestamp("created_at"))
                .thenReturn(Timestamp.from(Instant.parse("2026-06-18T01:00:00Z")));
        when(resultSet.getTimestamp("updated_at"))
                .thenReturn(Timestamp.from(Instant.parse("2026-06-18T01:10:00Z")));
    }
}

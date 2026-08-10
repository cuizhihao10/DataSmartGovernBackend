/**
 * @Author : Cui
 * @Date: 2026/08/05 00:00
 * @Description DataSmart Govern Backend - SpecialistTurnFactJdbcStoreTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.specialist;

import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 专业 Agent turn JDBC 仓储测试。
 *
 * <p>测试不要求本机启动 PostgreSQL，而是使用 Mockito 验证 PreparedStatement 参数、查询范围和事务行为。
 * 迁移脚本负责真实数据库约束，本测试负责确保 Java 仓储不会把高敏正文写入 SQL，也不会通过错误的幂等键覆盖
 * 其他身份范围。</p>
 */
class SpecialistTurnFactJdbcStoreTest {

    /** 查询 session 时必须绑定租户、项目、actor 和安全 limit，并能还原引用数组。 */
    @Test
    void shouldQuerySessionWithinDatabaseScope() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        stubRow(resultSet, "user-a", 10L, 10010L, 20L, "session-a", "run-a", "turn-a", "idem-a");
        SpecialistTurnFactJdbcStore store = store(dataSource);

        List<SpecialistTurnFact> result = store.findBySession(
                SpecialistTurnFact.userScope(10L, 10010L, 20L, "user-a"), "session-a", 50);

        assertEquals(1, result.size());
        assertEquals("user-a", result.getFirst().userId());
        assertEquals(List.of("tool.summary:1"), result.getFirst().toolActivitySummaryRefs());
        verify(statement).setLong(1, 10L);
        verify(statement).setLong(2, 10010L);
        verify(statement).setLong(3, 20L);
        verify(statement).setString(4, "session-a");
        verify(statement).setString(5, "user-a");
        verify(statement).setInt(6, 50);
    }

    /** 按 run 查询同样必须带租户和项目，项目审计范围才允许不绑定 user_id。 */
    @Test
    void shouldQueryRunForProjectAuditScope() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);
        SpecialistTurnFactJdbcStore store = store(dataSource);

        List<SpecialistTurnFact> result = store.findByRun(
                SpecialistTurnFact.projectAuditScope(10L, 10010L, 20L), "run-a", 20);

        assertTrue(result.isEmpty());
        verify(statement).setLong(1, 10L);
        verify(statement).setLong(2, 10010L);
        verify(statement).setLong(3, 20L);
        verify(statement).setString(4, "run-a");
        verify(statement).setInt(5, 20);
    }

    /** 新事实写入应使用事务、JSON 数组参数和低敏字段，不会绑定 prompt 或模型输出正文。 */
    @Test
    void shouldUpsertFactWithLowSensitiveParameters() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement keyStatement = mock(PreparedStatement.class);
        PreparedStatement turnStatement = mock(PreparedStatement.class);
        PreparedStatement upsertStatement = mock(PreparedStatement.class);
        ResultSet keyResult = mock(ResultSet.class);
        ResultSet turnResult = mock(ResultSet.class);
        ResultSet upsertResult = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(keyStatement, turnStatement, upsertStatement);
        when(keyStatement.executeQuery()).thenReturn(keyResult);
        when(turnStatement.executeQuery()).thenReturn(turnResult);
        when(upsertStatement.executeQuery()).thenReturn(upsertResult);
        when(keyResult.next()).thenReturn(false);
        when(turnResult.next()).thenReturn(false);
        when(upsertResult.next()).thenReturn(true, false);
        stubRow(upsertResult, "user-a", 10L, 10010L, 20L, "session-a", "run-a", "turn-a", "idem-a");
        SpecialistTurnFact fact = fact();
        SpecialistTurnFactJdbcStore store = store(dataSource);

        SpecialistTurnFact saved = store.save(fact);

        assertEquals(fact, saved);
        verify(connection).commit();
        verify(upsertStatement).setString(1, "user-a");
        verify(upsertStatement).setLong(2, 10L);
        verify(upsertStatement).setLong(3, 10010L);
        verify(upsertStatement).setLong(4, 20L);
        verify(upsertStatement).setString(16, "[\"tool.summary:1\"]");
        verify(upsertStatement).setString(17, "[\"rag.case:1\"]");
        verify(upsertStatement, never()).setString(13, "prompt");
    }

    /** 幂等键已经绑定其他用户时必须回滚并拒绝覆盖。 */
    @Test
    void shouldRejectIdempotencyKeyChangingIdentity() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement keyStatement = mock(PreparedStatement.class);
        ResultSet keyResult = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(keyStatement);
        when(keyStatement.executeQuery()).thenReturn(keyResult);
        when(keyResult.next()).thenReturn(true, false);
        stubRow(keyResult, "another-user", 10L, 10010L, 20L, "session-a", "run-a", "turn-a", "idem-a");
        SpecialistTurnFactJdbcStore store = store(dataSource);

        assertThrows(PlatformBusinessException.class, () -> store.save(fact()));
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    /** 使用现有 Agent Runtime JDBC 连接管理器构造测试 Store，保持与生产事务边界一致。 */
    private SpecialistTurnFactJdbcStore store(DataSource dataSource) {
        AgentRuntimePersistenceProperties properties = new AgentRuntimePersistenceProperties();
        properties.setDatabaseEnabled(true);
        properties.getJdbc().setMaxQueryLimit(1000);
        AgentRuntimeJdbcConnectionManager connectionManager =
                new AgentRuntimeJdbcConnectionManager(dataSource, properties);
        return new SpecialistTurnFactJdbcStore(connectionManager, new ObjectMapper(), properties);
    }

    /** 构造一条包含模型调用、工具摘要引用和 RAG 证据引用的合法事实。 */
    private SpecialistTurnFact fact() {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        return new SpecialistTurnFact(
                "user-a", 10L, 10010L, 20L, "session-a", "run-a", "turn-a", "idem-a",
                "knowledge-agent", "KNOWLEDGE_AGENT", null, "SUCCEEDED", "metadata checked",
                "provider-call-1", "gpt-5.6-sol", List.of("tool.summary:1"), List.of("rag.case:1"),
                10L, now, now, now, now
        );
    }

    /**
     * 给 JDBC mapper 准备一行低敏结果。
     *
     * <p>所有返回值都来自合法领域事实，测试因此可以同时覆盖 ResultSet 映射和 record 构造器的安全校验。</p>
     */
    private void stubRow(ResultSet resultSet,
                         String userId,
                         Long tenantId,
                         Long applicationId,
                         Long projectId,
                         String sessionId,
                         String runId,
                         String turnId,
                         String idempotencyKey) throws SQLException {
        when(resultSet.getString("user_id")).thenReturn(userId);
        when(resultSet.getLong("tenant_id")).thenReturn(tenantId);
        when(resultSet.getLong("application_id")).thenReturn(applicationId);
        when(resultSet.getLong("project_id")).thenReturn(projectId);
        when(resultSet.getString("session_id")).thenReturn(sessionId);
        when(resultSet.getString("run_id")).thenReturn(runId);
        when(resultSet.getString("turn_id")).thenReturn(turnId);
        when(resultSet.getString("idempotency_key")).thenReturn(idempotencyKey);
        when(resultSet.getString("agent_id")).thenReturn("knowledge-agent");
        when(resultSet.getString("role")).thenReturn("KNOWLEDGE_AGENT");
        when(resultSet.getString("delegation_id")).thenReturn(null);
        when(resultSet.getString("status")).thenReturn("SUCCEEDED");
        when(resultSet.getString("low_sensitive_summary")).thenReturn("metadata checked");
        when(resultSet.getString("model_invocation_id")).thenReturn("provider-call-1");
        when(resultSet.getString("model_name")).thenReturn("gpt-5.6-sol");
        when(resultSet.getString("tool_activity_summary_refs")).thenReturn("[\"tool.summary:1\"]");
        when(resultSet.getString("evidence_refs")).thenReturn("[\"rag.case:1\"]");
        when(resultSet.getLong("duration_millis")).thenReturn(10L);
        when(resultSet.wasNull()).thenReturn(false);
        Timestamp timestamp = Timestamp.from(Instant.parse("2026-08-05T00:00:00Z"));
        when(resultSet.getTimestamp("started_at")).thenReturn(timestamp);
        when(resultSet.getTimestamp("finished_at")).thenReturn(timestamp);
        when(resultSet.getTimestamp("created_at")).thenReturn(timestamp);
        when(resultSet.getTimestamp("updated_at")).thenReturn(timestamp);
    }
}

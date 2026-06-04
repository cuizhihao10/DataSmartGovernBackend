/**
 * @Author : Cui
 * @Date: 2026/06/04 23:36
 * @Description DataSmart Govern Backend - JdbcAgentSkillVisibilitySnapshotIndexStoreTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MySQL 版 Skill 可见性快照索引仓储测试。
 *
 * <p>测试仍然使用模拟 JDBC 对象，不连接真实 MySQL。这样可以在普通单元测试中保护 Store 的业务语义：
 * 幂等写入、查询条件拼装、授权项目空集合收口和 ResultSet 还原；真正的 SQL 方言、索引执行计划和迁移脚本
 * 后续可以用集成测试或 Testcontainers 单独验证。</p>
 */
class JdbcAgentSkillVisibilitySnapshotIndexStoreTest {

    @Test
    void appendShouldReturnFalseWhenIdentityKeyAlreadyExists() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenThrow(new SQLIntegrityConstraintViolationException(
                "Duplicate entry 'identity-skill-001' for key 'uk_agent_skill_visibility_identity_key'",
                "23000"
        ));
        JdbcAgentSkillVisibilitySnapshotIndexStore store = store(dataSource);

        boolean appended = store.append(record());

        assertFalse(appended);
        verify(connection).close();
    }

    @Test
    void queryShouldReturnEmptyWhenAuthorizedProjectScopeIsEmpty() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        JdbcAgentSkillVisibilitySnapshotIndexStore store = store(dataSource);

        List<AgentRuntimeEventProjectionRecord> records = store.query(new AgentRuntimeEventProjectionQuery(
                "tenant-a",
                null,
                null,
                null,
                null,
                null,
                AgentSkillVisibilitySnapshotProjectionService.SKILL_VISIBILITY_EVENT_TYPE,
                null,
                50,
                0L,
                List.of()
        ));

        assertTrue(records.isEmpty());
        verify(dataSource, never()).getConnection();
    }

    @Test
    void queryShouldBindScopeAndRestoreRows() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        stubRow(resultSet);
        JdbcAgentSkillVisibilitySnapshotIndexStore store = store(dataSource);

        List<AgentRuntimeEventProjectionRecord> records = store.query(new AgentRuntimeEventProjectionQuery(
                "tenant-a",
                "project-a",
                "actor-a",
                "request-a",
                "run-a",
                "session-a",
                AgentSkillVisibilitySnapshotProjectionService.SKILL_VISIBILITY_EVENT_TYPE,
                "info",
                50,
                80L,
                List.of("project-a")
        ));

        assertEquals(1, records.size());
        assertEquals("identity-skill-001", records.getFirst().identityKey());
        assertEquals("fingerprint-001", records.getFirst().attributes().get("manifestFingerprint"));
        verify(statement).setString(1, AgentSkillVisibilitySnapshotProjectionService.SKILL_VISIBILITY_EVENT_TYPE);
        verify(statement).setString(2, "tenant-a");
        verify(statement).setString(9, "project-a");
        verify(statement).setLong(10, 80L);
        verify(statement).setInt(11, 50);
    }

    private JdbcAgentSkillVisibilitySnapshotIndexStore store(DataSource dataSource) {
        AgentRuntimePersistenceProperties properties = new AgentRuntimePersistenceProperties();
        properties.setDatabaseEnabled(true);
        properties.getJdbc().setMaxQueryLimit(1000);
        AgentRuntimeJdbcConnectionManager connectionManager =
                new AgentRuntimeJdbcConnectionManager(dataSource, properties);
        return new JdbcAgentSkillVisibilitySnapshotIndexStore(connectionManager, new ObjectMapper(), properties);
    }

    private AgentRuntimeEventProjectionRecord record() {
        return new AgentRuntimeEventProjectionRecord(
                "identity-skill-001",
                "agent-runtime-event.v1",
                "python-ai-runtime",
                AgentSkillVisibilitySnapshotProjectionService.SKILL_VISIBILITY_EVENT_TYPE,
                "skill_visibility",
                "Skill 可见性快照已记录",
                "info",
                "tenant-a",
                "project-a",
                "actor-a",
                "request-a",
                "run-a",
                "session-a",
                12L,
                88L,
                Instant.parse("2026-06-04T12:00:00Z"),
                Instant.parse("2026-06-04T12:00:01Z"),
                Instant.parse("2026-06-04T12:00:02Z"),
                Map.of(
                        "available", true,
                        "manifestFingerprint", "fingerprint-001",
                        "visibleSkillCodes", List.of("datasource.metadata.read")
                )
        );
    }

    private void stubRow(ResultSet resultSet) throws SQLException {
        Instant now = Instant.parse("2026-06-04T12:00:00Z");
        when(resultSet.getString("identity_key")).thenReturn("identity-skill-001");
        when(resultSet.getString("schema_version")).thenReturn("agent-runtime-event.v1");
        when(resultSet.getString("source")).thenReturn("python-ai-runtime");
        when(resultSet.getString("event_type"))
                .thenReturn(AgentSkillVisibilitySnapshotProjectionService.SKILL_VISIBILITY_EVENT_TYPE);
        when(resultSet.getString("stage")).thenReturn("skill_visibility");
        when(resultSet.getString("message")).thenReturn("Skill 可见性快照已记录");
        when(resultSet.getString("severity")).thenReturn("info");
        when(resultSet.getString("tenant_id")).thenReturn("tenant-a");
        when(resultSet.getString("project_id")).thenReturn("project-a");
        when(resultSet.getString("actor_id")).thenReturn("actor-a");
        when(resultSet.getString("request_id")).thenReturn("request-a");
        when(resultSet.getString("run_id")).thenReturn("run-a");
        when(resultSet.getString("session_id")).thenReturn("session-a");
        when(resultSet.getLong("producer_sequence")).thenReturn(12L);
        when(resultSet.getLong("replay_sequence")).thenReturn(88L);
        when(resultSet.wasNull()).thenReturn(false, false);
        when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
        when(resultSet.getTimestamp("published_at")).thenReturn(Timestamp.from(now.plusSeconds(1)));
        when(resultSet.getTimestamp("consumed_at")).thenReturn(Timestamp.from(now.plusSeconds(2)));
        when(resultSet.getString("attributes_json")).thenReturn("""
                {
                  "manifestFingerprint": "fingerprint-001",
                  "visibleSkillCodes": ["datasource.metadata.read"]
                }
                """);
    }
}

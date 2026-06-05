/**
 * @Author : Cui
 * @Date: 2026/06/01 19:40
 * @Description DataSmart Govern Backend - JdbcAgentRunToolDagConfirmationStoreTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution.confirmation;

import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import com.czh.datasmart.govern.agent.model.AgentHandoffDagBridgeSourceEvidence;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MySQL/JDBC 版 DAG selected-node 确认记录仓储测试。
 *
 * <p>测试不连接真实 MySQL，而是模拟 JDBC 对象。这样可以在默认单元测试中稳定保护仓储语义：
 * 1. 首次确认应直接写入；
 * 2. 重复确认应依赖数据库唯一索引仲裁，并回读已有事实；
 * 3. JSON 字段应能完整恢复 policyVersions、delegationEvidence、outboxIds 和 commandIds。
 * 真正的 MySQL 方言、索引与 JSON 列行为后续可再补集成测试。</p>
 */
class JdbcAgentRunToolDagConfirmationStoreTest {

    @Test
    void saveIfAbsentShouldReturnExistingRecordWhenDuplicateConfirmationAlreadyExists() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection insertConnection = mock(Connection.class);
        Connection selectConnection = mock(Connection.class);
        PreparedStatement insertStatement = mock(PreparedStatement.class);
        PreparedStatement selectStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(insertConnection, selectConnection);
        when(insertConnection.prepareStatement(anyString())).thenReturn(insertStatement);
        when(selectConnection.prepareStatement(anyString())).thenReturn(selectStatement);
        when(insertStatement.executeUpdate()).thenThrow(new SQLIntegrityConstraintViolationException(
                "Duplicate entry 'dag-confirmation:001' for key 'uk_agent_dag_confirmation_id'",
                "23000"
        ));
        when(selectStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        stubConfirmationRow(resultSet);
        JdbcAgentRunToolDagConfirmationStore store = store(dataSource);

        AgentRunToolDagConfirmationRecord saved = store.saveIfAbsent(record());

        assertEquals("dag-confirmation:001", saved.confirmationId());
        assertEquals(List.of("policy:v1"), saved.policyVersions());
        assertEquals(List.of("delegation:evidence:001"), saved.delegationEvidence());
        assertEquals("trace-bridge-preview", saved.bridgeSourceEvidence().previewTraceId());
        verify(insertStatement).executeUpdate();
        verify(selectStatement).executeQuery();
        verify(insertConnection).close();
        verify(selectConnection).close();
    }

    @Test
    void listByRunShouldRestoreJsonEvidenceFieldsFromDatabaseRows() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        stubConfirmationRow(resultSet);
        JdbcAgentRunToolDagConfirmationStore store = store(dataSource);

        List<AgentRunToolDagConfirmationRecord> records = store.listByRun("run-confirmation", 20);

        assertEquals(1, records.size());
        assertEquals(List.of("node-a"), records.getFirst().selectedNodeIds());
        assertEquals(List.of("audit-a"), records.getFirst().selectedAuditIds());
        assertEquals(List.of("outbox-a"), records.getFirst().outboxIds());
        assertEquals(List.of("command-a"), records.getFirst().commandIds());
        assertEquals(AgentHandoffDagBridgeSourceEvidence.SOURCE_TYPE_HANDOFF_DAG_BRIDGE_PREVIEW,
                records.getFirst().bridgeSourceEvidence().sourceType());
        assertTrue(records.getFirst().confirmed());
    }

    private JdbcAgentRunToolDagConfirmationStore store(DataSource dataSource) {
        AgentRuntimePersistenceProperties properties = new AgentRuntimePersistenceProperties();
        properties.setDatabaseEnabled(true);
        properties.getJdbc().setMaxQueryLimit(1000);
        AgentRuntimeJdbcConnectionManager connectionManager =
                new AgentRuntimeJdbcConnectionManager(dataSource, properties);
        return new JdbcAgentRunToolDagConfirmationStore(connectionManager, new ObjectMapper(), properties);
    }

    private AgentRunToolDagConfirmationRecord record() {
        Instant now = Instant.parse("2026-06-01T11:40:00Z");
        return new AgentRunToolDagConfirmationRecord(
                "dag-confirmation:001",
                "session-confirmation",
                "run-confirmation",
                "dag-selection:fingerprint",
                List.of("node-a"),
                List.of("audit-a"),
                List.of("policy:v1"),
                List.of("delegation:evidence:001"),
                bridgeSourceEvidence(),
                List.of("outbox-a"),
                List.of("command-a"),
                10L,
                20L,
                30L,
                "actor-confirmation",
                "trace-confirmation",
                true,
                AgentRunToolDagConfirmationStatus.CONFIRMED,
                Instant.parse("2026-06-01T12:10:00Z"),
                now,
                now
        );
    }

    private void stubConfirmationRow(ResultSet resultSet) throws SQLException {
        Instant createdAt = Instant.parse("2026-06-01T11:40:00Z");
        Instant expiresAt = Instant.parse("2026-06-01T12:10:00Z");
        when(resultSet.getString("confirmation_id")).thenReturn("dag-confirmation:001");
        when(resultSet.getString("session_id")).thenReturn("session-confirmation");
        when(resultSet.getString("run_id")).thenReturn("run-confirmation");
        when(resultSet.getString("selection_fingerprint")).thenReturn("dag-selection:fingerprint");
        when(resultSet.getString("selected_node_ids")).thenReturn("[\"node-a\"]");
        when(resultSet.getString("selected_audit_ids")).thenReturn("[\"audit-a\"]");
        when(resultSet.getString("policy_versions")).thenReturn("[\"policy:v1\"]");
        when(resultSet.getString("delegation_evidence")).thenReturn("[\"delegation:evidence:001\"]");
        when(resultSet.getString("bridge_source_evidence")).thenReturn("""
                {
                  "sourceType": "HANDOFF_DAG_BRIDGE_PREVIEW",
                  "bridgeAction": "TOOL_CONTROL_DRY_RUN",
                  "bridgeReady": true,
                  "selectionFingerprint": "dag-selection:fingerprint",
                  "handoffNodeIds": ["tool-control"],
                  "mappedToolNodeIds": ["node-a"],
                  "mappedToolAuditIds": ["audit-a"],
                  "previewTraceId": "trace-bridge-preview",
                  "previewEventType": "agent.handoff_dag.execution_bridge.previewed"
                }
                """);
        when(resultSet.getString("outbox_ids")).thenReturn("[\"outbox-a\"]");
        when(resultSet.getString("command_ids")).thenReturn("[\"command-a\"]");
        when(resultSet.getLong("tenant_id")).thenReturn(10L);
        when(resultSet.getLong("project_id")).thenReturn(20L);
        when(resultSet.getLong("workspace_id")).thenReturn(30L);
        when(resultSet.wasNull()).thenReturn(false, false, false);
        when(resultSet.getString("actor_id")).thenReturn("actor-confirmation");
        when(resultSet.getString("trace_id")).thenReturn("trace-confirmation");
        when(resultSet.getBoolean("confirmed")).thenReturn(true);
        when(resultSet.getString("status")).thenReturn(AgentRunToolDagConfirmationStatus.CONFIRMED.name());
        when(resultSet.getTimestamp("expires_at")).thenReturn(Timestamp.from(expiresAt));
        when(resultSet.getTimestamp("create_time")).thenReturn(Timestamp.from(createdAt));
        when(resultSet.getTimestamp("update_time")).thenReturn(Timestamp.from(createdAt));
    }

    private AgentHandoffDagBridgeSourceEvidence bridgeSourceEvidence() {
        return AgentHandoffDagBridgeSourceEvidence.handoffBridgePreview(
                AgentHandoffDagBridgeSourceEvidence.BRIDGE_ACTION_TOOL_CONTROL_DRY_RUN,
                true,
                "dag-selection:fingerprint",
                List.of("tool-control"),
                List.of("node-a"),
                List.of("audit-a"),
                "trace-bridge-preview",
                "agent.handoff_dag.execution_bridge.previewed"
        );
    }
}

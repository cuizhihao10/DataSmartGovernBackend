/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - JdbcAgentSessionStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentSessionState;
import com.czh.datasmart.govern.agent.model.AgentToolBindingStatus;
import com.czh.datasmart.govern.agent.model.AgentToolType;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** PostgreSQL-backed Agent session aggregate store. */
@Component
@ConditionalOnExpression(
        "T(com.czh.datasmart.govern.agent.config.AgentRuntimeStoreMode)"
                + ".isJdbcDurable('${datasmart.agent-runtime.persistence.session-store:memory}') "
                + "&& '${datasmart.agent-runtime.persistence.database-enabled:false}'.equalsIgnoreCase('true')"
)
public class JdbcAgentSessionStore implements AgentSessionStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };
    private final AgentRuntimeJdbcConnectionManager connectionManager;
    private final ObjectMapper objectMapper;
    private final int maxQueryLimit;

    public JdbcAgentSessionStore(AgentRuntimeJdbcConnectionManager connectionManager,
                                 ObjectMapper objectMapper,
                                 AgentRuntimePersistenceProperties properties) {
        this.connectionManager = connectionManager;
        this.objectMapper = objectMapper;
        this.maxQueryLimit = Math.max(1, Math.min(properties.getJdbc().getMaxQueryLimit(), 100));
    }

    @Override
    public void save(AgentSessionRecord session) {
        connectionManager.executeInTransaction(connection -> {
            upsertSession(connection, session);
            replaceDelegation(connection, session);
            replaceToolBindings(connection, session);
            replaceRuns(connection, session);
            replaceMessages(connection, session);
            return null;
        });
    }

    @Override
    public Optional<AgentSessionRecord> findById(String sessionId) {
        if (!hasText(sessionId)) {
            return Optional.empty();
        }
        return connectionManager.executeWithConnection(connection -> querySession(connection, sessionId.trim()));
    }

    @Override
    public List<AgentSessionRecord> list(Long tenantId,
                                         Long projectId,
                                         String actorId,
                                         boolean archived,
                                         int limit) {
        int normalizedLimit = Math.max(1, Math.min(Math.min(limit, 100), maxQueryLimit));
        return connectionManager.executeWithConnection(connection -> {
            StringBuilder sql = new StringBuilder("SELECT session_id FROM agent_session WHERE archived_at IS ")
                    .append(archived ? "NOT NULL" : "NULL");
            List<Object> parameters = new ArrayList<>();
            if (tenantId != null) {
                sql.append(" AND tenant_id = ?");
                parameters.add(tenantId);
            }
            if (projectId != null) {
                sql.append(" AND project_id = ?");
                parameters.add(projectId);
            }
            if (hasText(actorId)) {
                sql.append(" AND actor_id = ?");
                parameters.add(actorId.trim());
            }
            sql.append(" ORDER BY pinned DESC, update_time DESC LIMIT ?");
            parameters.add(normalizedLimit);
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                bindParameters(statement, parameters);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<AgentSessionRecord> records = new ArrayList<>();
                    while (resultSet.next()) {
                        querySession(connection, resultSet.getString(1)).ifPresent(records::add);
                    }
                    return records;
                }
            }
        });
    }

    private void upsertSession(Connection connection, AgentSessionRecord session) throws SQLException {
        String sql = """
                INSERT INTO agent_session (
                    session_id, agent_id, tenant_id, project_id, workspace_id, actor_id, actor_role, actor_type,
                    authorized_project_roles, channel, objective, isolation_level, workspace_key, state,
                    pinned, archived_at, last_message_at, create_time, update_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (session_id) DO UPDATE SET
                    agent_id=EXCLUDED.agent_id, actor_role=EXCLUDED.actor_role, actor_type=EXCLUDED.actor_type,
                    authorized_project_roles=EXCLUDED.authorized_project_roles, state=EXCLUDED.state,
                    pinned=EXCLUDED.pinned, archived_at=EXCLUDED.archived_at,
                    last_message_at=EXCLUDED.last_message_at, update_time=EXCLUDED.update_time
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, session.getSessionId());
            statement.setString(index++, session.getAgentId());
            setNullableLong(statement, index++, session.getTenantId());
            setNullableLong(statement, index++, session.getProjectId());
            setNullableLong(statement, index++, session.getWorkspaceId());
            statement.setString(index++, session.getActorId());
            statement.setString(index++, session.getActorRole());
            statement.setString(index++, session.getActorType());
            statement.setString(index++, session.getAuthorizedProjectRoles());
            statement.setString(index++, session.getChannel());
            statement.setString(index++, session.getObjective());
            statement.setString(index++, session.getIsolationLevel().name());
            statement.setString(index++, session.getWorkspaceKey());
            statement.setString(index++, session.getState().name());
            statement.setBoolean(index++, session.isPinned());
            setTimestamp(statement, index++, session.getArchivedAt());
            setTimestamp(statement, index++, session.getLastMessageAt());
            setTimestamp(statement, index++, session.getCreateTime());
            setTimestamp(statement, index, session.getUpdateTime());
            statement.executeUpdate();
        }
    }

    private void replaceDelegation(Connection connection, AgentSessionRecord session) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM agent_delegation WHERE session_id = ?")) {
            delete.setString(1, session.getSessionId());
            delete.executeUpdate();
        }
        AgentDelegationRecord record = session.getDelegation();
        String sql = """
                INSERT INTO agent_delegation (
                    delegation_id, session_id, agent_id, user_actor_id, tenant_id, project_id,
                    tool_codes, actions, resource_scopes, status, issued_at, expires_at, revoked_at, update_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, record.getDelegationId());
            statement.setString(index++, session.getSessionId());
            statement.setString(index++, record.getAgentId());
            statement.setString(index++, record.getUserActorId());
            setNullableLong(statement, index++, record.getTenantId());
            setNullableLong(statement, index++, record.getProjectId());
            statement.setString(index++, json(record.getToolCodes()));
            statement.setString(index++, json(record.getActions()));
            statement.setString(index++, json(record.getResourceScopes()));
            statement.setString(index++, record.getStatus());
            setTimestamp(statement, index++, record.getIssuedAt());
            setTimestamp(statement, index++, record.getExpiresAt());
            setTimestamp(statement, index++, record.getRevokedAt());
            setTimestamp(statement, index, record.getUpdateTime());
            statement.executeUpdate();
        }
    }

    private void replaceToolBindings(Connection connection, AgentSessionRecord session) throws SQLException {
        deleteChildren(connection, "agent_session_tool_binding", session.getSessionId());
        String sql = """
                INSERT INTO agent_session_tool_binding (
                    binding_id, session_id, tool_code, tool_type, display_name, target_service, target_endpoint,
                    target_resource_id, read_only, risk_level, execution_mode, requires_approval, idempotent,
                    status, allowed_actions, create_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (AgentToolBindingRecord binding : session.getToolBindings()) {
                int index = 1;
                statement.setString(index++, binding.bindingId());
                statement.setString(index++, session.getSessionId());
                statement.setString(index++, binding.toolCode());
                statement.setString(index++, binding.toolType().name());
                statement.setString(index++, binding.displayName());
                statement.setString(index++, binding.targetService());
                statement.setString(index++, binding.targetEndpoint());
                setNullableLong(statement, index++, binding.targetResourceId());
                statement.setBoolean(index++, Boolean.TRUE.equals(binding.readOnly()));
                statement.setString(index++, binding.riskLevel());
                statement.setString(index++, binding.executionMode());
                statement.setBoolean(index++, Boolean.TRUE.equals(binding.requiresApproval()));
                statement.setBoolean(index++, Boolean.TRUE.equals(binding.idempotent()));
                statement.setString(index++, binding.status().name());
                statement.setString(index++, json(binding.allowedActions()));
                setTimestamp(statement, index, binding.createTime());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void replaceRuns(Connection connection, AgentSessionRecord session) throws SQLException {
        deleteChildren(connection, "agent_run", session.getSessionId());
        String sql = """
                INSERT INTO agent_run (
                    run_id, session_id, state, workload_type, user_input_preview, dry_run, require_human_approval,
                    next_actions, variables, message, create_time, update_time, finish_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (AgentRunRecord run : session.getRuns()) {
                int index = 1;
                statement.setString(index++, run.getRunId());
                statement.setString(index++, session.getSessionId());
                statement.setString(index++, run.getState().name());
                statement.setString(index++, run.getWorkloadType());
                statement.setString(index++, run.getUserInputPreview());
                statement.setBoolean(index++, Boolean.TRUE.equals(run.getDryRun()));
                statement.setBoolean(index++, Boolean.TRUE.equals(run.getRequireHumanApproval()));
                statement.setString(index++, json(run.getNextActions()));
                statement.setString(index++, json(run.getVariables()));
                statement.setString(index++, run.getMessage());
                setTimestamp(statement, index++, run.getCreateTime());
                setTimestamp(statement, index++, run.getUpdateTime());
                setTimestamp(statement, index, run.getFinishTime());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void replaceMessages(Connection connection, AgentSessionRecord session) throws SQLException {
        deleteChildren(connection, "agent_conversation_message", session.getSessionId());
        String sql = "INSERT INTO agent_conversation_message "
                + "(message_id, session_id, run_id, role, content, create_time) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (AgentConversationMessageRecord message : session.getMessages()) {
                statement.setString(1, message.messageId());
                statement.setString(2, session.getSessionId());
                statement.setString(3, message.runId());
                statement.setString(4, message.role());
                statement.setString(5, message.content());
                setTimestamp(statement, 6, message.createTime());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private Optional<AgentSessionRecord> querySession(Connection connection, String sessionId) throws SQLException {
        String sql = "SELECT * FROM agent_session WHERE session_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                AgentDelegationRecord delegation = queryDelegation(connection, sessionId).orElse(null);
                return Optional.of(new AgentSessionRecord(
                        sessionId,
                        resultSet.getString("agent_id"),
                        nullableLong(resultSet, "tenant_id"),
                        nullableLong(resultSet, "project_id"),
                        nullableLong(resultSet, "workspace_id"),
                        resultSet.getString("actor_id"),
                        resultSet.getString("actor_role"),
                        resultSet.getString("actor_type"),
                        resultSet.getString("authorized_project_roles"),
                        resultSet.getString("channel"),
                        resultSet.getString("objective"),
                        WorkspaceIsolationLevel.valueOf(resultSet.getString("isolation_level")),
                        resultSet.getString("workspace_key"),
                        AgentSessionState.valueOf(resultSet.getString("state")),
                        delegation,
                        resultSet.getBoolean("pinned"),
                        localDateTime(resultSet, "archived_at"),
                        localDateTime(resultSet, "last_message_at"),
                        localDateTime(resultSet, "create_time"),
                        localDateTime(resultSet, "update_time"),
                        queryToolBindings(connection, sessionId),
                        queryRuns(connection, sessionId),
                        queryMessages(connection, sessionId)
                ));
            }
        }
    }

    private Optional<AgentDelegationRecord> queryDelegation(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM agent_delegation WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AgentDelegationRecord(
                        resultSet.getString("delegation_id"), resultSet.getString("agent_id"),
                        resultSet.getString("user_actor_id"), nullableLong(resultSet, "tenant_id"),
                        nullableLong(resultSet, "project_id"), strings(resultSet.getString("tool_codes")),
                        strings(resultSet.getString("actions")), strings(resultSet.getString("resource_scopes")),
                        resultSet.getString("status"), localDateTime(resultSet, "issued_at"),
                        localDateTime(resultSet, "expires_at"), localDateTime(resultSet, "revoked_at"),
                        localDateTime(resultSet, "update_time")));
            }
        }
    }

    private List<AgentToolBindingRecord> queryToolBindings(Connection connection, String sessionId) throws SQLException {
        String sql = "SELECT * FROM agent_session_tool_binding WHERE session_id = ? ORDER BY create_time";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentToolBindingRecord> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(new AgentToolBindingRecord(
                            resultSet.getString("binding_id"), resultSet.getString("tool_code"),
                            AgentToolType.valueOf(resultSet.getString("tool_type")), resultSet.getString("display_name"),
                            resultSet.getString("target_service"), resultSet.getString("target_endpoint"),
                            nullableLong(resultSet, "target_resource_id"), resultSet.getBoolean("read_only"),
                            resultSet.getString("risk_level"), resultSet.getString("execution_mode"),
                            resultSet.getBoolean("requires_approval"), resultSet.getBoolean("idempotent"),
                            AgentToolBindingStatus.valueOf(resultSet.getString("status")),
                            strings(resultSet.getString("allowed_actions")), localDateTime(resultSet, "create_time")));
                }
                return result;
            }
        }
    }

    private List<AgentRunRecord> queryRuns(Connection connection, String sessionId) throws SQLException {
        String sql = "SELECT * FROM agent_run WHERE session_id = ? ORDER BY create_time";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentRunRecord> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(new AgentRunRecord(
                            resultSet.getString("run_id"), sessionId,
                            AgentRunState.valueOf(resultSet.getString("state")), resultSet.getString("workload_type"),
                            resultSet.getString("user_input_preview"), resultSet.getBoolean("dry_run"),
                            resultSet.getBoolean("require_human_approval"), strings(resultSet.getString("next_actions")),
                            objectMap(resultSet.getString("variables")), localDateTime(resultSet, "create_time"),
                            localDateTime(resultSet, "update_time"), localDateTime(resultSet, "finish_time"),
                            resultSet.getString("message")));
                }
                return result;
            }
        }
    }

    private List<AgentConversationMessageRecord> queryMessages(Connection connection, String sessionId) throws SQLException {
        String sql = "SELECT * FROM agent_conversation_message WHERE session_id = ? ORDER BY create_time, message_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentConversationMessageRecord> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(new AgentConversationMessageRecord(
                            resultSet.getString("message_id"), resultSet.getString("run_id"),
                            resultSet.getString("role"), resultSet.getString("content"),
                            localDateTime(resultSet, "create_time")));
                }
                return result;
            }
        }
    }

    private void deleteChildren(Connection connection, String table, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            statement.executeUpdate();
        }
    }

    private void bindParameters(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            statement.setObject(index + 1, parameters.get(index));
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化 Agent 会话聚合 JSON 失败", exception);
        }
    }

    private List<String> strings(String value) {
        if (!hasText(value)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("解析 Agent 会话字符串列表失败", exception);
        }
    }

    private Map<String, Object> objectMap(String value) {
        if (!hasText(value)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, OBJECT_MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("解析 Agent Run 变量失败", exception);
        }
    }

    private void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private void setTimestamp(PreparedStatement statement, int index, LocalDateTime value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.valueOf(value));
        }
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private LocalDateTime localDateTime(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - JdbcAgentToolActionApprovalConfirmationStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable store for user approval confirmations used by controlled Agent tools. */
@Component
@ConditionalOnExpression(
        "T(com.czh.datasmart.govern.agent.config.AgentRuntimeStoreMode)"
                + ".isJdbcDurable('${datasmart.agent-runtime.persistence.approval-confirmation-store:memory}') "
                + "&& '${datasmart.agent-runtime.persistence.database-enabled:false}'.equalsIgnoreCase('true')"
)
public class JdbcAgentToolActionApprovalConfirmationStore
        implements AgentToolActionApprovalConfirmationStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private final AgentRuntimeJdbcConnectionManager connectionManager;
    private final ObjectMapper objectMapper;

    public JdbcAgentToolActionApprovalConfirmationStore(AgentRuntimeJdbcConnectionManager connectionManager,
                                                         ObjectMapper objectMapper) {
        this.connectionManager = connectionManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean saveIfAbsent(AgentToolActionApprovalConfirmationRecord record) {
        String sql = """
                INSERT INTO agent_tool_action_approval_confirmation (
                    confirmation_id, proposal_id, client_request_id, payload_reference, run_id, payload_key,
                    tenant_id, project_id, actor_id, confirming_actor_id, tool_name, graph_id, contract_id,
                    policy_version, payload_policy, payload_body_available, payload_size_bytes,
                    payload_metadata_digest, accepted_payload_evidence, confirmed, status,
                    created_at, confirmed_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                ON CONFLICT (confirmation_id) DO NOTHING
                """;
        return connectionManager.executeWithConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                statement.setString(index++, record.confirmationId());
                statement.setString(index++, record.proposalId());
                statement.setString(index++, record.clientRequestId());
                statement.setString(index++, record.payloadReference());
                statement.setString(index++, record.runId());
                statement.setString(index++, record.payloadKey());
                statement.setString(index++, record.tenantId());
                statement.setString(index++, record.projectId());
                statement.setString(index++, record.actorId());
                statement.setString(index++, record.confirmingActorId());
                statement.setString(index++, record.toolName());
                statement.setString(index++, record.graphId());
                statement.setString(index++, record.contractId());
                statement.setString(index++, record.policyVersion());
                statement.setString(index++, record.payloadPolicy());
                statement.setBoolean(index++, record.payloadBodyAvailable());
                statement.setInt(index++, record.payloadSizeBytes());
                statement.setString(index++, record.payloadMetadataDigest());
                statement.setString(index++, json(record.acceptedPayloadEvidence()));
                statement.setBoolean(index++, record.confirmed());
                statement.setString(index++, record.status().name());
                statement.setTimestamp(index++, timestamp(record.createdAt()));
                statement.setTimestamp(index++, timestamp(record.confirmedAt()));
                statement.setTimestamp(index, timestamp(record.expiresAt()));
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public Optional<AgentToolActionApprovalConfirmationRecord> findByConfirmationId(String confirmationId) {
        if (confirmationId == null || confirmationId.isBlank()) {
            return Optional.empty();
        }
        return connectionManager.executeWithConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM agent_tool_action_approval_confirmation WHERE confirmation_id = ?")) {
                statement.setString(1, confirmationId.trim());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(toRecord(resultSet));
                }
            }
        });
    }

    @Override
    public int removeExpired(Instant now) {
        Instant reference = now == null ? Instant.now() : now;
        return connectionManager.executeWithConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM agent_tool_action_approval_confirmation WHERE expires_at < ?")) {
                statement.setTimestamp(1, timestamp(reference));
                return statement.executeUpdate();
            }
        });
    }

    private AgentToolActionApprovalConfirmationRecord toRecord(ResultSet resultSet) throws java.sql.SQLException {
        return new AgentToolActionApprovalConfirmationRecord(
                resultSet.getString("confirmation_id"), resultSet.getString("proposal_id"),
                resultSet.getString("client_request_id"), resultSet.getString("payload_reference"),
                resultSet.getString("run_id"), resultSet.getString("payload_key"),
                resultSet.getString("tenant_id"), resultSet.getString("project_id"),
                resultSet.getString("actor_id"), resultSet.getString("confirming_actor_id"),
                resultSet.getString("tool_name"), resultSet.getString("graph_id"),
                resultSet.getString("contract_id"), resultSet.getString("policy_version"),
                resultSet.getString("payload_policy"), resultSet.getBoolean("payload_body_available"),
                resultSet.getInt("payload_size_bytes"), resultSet.getString("payload_metadata_digest"),
                strings(resultSet.getString("accepted_payload_evidence")), resultSet.getBoolean("confirmed"),
                AgentToolActionApprovalConfirmationStatus.valueOf(resultSet.getString("status")),
                instant(resultSet.getTimestamp("created_at")), instant(resultSet.getTimestamp("confirmed_at")),
                instant(resultSet.getTimestamp("expires_at")));
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化 Agent 工具确认事实失败", exception);
        }
    }

    private List<String> strings(String value) {
        try {
            return value == null ? List.of() : objectMapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("解析 Agent 工具确认事实失败", exception);
        }
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

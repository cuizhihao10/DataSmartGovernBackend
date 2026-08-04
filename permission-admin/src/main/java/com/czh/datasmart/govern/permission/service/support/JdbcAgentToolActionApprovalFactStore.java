/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - JdbcAgentToolActionApprovalFactStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** PostgreSQL approval fact store used by the permission control plane. */
@Component
@RequiredArgsConstructor
public class JdbcAgentToolActionApprovalFactStore implements AgentToolActionApprovalFactStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public AgentToolActionApprovalFactRecord save(AgentToolActionApprovalFactRecord record) {
        jdbcTemplate.update("""
                        INSERT INTO agent_tool_action_approval_fact (
                            approval_fact_id, tenant_id, project_id, actor_id, session_id, run_id, command_id,
                            tool_code, policy_version, status, expires_at, approved_by_actor_id,
                            reason_codes, evidence_codes, create_time, update_time
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, CURRENT_TIMESTAMP)
                        ON CONFLICT (approval_fact_id) DO UPDATE SET
                            status=EXCLUDED.status, expires_at=EXCLUDED.expires_at,
                            approved_by_actor_id=EXCLUDED.approved_by_actor_id,
                            reason_codes=EXCLUDED.reason_codes, evidence_codes=EXCLUDED.evidence_codes,
                            policy_version=EXCLUDED.policy_version, update_time=CURRENT_TIMESTAMP
                        """,
                record.approvalFactId(), record.tenantId(), record.projectId(), record.actorId(),
                record.sessionId(), record.runId(), record.commandId(), record.toolCode(), record.policyVersion(),
                record.status(), record.expiresAt(), record.approvedByActorId(), json(record.reasonCodes()),
                json(record.evidenceCodes()), record.createdAt());
        return findById(record.approvalFactId()).orElse(record);
    }

    @Override
    public Optional<AgentToolActionApprovalFactRecord> findById(String approvalFactId) {
        if (approvalFactId == null || approvalFactId.isBlank()) {
            return Optional.empty();
        }
        List<AgentToolActionApprovalFactRecord> records = jdbcTemplate.query(
                "SELECT * FROM agent_tool_action_approval_fact WHERE approval_fact_id = ?",
                this::mapRecord,
                approvalFactId.trim());
        return records.stream().findFirst();
    }

    private AgentToolActionApprovalFactRecord mapRecord(ResultSet resultSet, int rowNum) throws SQLException {
        return new AgentToolActionApprovalFactRecord(
                resultSet.getString("approval_fact_id"), resultSet.getObject("tenant_id", Long.class),
                resultSet.getObject("project_id", Long.class), resultSet.getString("actor_id"),
                resultSet.getString("session_id"), resultSet.getString("run_id"),
                resultSet.getString("command_id"), resultSet.getString("tool_code"),
                resultSet.getString("policy_version"), resultSet.getString("status"),
                resultSet.getTimestamp("expires_at") == null ? null : resultSet.getTimestamp("expires_at").toLocalDateTime(),
                resultSet.getString("approved_by_actor_id"), strings(resultSet.getString("reason_codes")),
                strings(resultSet.getString("evidence_codes")), resultSet.getTimestamp("create_time").toLocalDateTime());
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化 Agent 审批事实失败", exception);
        }
    }

    private List<String> strings(String value) {
        try {
            return value == null ? List.of() : objectMapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("解析 Agent 审批事实失败", exception);
        }
    }
}

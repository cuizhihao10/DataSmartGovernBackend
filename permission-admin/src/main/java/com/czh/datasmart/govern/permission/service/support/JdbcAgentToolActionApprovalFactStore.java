/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - JdbcAgentToolActionApprovalFactStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
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

/**
 * 权限控制面的 Agent 工具审批事实 PostgreSQL 存储。
 *
 * <p>审批事实是执行阶段可审计的授权证据，记录谁在何租户、项目、会话和运行中批准了哪个工具命令。
 * 它与 Agent Runtime 的交互确认记录分库保存，使权限中心能够独立拒绝伪造或过期授权。</p>
 */
@Component
@RequiredArgsConstructor
public class JdbcAgentToolActionApprovalFactStore implements AgentToolActionApprovalFactStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final String ATOMIC_UPSERT_SQL = """
            INSERT INTO agent_tool_action_approval_fact AS current_fact (
                approval_fact_id, tenant_id, application_id, project_id, user_id, actor_id, agent_id,
                session_id, run_id, delegation_id, command_id, tool_code, policy_version, status, expires_at,
                approved_by_actor_id, reason_codes, evidence_codes, create_time, update_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (approval_fact_id) DO UPDATE SET
                status = CASE
                    WHEN UPPER(COALESCE(current_fact.status, '')) IN ('APPROVED', 'REJECTED')
                         AND UPPER(COALESCE(EXCLUDED.status, 'PENDING')) = 'PENDING'
                    THEN current_fact.status
                    ELSE EXCLUDED.status
                END,
                expires_at = CASE
                    WHEN UPPER(COALESCE(current_fact.status, '')) IN ('APPROVED', 'REJECTED')
                         AND UPPER(COALESCE(EXCLUDED.status, 'PENDING')) = 'PENDING'
                    THEN current_fact.expires_at
                    ELSE EXCLUDED.expires_at
                END,
                approved_by_actor_id = CASE
                    WHEN UPPER(COALESCE(current_fact.status, '')) IN ('APPROVED', 'REJECTED')
                         AND UPPER(COALESCE(EXCLUDED.status, 'PENDING')) = 'PENDING'
                    THEN current_fact.approved_by_actor_id
                    ELSE EXCLUDED.approved_by_actor_id
                END,
                reason_codes = CASE
                    WHEN UPPER(COALESCE(current_fact.status, '')) IN ('APPROVED', 'REJECTED')
                         AND UPPER(COALESCE(EXCLUDED.status, 'PENDING')) = 'PENDING'
                    THEN current_fact.reason_codes
                    ELSE EXCLUDED.reason_codes
                END,
                evidence_codes = CASE
                    WHEN UPPER(COALESCE(current_fact.status, '')) IN ('APPROVED', 'REJECTED')
                         AND UPPER(COALESCE(EXCLUDED.status, 'PENDING')) = 'PENDING'
                    THEN current_fact.evidence_codes
                    ELSE EXCLUDED.evidence_codes
                END,
                update_time = CASE
                    WHEN UPPER(COALESCE(current_fact.status, '')) IN ('APPROVED', 'REJECTED')
                         AND UPPER(COALESCE(EXCLUDED.status, 'PENDING')) = 'PENDING'
                    THEN current_fact.update_time
                    ELSE CURRENT_TIMESTAMP
                END
            WHERE current_fact.tenant_id IS NOT DISTINCT FROM EXCLUDED.tenant_id
              AND current_fact.application_id IS NOT DISTINCT FROM EXCLUDED.application_id
              AND current_fact.project_id IS NOT DISTINCT FROM EXCLUDED.project_id
              AND current_fact.user_id IS NOT DISTINCT FROM EXCLUDED.user_id
              AND current_fact.actor_id IS NOT DISTINCT FROM EXCLUDED.actor_id
              AND current_fact.agent_id IS NOT DISTINCT FROM EXCLUDED.agent_id
              AND current_fact.session_id IS NOT DISTINCT FROM EXCLUDED.session_id
              AND current_fact.run_id IS NOT DISTINCT FROM EXCLUDED.run_id
              AND current_fact.delegation_id IS NOT DISTINCT FROM EXCLUDED.delegation_id
              AND current_fact.command_id IS NOT DISTINCT FROM EXCLUDED.command_id
              AND current_fact.tool_code IS NOT DISTINCT FROM EXCLUDED.tool_code
              AND current_fact.policy_version IS NOT DISTINCT FROM EXCLUDED.policy_version
            RETURNING *
            """;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 幂等新增或更新审批事实。
     *
     * <p>相同 approvalFactId 重试时只更新状态、有效期、审批人、理由、证据和策略版本，不改写租户、
     * 项目、用户、会话、运行、命令和工具等身份边界，防止一个事实编号被挪用到其他资源。</p>
     *
     * @param record 已经过可信服务登记守卫校验的审批事实
     * @return 数据库中的最终记录；极端并发下读不到时回退返回调用方记录
     */
    @Override
    public AgentToolActionApprovalFactRecord save(AgentToolActionApprovalFactRecord record) {
        List<AgentToolActionApprovalFactRecord> records = jdbcTemplate.query(
                ATOMIC_UPSERT_SQL,
                this::mapRecord,
                record.approvalFactId(), record.tenantId(), record.applicationId(), record.projectId(),
                record.userId(), record.actorId(), record.agentId(), record.sessionId(), record.runId(),
                record.delegationId(), record.commandId(), record.toolCode(), record.policyVersion(), record.status(),
                record.expiresAt(), record.approvedByActorId(), json(record.reasonCodes()), json(record.evidenceCodes()),
                record.createdAt());
        if (records.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.DUPLICATE_OPERATION,
                    "approvalFactId 的双主体 scope 或 policyVersion 与已有事实不一致，拒绝覆盖");
        }
        return records.get(0);
    }

    /**
     * 按事实编号查询审批证据。
     *
     * @param approvalFactId 事实唯一编号；空白值直接返回空
     * @return 已持久化的审批事实
     */
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

    /** 将数据库行映射为审批领域记录，并恢复 JSONB 理由与证据列表。 */
    private AgentToolActionApprovalFactRecord mapRecord(ResultSet resultSet, int rowNum) throws SQLException {
        return new AgentToolActionApprovalFactRecord(
                resultSet.getString("approval_fact_id"), resultSet.getObject("tenant_id", Long.class),
                resultSet.getObject("application_id", Long.class), resultSet.getObject("project_id", Long.class),
                resultSet.getString("user_id"), resultSet.getString("actor_id"), resultSet.getString("agent_id"),
                resultSet.getString("session_id"), resultSet.getString("run_id"), resultSet.getString("delegation_id"),
                resultSet.getString("command_id"), resultSet.getString("tool_code"),
                resultSet.getString("policy_version"), resultSet.getString("status"),
                resultSet.getTimestamp("expires_at") == null ? null : resultSet.getTimestamp("expires_at").toLocalDateTime(),
                resultSet.getString("approved_by_actor_id"), strings(resultSet.getString("reason_codes")),
                strings(resultSet.getString("evidence_codes")), resultSet.getTimestamp("create_time").toLocalDateTime());
    }

    /** 将理由或证据编码写成 JSONB 文本；序列化失败时拒绝生成不可审计的事实。 */
    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化 Agent 审批事实失败", exception);
        }
    }

    /** 把 JSONB 理由或证据恢复为字符串列表，数据库空值按空列表处理。 */
    private List<String> strings(String value) {
        try {
            return value == null ? List.of() : objectMapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("解析 Agent 审批事实失败", exception);
        }
    }
}

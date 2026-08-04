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
                resultSet.getObject("project_id", Long.class), resultSet.getString("actor_id"),
                resultSet.getString("session_id"), resultSet.getString("run_id"),
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

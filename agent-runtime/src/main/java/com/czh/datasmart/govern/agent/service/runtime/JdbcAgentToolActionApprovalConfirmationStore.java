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

/**
 * 受控 Agent 工具“用户确认记录”的 PostgreSQL 存储。
 *
 * <p>确认记录回答的是“用户是否确认了这一次具体提案和载荷”，它不能单独代表业务权限，也不能替代
 * permission-admin 中的审批事实。工具执行时仍需同时校验用户身份、委托范围、策略版本、资源范围和
 * 下游接口权限。</p>
 */
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

    /**
     * 创建确认记录存储。
     *
     * @param connectionManager Agent Runtime 统一 JDBC 连接管理器
     * @param objectMapper 用于持久化用户所接受的载荷证据列表
     */
    public JdbcAgentToolActionApprovalConfirmationStore(AgentRuntimeJdbcConnectionManager connectionManager,
                                                         ObjectMapper objectMapper) {
        this.connectionManager = connectionManager;
        this.objectMapper = objectMapper;
    }

    /**
     * 仅在 confirmationId 尚不存在时保存确认记录。
     *
     * <p>数据库唯一约束和 {@code ON CONFLICT DO NOTHING} 共同提供幂等性：网络重试不会制造第二份确认，
     * 返回 false 表示该编号已经登记，而不是数据库失败。调用方应继续使用原记录，不应覆盖审批证据。</p>
     *
     * @param record 已绑定用户、租户、项目、工具、载荷摘要和有效期的确认记录
     * @return true 表示本次新插入，false 表示同编号记录此前已存在
     */
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

    /**
     * 按确认编号读取持久化证据。
     *
     * @param confirmationId 确认记录唯一编号；空白值直接返回空
     * @return 完整确认记录，供执行前核对身份、策略、载荷摘要和有效期
     */
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

    /**
     * 清理已过期确认，避免短期授权凭据无限增长。
     *
     * @param now 判定过期的基准时刻；传 null 时使用当前时刻
     * @return 实际删除记录数，便于调度任务记录清理效果
     */
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

    /** 把 JDBC 行完整还原为领域记录；时间统一转回 Instant 以保留绝对时间语义。 */
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

    /** 将确认时接受的证据编码序列化为 JSONB 输入；失败时拒绝保存不完整证据。 */
    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化 Agent 工具确认事实失败", exception);
        }
    }

    /** 将 JSONB 证据恢复为只读语义的空列表或字符串列表。 */
    private List<String> strings(String value) {
        try {
            return value == null ? List.of() : objectMapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("解析 Agent 工具确认事实失败", exception);
        }
    }

    /** 把可空 Instant 转为 JDBC Timestamp，兼容无过期时间的策略。 */
    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    /** 把可空 JDBC Timestamp 恢复为绝对时间。 */
    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}

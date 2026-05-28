/**
 * @Author : Cui
 * @Date: 2026/05/28 19:34
 * @Description DataSmart Govern Backend - JdbcAgentToolExecutionAuditStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.audit;

import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

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

/**
 * 基于 JDBC/MySQL 的工具执行审计仓储。
 *
 * <p>该实现只在同时满足以下条件时注册：
 * {@code datasmart.agent-runtime.persistence.audit-store=mysql} 且
 * {@code datasmart.agent-runtime.persistence.database-enabled=true}。
 * 默认本地环境仍然使用内存仓储，因此加入该类不会让普通单元测试或学习环境强制连接数据库。</p>
 *
 * <p>为什么这里先采用“手写 JDBC”而不是直接引入 MyBatis-Plus：
 * 1. agent-runtime 之前没有数据库依赖，贸然加入 MyBatis starter 可能触发全局 DataSource 自动配置，破坏默认本地启动；
 * 2. 当前仓储只需要非常有限的 upsert、findById 和 list 能力，手写 JDBC 足够清晰，也便于学习 SQL 与对象映射的原理；
 * 3. 后续如果 agent-runtime 整体数据库化，再迁移到 MyBatis-Plus Entity/Mapper 也不会影响服务层，因为上层只依赖
 *    {@link AgentToolExecutionAuditStore} 端口。</p>
 *
 * <p>商业化注意事项：
 * - 该 store 会通过 Agent Runtime JDBC 连接管理器取连接；当服务层打开事务时，可与 outbox store 复用同一连接；
 * - 当前查询接口没有分页对象，因此数据库实现强制追加 maxQueryLimit，避免误查询拖垮审计表；
 * - planArguments、governanceHints、parameterValidation 以 JSON 保存，后续如果需要高频过滤，应把关键维度拆成独立列或索引表。</p>
 */
@Component
@ConditionalOnExpression(
        "'${datasmart.agent-runtime.persistence.audit-store:memory}'.equalsIgnoreCase('mysql') "
                + "&& '${datasmart.agent-runtime.persistence.database-enabled:false}'.equalsIgnoreCase('true')"
)
public class JdbcAgentToolExecutionAuditStore implements AgentToolExecutionAuditStore {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP_TYPE = new TypeReference<>() {
    };

    private static final String SELECT_COLUMNS = """
            id, audit_id, session_id, run_id, binding_id, tool_code, tool_type,
            target_service, target_endpoint, target_resource_id,
            tenant_id, project_id, workspace_id, actor_id,
            risk_level, execution_mode, requires_approval, read_only, idempotent,
            allowed_actions, plan_reason, plan_arguments, governance_hints, parameter_validation,
            state, trace_id, message, approval_operator_id, approval_comment, approval_time,
            execution_start_time, execution_finish_time, output_summary, error_code,
            create_time, update_time
            """;

    /**
     * MySQL upsert SQL。
     *
     * <p>以 audit_id 唯一键作为幂等边界：同一条审计记录首次创建时 INSERT，状态推进后再次 save 时 UPDATE。
     * 后续若把状态更新和 outbox 写入放进同一事务，可以在业务服务层围绕该 save 动作继续扩展事务模板。</p>
     */
    private static final String UPSERT_SQL = """
            INSERT INTO agent_tool_execution_audit (
                audit_id, session_id, run_id, binding_id, tool_code, tool_type,
                target_service, target_endpoint, target_resource_id,
                tenant_id, project_id, workspace_id, actor_id,
                risk_level, execution_mode, requires_approval, read_only, idempotent,
                allowed_actions, plan_reason, plan_arguments, governance_hints, parameter_validation,
                state, trace_id, message, approval_operator_id, approval_comment, approval_time,
                execution_start_time, execution_finish_time, output_summary, error_code,
                create_time, update_time
            ) VALUES (
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?
            )
            ON DUPLICATE KEY UPDATE
                session_id = VALUES(session_id),
                run_id = VALUES(run_id),
                binding_id = VALUES(binding_id),
                tool_code = VALUES(tool_code),
                tool_type = VALUES(tool_type),
                target_service = VALUES(target_service),
                target_endpoint = VALUES(target_endpoint),
                target_resource_id = VALUES(target_resource_id),
                tenant_id = VALUES(tenant_id),
                project_id = VALUES(project_id),
                workspace_id = VALUES(workspace_id),
                actor_id = VALUES(actor_id),
                risk_level = VALUES(risk_level),
                execution_mode = VALUES(execution_mode),
                requires_approval = VALUES(requires_approval),
                read_only = VALUES(read_only),
                idempotent = VALUES(idempotent),
                allowed_actions = VALUES(allowed_actions),
                plan_reason = VALUES(plan_reason),
                plan_arguments = VALUES(plan_arguments),
                governance_hints = VALUES(governance_hints),
                parameter_validation = VALUES(parameter_validation),
                state = VALUES(state),
                trace_id = VALUES(trace_id),
                message = VALUES(message),
                approval_operator_id = VALUES(approval_operator_id),
                approval_comment = VALUES(approval_comment),
                approval_time = VALUES(approval_time),
                execution_start_time = VALUES(execution_start_time),
                execution_finish_time = VALUES(execution_finish_time),
                output_summary = VALUES(output_summary),
                error_code = VALUES(error_code),
                update_time = VALUES(update_time)
            """;

    private final AgentRuntimeJdbcConnectionManager connectionManager;
    private final ObjectMapper objectMapper;
    private final int maxQueryLimit;

    public JdbcAgentToolExecutionAuditStore(AgentRuntimeJdbcConnectionManager connectionManager,
                                            ObjectMapper objectMapper,
                                            AgentRuntimePersistenceProperties properties) {
        this.connectionManager = connectionManager;
        this.objectMapper = objectMapper;
        this.maxQueryLimit = Math.max(1, properties.getJdbc().getMaxQueryLimit());
    }

    /**
     * 保存单条工具审计记录。
     *
     * <p>该方法是工具状态进入 MySQL 的最小持久化单元。当前使用自动提交，适合单条状态更新；
     * 后续接入 outbox 同事务时，应由更外层事务模板同时调用 audit store 与 outbox store，或者把两者合并到一个事务服务中。</p>
     */
    @Override
    public void save(AgentToolExecutionAuditRecord audit) {
        try {
            connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
                    bindAudit(statement, audit);
                    statement.executeUpdate();
                    return null;
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("保存 Agent 工具执行审计到 MySQL 失败，auditId=" + audit.getAuditId(), exception);
        }
    }

    /**
     * 批量保存工具审计记录。
     *
     * <p>一次 AgentPlan 可能生成多个工具计划。批量保存使用同一个连接和事务提交，可以减少网络往返，
     * 也能避免“同一个 run 的部分工具计划入库成功、部分失败”的中间状态。</p>
     */
    @Override
    public void saveAll(List<AgentToolExecutionAuditRecord> audits) {
        if (audits == null || audits.isEmpty()) {
            return;
        }
        try {
            connectionManager.executeInTransaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
                    bindAuditBatch(statement, audits);
                    statement.executeBatch();
                    return null;
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("批量保存 Agent 工具执行审计到 MySQL 失败，count=" + audits.size(), exception);
        }
    }

    /**
     * 绑定批量保存参数。
     *
     * <p>该方法看似只是循环调用 bindAudit，但保留独立注释的原因是为了强调：
     * 批量保存也会走 {@link AgentRuntimeJdbcConnectionManager#executeInTransaction(AgentRuntimeJdbcConnectionManager.SqlConnectionCallback)}。
     * 如果外层服务已经开启“审计 + outbox”事务，这里会复用外层连接；如果单独调用 saveAll，也能保证同一批计划要么全部入库，要么全部回滚。</p>
     */
    private void bindAuditBatch(PreparedStatement statement, List<AgentToolExecutionAuditRecord> audits) throws SQLException {
        for (AgentToolExecutionAuditRecord audit : audits) {
            bindAudit(statement, audit);
            statement.addBatch();
        }
    }

    /**
     * 按审计 ID 查询单条工具审计记录。
     *
     * <p>auditId 是工具调用链路的业务主键。Python Runtime、前端审批页和审计中心都应优先用它定位某一次工具计划。</p>
     */
    @Override
    public Optional<AgentToolExecutionAuditRecord> findById(String auditId) {
        if (auditId == null || auditId.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT " + SELECT_COLUMNS + " FROM agent_tool_execution_audit WHERE audit_id = ?";
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, auditId);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            return Optional.empty();
                        }
                        return Optional.of(toRecord(resultSet));
                    }
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("查询 Agent 工具执行审计失败，auditId=" + auditId, exception);
        }
    }

    /**
     * 按 session/run 查询工具审计列表。
     *
     * <p>当前端口还没有分页对象，因此这里无论是否传入 sessionId/runId 都会追加 LIMIT。
     * 生产环境的管理后台后续应升级为带 tenant/project/role/page/pageSize 的查询对象，避免大租户审计表被误扫。</p>
     */
    @Override
    public List<AgentToolExecutionAuditRecord> list(String sessionId, String runId) {
        QueryPlan queryPlan = buildListQuery(sessionId, runId);
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(queryPlan.sql())) {
                    int index = 1;
                    for (String parameter : queryPlan.parameters()) {
                        statement.setString(index++, parameter);
                    }
                    statement.setInt(index, maxQueryLimit);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        List<AgentToolExecutionAuditRecord> records = new ArrayList<>();
                        while (resultSet.next()) {
                            records.add(toRecord(resultSet));
                        }
                        return records;
                    }
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("查询 Agent 工具执行审计列表失败，sessionId=" + sessionId + ", runId=" + runId, exception);
        }
    }

    private QueryPlan buildListQuery(String sessionId, String runId) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(SELECT_COLUMNS)
                .append(" FROM agent_tool_execution_audit WHERE 1 = 1");
        List<String> parameters = new ArrayList<>();
        if (sessionId != null && !sessionId.isBlank()) {
            sql.append(" AND session_id = ?");
            parameters.add(sessionId);
        }
        if (runId != null && !runId.isBlank()) {
            sql.append(" AND run_id = ?");
            parameters.add(runId);
        }
        sql.append(" ORDER BY create_time ASC, id ASC LIMIT ?");
        return new QueryPlan(sql.toString(), parameters);
    }

    private void bindAudit(PreparedStatement statement, AgentToolExecutionAuditRecord audit) throws SQLException {
        int index = 1;
        statement.setString(index++, audit.getAuditId());
        statement.setString(index++, audit.getSessionId());
        statement.setString(index++, audit.getRunId());
        statement.setString(index++, audit.getBindingId());
        statement.setString(index++, audit.getToolCode());
        statement.setString(index++, audit.getToolType());
        setNullableString(statement, index++, audit.getTargetService());
        setNullableString(statement, index++, audit.getTargetEndpoint());
        setNullableLong(statement, index++, audit.getTargetResourceId());
        setNullableLong(statement, index++, audit.getTenantId());
        setNullableLong(statement, index++, audit.getProjectId());
        setNullableLong(statement, index++, audit.getWorkspaceId());
        setNullableString(statement, index++, audit.getActorId());
        statement.setString(index++, audit.getRiskLevel());
        statement.setString(index++, audit.getExecutionMode());
        statement.setBoolean(index++, Boolean.TRUE.equals(audit.getRequiresApproval()));
        statement.setBoolean(index++, Boolean.TRUE.equals(audit.getReadOnly()));
        statement.setBoolean(index++, Boolean.TRUE.equals(audit.getIdempotent()));
        statement.setString(index++, toJson(audit.getAllowedActions() == null ? List.of() : audit.getAllowedActions()));
        setNullableString(statement, index++, audit.getPlanReason());
        statement.setString(index++, toJson(audit.getPlanArguments()));
        statement.setString(index++, toJson(audit.getGovernanceHints()));
        statement.setString(index++, toJson(audit.getParameterValidation()));
        statement.setString(index++, audit.getState().name());
        setNullableString(statement, index++, audit.getTraceId());
        setNullableString(statement, index++, audit.getMessage());
        setNullableString(statement, index++, audit.getApprovalOperatorId());
        setNullableString(statement, index++, audit.getApprovalComment());
        setNullableTimestamp(statement, index++, audit.getApprovalTime());
        setNullableTimestamp(statement, index++, audit.getExecutionStartTime());
        setNullableTimestamp(statement, index++, audit.getExecutionFinishTime());
        setNullableString(statement, index++, audit.getOutputSummary());
        setNullableString(statement, index++, audit.getErrorCode());
        setNullableTimestamp(statement, index++, audit.getCreateTime());
        setNullableTimestamp(statement, index, audit.getUpdateTime());
    }

    private AgentToolExecutionAuditRecord toRecord(ResultSet resultSet) throws SQLException {
        AgentToolExecutionAuditRecord record = new AgentToolExecutionAuditRecord(
                resultSet.getString("audit_id"),
                resultSet.getString("session_id"),
                resultSet.getString("run_id"),
                resultSet.getString("binding_id"),
                resultSet.getString("tool_code"),
                resultSet.getString("tool_type"),
                resultSet.getString("target_service"),
                resultSet.getString("target_endpoint"),
                getNullableLong(resultSet, "target_resource_id"),
                getNullableLong(resultSet, "tenant_id"),
                getNullableLong(resultSet, "project_id"),
                getNullableLong(resultSet, "workspace_id"),
                resultSet.getString("actor_id"),
                resultSet.getString("risk_level"),
                resultSet.getString("execution_mode"),
                resultSet.getBoolean("requires_approval"),
                resultSet.getBoolean("read_only"),
                resultSet.getBoolean("idempotent"),
                fromJsonList(resultSet.getString("allowed_actions")),
                resultSet.getString("plan_reason"),
                fromJsonMap(resultSet.getString("plan_arguments")),
                fromJsonMap(resultSet.getString("governance_hints")),
                fromJsonMap(resultSet.getString("parameter_validation")),
                AgentToolExecutionState.valueOf(resultSet.getString("state")),
                resultSet.getString("trace_id"),
                resultSet.getString("message"),
                getLocalDateTime(resultSet, "create_time")
        );
        record.restoreMutableFields(
                resultSet.getString("approval_operator_id"),
                resultSet.getString("approval_comment"),
                getLocalDateTime(resultSet, "approval_time"),
                getLocalDateTime(resultSet, "execution_start_time"),
                getLocalDateTime(resultSet, "execution_finish_time"),
                resultSet.getString("output_summary"),
                resultSet.getString("error_code"),
                getLocalDateTime(resultSet, "update_time")
        );
        return record;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化 Agent 工具执行审计 JSON 字段失败", exception);
        }
    }

    private List<String> fromJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("反序列化 Agent 工具 allowed_actions 失败", exception);
        }
    }

    private Map<String, Object> fromJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> value = objectMapper.readValue(json, OBJECT_MAP_TYPE);
            return value == null ? Map.of() : value;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("反序列化 Agent 工具执行审计 JSON 字段失败", exception);
        }
    }

    private void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private void setNullableTimestamp(PreparedStatement statement, int index, LocalDateTime value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.valueOf(value));
        }
    }

    private Long getNullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private LocalDateTime getLocalDateTime(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private record QueryPlan(String sql, List<String> parameters) {
    }
}

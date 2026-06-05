/**
 * @Author : Cui
 * @Date: 2026/06/01 19:30
 * @Description DataSmart Govern Backend - JdbcAgentRunToolDagConfirmationRecordMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution.confirmation;

import com.czh.datasmart.govern.agent.model.AgentHandoffDagBridgeSourceEvidence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

/**
 * DAG selected-node 确认记录的 JDBC 字段映射器。
 *
 * <p>这个类刻意保持为 package-private 工具类，而不是 Spring Bean。它不决定“什么时候确认”“确认是否有效”
 * 或“是否允许继续执行”，只负责把 {@link AgentRunToolDagConfirmationRecord} 与
 * {@code agent_run_tool_dag_confirmation} 表之间做稳定映射。这样拆分后，Store 负责持久化语义，
 * Mapper 负责字段顺序、JSON 编解码和 JDBC null 绑定，避免一个 Store 文件继续变成新的大 Impl。</p>
 *
 * <p>安全约束：表中的 JSON 字段只保存 ID、策略版本和授权证据摘要，不保存工具参数、SQL、prompt、
 * 样本数据或连接密钥。这里的序列化方法也只接受 {@code List<String>}，从类型层面降低误把复杂敏感对象写入确认表的概率。</p>
 */
final class JdbcAgentRunToolDagConfirmationRecordMapper {

    /**
     * 所有查询统一复用的字段清单。
     *
     * <p>确认记录未来会被多个入口读取：确认响应回读、审计台详情、run 时间线、管理员补偿台等。
     * 如果每个查询都手写字段，很容易出现某个入口漏掉 policyVersions 或 delegationEvidence 的问题。
     * 集中维护 SELECT 字段能保证“写入什么，回读就能看到什么”。</p>
     */
    static final String SELECT_COLUMNS = """
            confirmation_id, session_id, run_id, selection_fingerprint,
            selected_node_ids, selected_audit_ids, policy_versions, delegation_evidence,
            bridge_source_evidence, outbox_ids, command_ids, tenant_id, project_id, workspace_id, actor_id, trace_id,
            confirmed, status, expires_at, create_time, update_time
            """;

    /**
     * MySQL 插入确认记录的 SQL。
     *
     * <p>confirmation_id 有唯一索引。重复确认时不依赖“先查再插”的应用层判断，因为并发下两个请求可能同时查不到。
     * 正确做法是直接 INSERT，让数据库唯一索引作为最终仲裁；如果发生唯一键冲突，Store 捕获后回读已有记录，
     * 返回同一条确认事实，从而实现幂等确认。</p>
     */
    static final String INSERT_SQL = """
            INSERT INTO agent_run_tool_dag_confirmation (
                confirmation_id, session_id, run_id, selection_fingerprint,
                selected_node_ids, selected_audit_ids, policy_versions, delegation_evidence,
                bridge_source_evidence, outbox_ids, command_ids, tenant_id, project_id, workspace_id, actor_id, trace_id,
                confirmed, status, expires_at, create_time, update_time
            ) VALUES (
                ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?
            )
            """;

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private JdbcAgentRunToolDagConfirmationRecordMapper() {
    }

    /**
     * 绑定确认记录 INSERT 参数。
     *
     * <p>字段顺序必须与 {@link #INSERT_SQL} 完全一致。JSON 字段统一通过 ObjectMapper 序列化为字符串，
     * 而不是依赖某个数据库驱动的 JSON 专有对象，原因是单元测试、MySQL 驱动和未来可能的兼容数据库
     * 对 JSON JDBC 类型支持并不完全一致；以字符串写入 JSON 列是最稳妥的跨环境做法。</p>
     */
    static void bindRecord(PreparedStatement statement,
                           AgentRunToolDagConfirmationRecord record,
                           ObjectMapper objectMapper) throws SQLException {
        int index = 1;
        statement.setString(index++, record.confirmationId());
        statement.setString(index++, record.sessionId());
        statement.setString(index++, record.runId());
        statement.setString(index++, record.selectionFingerprint());
        statement.setString(index++, toJson(record.selectedNodeIds(), objectMapper, "selectedNodeIds"));
        statement.setString(index++, toJson(record.selectedAuditIds(), objectMapper, "selectedAuditIds"));
        statement.setString(index++, toJson(record.policyVersions(), objectMapper, "policyVersions"));
        statement.setString(index++, toJson(record.delegationEvidence(), objectMapper, "delegationEvidence"));
        setNullableString(statement, index++, toJson(record.bridgeSourceEvidence(), objectMapper, "bridgeSourceEvidence"));
        statement.setString(index++, toJson(record.outboxIds(), objectMapper, "outboxIds"));
        statement.setString(index++, toJson(record.commandIds(), objectMapper, "commandIds"));
        setNullableLong(statement, index++, record.tenantId());
        setNullableLong(statement, index++, record.projectId());
        setNullableLong(statement, index++, record.workspaceId());
        setNullableString(statement, index++, record.actorId());
        setNullableString(statement, index++, record.traceId());
        statement.setBoolean(index++, Boolean.TRUE.equals(record.confirmed()));
        statement.setString(index++, record.status().name());
        setNullableTimestamp(statement, index++, record.expiresAt());
        setNullableTimestamp(statement, index++, record.createdAt());
        setNullableTimestamp(statement, index, record.updatedAt());
    }

    /**
     * 从 ResultSet 还原确认记录。
     *
     * <p>Mapper 不在这里判断记录是否过期、是否撤销、是否还允许补偿执行。数据库里是什么状态就还原成什么状态；
     * 上层服务或未来审计查询服务再根据 status/expiresAt 做产品语义判断，避免映射层悄悄改变历史事实。</p>
     */
    static AgentRunToolDagConfirmationRecord toRecord(ResultSet resultSet,
                                                       ObjectMapper objectMapper) throws SQLException {
        return new AgentRunToolDagConfirmationRecord(
                resultSet.getString("confirmation_id"),
                resultSet.getString("session_id"),
                resultSet.getString("run_id"),
                resultSet.getString("selection_fingerprint"),
                fromJson(resultSet.getString("selected_node_ids"), objectMapper, "selected_node_ids"),
                fromJson(resultSet.getString("selected_audit_ids"), objectMapper, "selected_audit_ids"),
                fromJson(resultSet.getString("policy_versions"), objectMapper, "policy_versions"),
                fromJson(resultSet.getString("delegation_evidence"), objectMapper, "delegation_evidence"),
                fromBridgeSourceJson(resultSet.getString("bridge_source_evidence"), objectMapper, "bridge_source_evidence"),
                fromJson(resultSet.getString("outbox_ids"), objectMapper, "outbox_ids"),
                fromJson(resultSet.getString("command_ids"), objectMapper, "command_ids"),
                getNullableLong(resultSet, "tenant_id"),
                getNullableLong(resultSet, "project_id"),
                getNullableLong(resultSet, "workspace_id"),
                resultSet.getString("actor_id"),
                resultSet.getString("trace_id"),
                resultSet.getBoolean("confirmed"),
                AgentRunToolDagConfirmationStatus.valueOf(resultSet.getString("status")),
                getInstant(resultSet, "expires_at"),
                getInstant(resultSet, "create_time"),
                getInstant(resultSet, "update_time")
        );
    }

    static void bindParameters(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object parameter = parameters.get(index);
            int jdbcIndex = index + 1;
            if (parameter == null) {
                statement.setNull(jdbcIndex, Types.NULL);
            } else if (parameter instanceof Integer integer) {
                statement.setInt(jdbcIndex, integer);
            } else {
                statement.setString(jdbcIndex, parameter.toString());
            }
        }
    }

    private static String toJson(List<String> values, ObjectMapper objectMapper, String fieldName) throws SQLException {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new SQLException("序列化 DAG 确认记录 JSON 字段失败: " + fieldName, exception);
        }
    }

    private static String toJson(AgentHandoffDagBridgeSourceEvidence value,
                                 ObjectMapper objectMapper,
                                 String fieldName) throws SQLException {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new SQLException("序列化 DAG 确认记录 bridge 来源证据失败: " + fieldName, exception);
        }
    }

    private static List<String> fromJson(String json, ObjectMapper objectMapper, String columnName) throws SQLException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            throw new SQLException("反序列化 DAG 确认记录 JSON 字段失败: " + columnName, exception);
        }
    }

    private static AgentHandoffDagBridgeSourceEvidence fromBridgeSourceJson(String json,
                                                                            ObjectMapper objectMapper,
                                                                            String columnName) throws SQLException {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, AgentHandoffDagBridgeSourceEvidence.class);
        } catch (JsonProcessingException exception) {
            throw new SQLException("反序列化 DAG 确认记录 bridge 来源证据失败: " + columnName, exception);
        }
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setNullableTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.from(value));
        }
    }

    private static Long getNullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Instant getInstant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}

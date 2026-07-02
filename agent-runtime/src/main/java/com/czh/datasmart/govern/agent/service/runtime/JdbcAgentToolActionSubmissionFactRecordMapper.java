/**
 * @Author : Cui
 * @Date: 2026/06/28 21:46
 * @Description DataSmart Govern Backend - JdbcAgentToolActionSubmissionFactRecordMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * 受控工具提交事实 JDBC 字段映射器。
 *
 * <p>该类不是业务服务，只负责 Java record 与 MySQL 行之间的字段翻译。
 * 把 SQL、参数绑定、列表序列化和 ResultSet 还原拆到独立 mapper 后，
 * {@link JdbcAgentToolActionSubmissionFactStore} 可以专注于“start 是否原子、防重是否正确、保存是否可恢复”。</p>
 *
 * <p>安全边界：
 * 1. 不存在 payload/body/arguments/prompt/sql/model_output 字段；
 * 2. targetEndpoint 只允许保存端点模板，不允许保存完整 URL；
 * 3. issueCodes/recommendedActions 是低敏机器码和动作建议，不能写入人工输入正文；
 * 4. lowSensitiveMessage 有长度限制和敏感模式过滤。</p>
 */
final class JdbcAgentToolActionSubmissionFactRecordMapper {

    /**
     * 所有查询复用的字段清单。
     *
     * <p>统一 SELECT 字段能降低表结构演进时的漏改概率。数据库自增 id 不进入领域模型，
     * 因为提交事实以 commandId/submissionIdentityKey 为业务定位。</p>
     */
    static final String SELECT_COLUMNS = """
            submission_identity_key, command_id, idempotency_key, session_id, run_id,
            audit_id, tool_code, tenant_id, project_id, actor_id,
            payload_reference, confirmation_id, policy_version, target_service, target_endpoint,
            status, side_effect_started, side_effect_executed, outcome,
            downstream_task_id, downstream_task_status, error_code, issue_codes,
            recommended_actions, low_sensitive_message, first_submitted_at, last_updated_at
            """;

    /**
     * 首次登记 SUBMITTING 事实使用的 INSERT。
     *
     * <p>start 路径不能使用无脑 upsert。原因是 upsert 会让第二个并发 worker 覆盖第一个 worker 的
     * SUBMITTING 事实，从而误以为自己也可以继续调用下游。start 必须依赖唯一键和事务语义区分胜负。</p>
     */
    static final String INSERT_SQL = """
            INSERT INTO agent_tool_action_submission_fact (
                submission_identity_key, command_id, idempotency_key, session_id, run_id,
                audit_id, tool_code, tenant_id, project_id, actor_id,
                payload_reference, confirmation_id, policy_version, target_service, target_endpoint,
                status, side_effect_started, side_effect_executed, outcome,
                downstream_task_id, downstream_task_status, error_code, issue_codes,
                recommended_actions, low_sensitive_message, first_submitted_at, last_updated_at
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?, ?
            )
            """;

    /**
     * 保存终态或 UNKNOWN 状态使用的 UPSERT。
     *
     * <p>save 允许 upsert 是为了兼容两个现实场景：
     * 1. 历史版本尚未在提交前登记 SUBMITTING，但后续补偿任务想把结果补物化；
     * 2. MySQL 连接短暂抖动后，调用方重新保存同一 commandId 的终态。</p>
     */
    static final String UPSERT_SQL = """
            INSERT INTO agent_tool_action_submission_fact (
                submission_identity_key, command_id, idempotency_key, session_id, run_id,
                audit_id, tool_code, tenant_id, project_id, actor_id,
                payload_reference, confirmation_id, policy_version, target_service, target_endpoint,
                status, side_effect_started, side_effect_executed, outcome,
                downstream_task_id, downstream_task_status, error_code, issue_codes,
                recommended_actions, low_sensitive_message, first_submitted_at, last_updated_at
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?, ?
            )
            ON CONFLICT (submission_identity_key) DO UPDATE SET
                idempotency_key = EXCLUDED.idempotency_key,
                session_id = EXCLUDED.session_id,
                run_id = EXCLUDED.run_id,
                audit_id = EXCLUDED.audit_id,
                tool_code = EXCLUDED.tool_code,
                tenant_id = EXCLUDED.tenant_id,
                project_id = EXCLUDED.project_id,
                actor_id = EXCLUDED.actor_id,
                payload_reference = EXCLUDED.payload_reference,
                confirmation_id = EXCLUDED.confirmation_id,
                policy_version = EXCLUDED.policy_version,
                target_service = EXCLUDED.target_service,
                target_endpoint = EXCLUDED.target_endpoint,
                status = EXCLUDED.status,
                side_effect_started = EXCLUDED.side_effect_started,
                side_effect_executed = EXCLUDED.side_effect_executed,
                outcome = EXCLUDED.outcome,
                downstream_task_id = EXCLUDED.downstream_task_id,
                downstream_task_status = EXCLUDED.downstream_task_status,
                error_code = EXCLUDED.error_code,
                issue_codes = EXCLUDED.issue_codes,
                recommended_actions = EXCLUDED.recommended_actions,
                low_sensitive_message = EXCLUDED.low_sensitive_message,
                last_updated_at = EXCLUDED.last_updated_at,
                update_time = CURRENT_TIMESTAMP
            """;

    private JdbcAgentToolActionSubmissionFactRecordMapper() {
    }

    static void bindRecord(PreparedStatement statement,
                           AgentToolActionSubmissionFactRecord record) throws SQLException {
        int index = 1;
        setNullableString(statement, index++, truncate(record.submissionIdentityKey(), 220));
        setNullableString(statement, index++, truncate(record.commandId(), 180));
        setNullableString(statement, index++, truncate(record.idempotencyKey(), 180));
        setNullableString(statement, index++, truncate(record.sessionId(), 180));
        setNullableString(statement, index++, truncate(record.runId(), 180));
        setNullableString(statement, index++, truncate(record.auditId(), 200));
        setNullableString(statement, index++, truncate(record.toolCode(), 180));
        setNullableString(statement, index++, truncate(record.tenantId(), 80));
        setNullableString(statement, index++, truncate(record.projectId(), 80));
        setNullableString(statement, index++, truncate(record.actorId(), 120));
        setNullableString(statement, index++, truncate(record.payloadReference(), 260));
        setNullableString(statement, index++, truncate(record.confirmationId(), 220));
        setNullableString(statement, index++, truncate(record.policyVersion(), 120));
        setNullableString(statement, index++, truncate(record.targetService(), 120));
        setNullableString(statement, index++, truncate(record.targetEndpoint(), 180));
        setNullableString(statement, index++, record.status().name());
        statement.setBoolean(index++, Boolean.TRUE.equals(record.sideEffectStarted()));
        statement.setBoolean(index++, Boolean.TRUE.equals(record.sideEffectExecuted()));
        setNullableString(statement, index++, truncate(record.outcome(), 120));
        setNullableLong(statement, index++, record.downstreamTaskId());
        setNullableString(statement, index++, truncate(record.downstreamTaskStatus(), 80));
        setNullableString(statement, index++, truncate(record.errorCode(), 160));
        setNullableString(statement, index++, join(record.issueCodes(), 900));
        setNullableString(statement, index++, join(record.recommendedActions(), 1000));
        setNullableString(statement, index++, truncate(record.lowSensitiveMessage(), 300));
        setNullableTimestamp(statement, index++, record.firstSubmittedAt());
        setNullableTimestamp(statement, index, record.lastUpdatedAt());
    }

    static AgentToolActionSubmissionFactRecord toRecord(ResultSet resultSet) throws SQLException {
        return new AgentToolActionSubmissionFactRecord(
                resultSet.getString("submission_identity_key"),
                resultSet.getString("command_id"),
                resultSet.getString("idempotency_key"),
                resultSet.getString("session_id"),
                resultSet.getString("run_id"),
                resultSet.getString("audit_id"),
                resultSet.getString("tool_code"),
                resultSet.getString("tenant_id"),
                resultSet.getString("project_id"),
                resultSet.getString("actor_id"),
                resultSet.getString("payload_reference"),
                resultSet.getString("confirmation_id"),
                resultSet.getString("policy_version"),
                resultSet.getString("target_service"),
                resultSet.getString("target_endpoint"),
                status(resultSet.getString("status")),
                resultSet.getBoolean("side_effect_started"),
                resultSet.getBoolean("side_effect_executed"),
                resultSet.getString("outcome"),
                nullableLong(resultSet, "downstream_task_id"),
                resultSet.getString("downstream_task_status"),
                resultSet.getString("error_code"),
                split(resultSet.getString("issue_codes")),
                split(resultSet.getString("recommended_actions")),
                resultSet.getString("low_sensitive_message"),
                getInstant(resultSet, "first_submitted_at"),
                getInstant(resultSet, "last_updated_at")
        );
    }

    private static AgentToolActionSubmissionStatus status(String value) {
        if (value == null || value.isBlank()) {
            return AgentToolActionSubmissionStatus.UNKNOWN;
        }
        try {
            return AgentToolActionSubmissionStatus.valueOf(value.trim());
        } catch (IllegalArgumentException ignored) {
            return AgentToolActionSubmissionStatus.UNKNOWN;
        }
    }

    private static String join(List<String> values, int maxLength) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String text = String.join("\n", values);
        return truncate(text, maxLength);
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("\\R"))
                .filter(item -> item != null && !item.isBlank())
                .toList();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
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

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static void setNullableTimestamp(PreparedStatement statement, int index, Instant value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.from(value));
        }
    }

    private static Instant getInstant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}

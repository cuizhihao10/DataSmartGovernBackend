/**
 * @Author : Cui
 * @Date: 2026/08/19 00:00
 * @Description DataSmart Govern Backend - JdbcAgentCommandTaskFinalStateCallbackJobRecordMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

/**
 * 最终态 callback job/history 与 PostgreSQL 行之间的低敏字段映射器。
 *
 * <p>Mapper 不负责领取、退避或 HTTP callback，只负责列清单、参数绑定和 ResultSet 还原。这样 Store 可以把
 * 条件 UPDATE + history INSERT 放到同一 JDBC 事务，而字段长度、NULL 处理和状态恢复仍集中在一个可审计位置。</p>
 */
final class JdbcAgentCommandTaskFinalStateCallbackJobRecordMapper {

    /** job 查询统一字段，所有 SELECT/UPDATE RETURNING 复用，防止持久化字段漂移。 */
    static final String JOB_SELECT_COLUMNS = """
            job_id, source_receipt_identity_key, source_replay_sequence, command_id,
            task_id, task_run_id, executor_id, audit_id,
            tenant_id, project_id, actor_id, run_id, session_id, tool_code,
            callback_status, callback_idempotency_key, requires_manual_compensation,
            status, attempt_count, next_attempt_at, lease_owner, lease_token, lease_expires_at,
            failure_code, callback_delivered_at, create_time, update_time
            """;

    /** history 查询统一字段。 */
    static final String HISTORY_SELECT_COLUMNS = """
            history_id, job_id, source_receipt_identity_key, event_type, status,
            reason_code, attempt_count, worker_id, occurred_at
            """;

    /** 插入新 job 的 SQL；source receipt identity 唯一约束提供持久幂等。 */
    static final String INSERT_JOB_SQL = """
            INSERT INTO agent_command_task_final_callback_job (
                job_id, source_receipt_identity_key, source_replay_sequence, command_id,
                task_id, task_run_id, executor_id, audit_id,
                tenant_id, project_id, actor_id, run_id, session_id, tool_code,
                callback_status, callback_idempotency_key, requires_manual_compensation,
                status, attempt_count, next_attempt_at, lease_owner, lease_token, lease_expires_at,
                failure_code, callback_delivered_at, create_time, update_time
            ) VALUES (
                ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?,
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?
            )
            ON CONFLICT (source_receipt_identity_key) DO NOTHING
            """;

    /** 插入 immutable history 事件的 SQL。 */
    static final String INSERT_HISTORY_SQL = """
            INSERT INTO agent_command_task_final_callback_history (
                history_id, job_id, source_receipt_identity_key, event_type, status,
                reason_code, attempt_count, worker_id, occurred_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private JdbcAgentCommandTaskFinalStateCallbackJobRecordMapper() {
    }

    /**
     * 绑定新 job 的插入参数，并在字段层做长度保护；敏感内容已由上游模型拒绝，不在此处做内容推断。
     */
    static void bindJobInsert(PreparedStatement statement,
                              AgentCommandTaskFinalStateCallbackJob job) throws SQLException {
        int index = 1;
        setString(statement, index++, job.jobId(), 96);
        setString(statement, index++, job.sourceReceiptIdentityKey(), 240);
        setLong(statement, index++, job.sourceReplaySequence());
        setString(statement, index++, job.commandId(), 180);
        setLong(statement, index++, job.taskId());
        setLong(statement, index++, job.taskRunId());
        setString(statement, index++, job.executorId(), 160);
        setString(statement, index++, job.auditId(), 200);
        setString(statement, index++, job.tenantId(), 80);
        setString(statement, index++, job.projectId(), 80);
        setString(statement, index++, job.actorId(), 120);
        setString(statement, index++, job.runId(), 180);
        setString(statement, index++, job.sessionId(), 180);
        setString(statement, index++, job.toolCode(), 180);
        setString(statement, index++, job.callbackStatus(), 32);
        setString(statement, index++, job.callbackIdempotencyKey(), 255);
        statement.setBoolean(index++, job.requiresManualCompensation());
        setString(statement, index++, job.status().name(), 40);
        statement.setInt(index++, job.attemptCount());
        setInstant(statement, index++, job.nextAttemptAt());
        setString(statement, index++, job.leaseOwner(), 160);
        setString(statement, index++, job.leaseToken(), 128);
        setInstant(statement, index++, job.leaseExpiresAt());
        setString(statement, index++, job.failureCode(), 160);
        setInstant(statement, index++, job.callbackDeliveredAt());
        setInstant(statement, index++, job.createdAt());
        setInstant(statement, index, job.updatedAt());
    }

    /**
     * 绑定 history 插入参数；reasonCode 只允许低敏机器码，完整错误仍由日志系统处理。
     */
    static void bindHistoryInsert(PreparedStatement statement,
                                  AgentCommandTaskFinalStateCallbackHistoryRecord history) throws SQLException {
        int index = 1;
        setString(statement, index++, history.historyId(), 96);
        setString(statement, index++, history.jobId(), 96);
        setString(statement, index++, history.sourceReceiptIdentityKey(), 240);
        setString(statement, index++, history.eventType(), 64);
        setString(statement, index++, history.status() == null ? null : history.status().name(), 40);
        setString(statement, index++, history.reasonCode(), 160);
        statement.setInt(index++, Math.max(0, history.attemptCount()));
        setString(statement, index++, history.workerId(), 160);
        setInstant(statement, index, history.occurredAt());
    }

    /**
     * 从 ResultSet 还原 job 当前状态；非法状态回退为 PENDING，避免历史脏值导致 worker 崩溃。
     */
    static AgentCommandTaskFinalStateCallbackJob toJob(ResultSet resultSet) throws SQLException {
        return new AgentCommandTaskFinalStateCallbackJob(
                resultSet.getString("job_id"),
                resultSet.getString("source_receipt_identity_key"),
                nullableLong(resultSet, "source_replay_sequence"),
                resultSet.getString("command_id"),
                nullableLong(resultSet, "task_id"),
                nullableLong(resultSet, "task_run_id"),
                resultSet.getString("executor_id"),
                resultSet.getString("audit_id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("project_id"),
                resultSet.getString("actor_id"),
                resultSet.getString("run_id"),
                resultSet.getString("session_id"),
                resultSet.getString("tool_code"),
                resultSet.getString("callback_status"),
                resultSet.getString("callback_idempotency_key"),
                resultSet.getBoolean("requires_manual_compensation"),
                parseStatus(resultSet.getString("status")),
                resultSet.getInt("attempt_count"),
                instant(resultSet, "next_attempt_at"),
                resultSet.getString("lease_owner"),
                resultSet.getString("lease_token"),
                instant(resultSet, "lease_expires_at"),
                resultSet.getString("failure_code"),
                instant(resultSet, "callback_delivered_at"),
                instant(resultSet, "create_time"),
                instant(resultSet, "update_time")
        );
    }

    /**
     * 从 ResultSet 还原一条低敏 history 事件。
     */
    static AgentCommandTaskFinalStateCallbackHistoryRecord toHistory(ResultSet resultSet) throws SQLException {
        return new AgentCommandTaskFinalStateCallbackHistoryRecord(
                resultSet.getString("history_id"),
                resultSet.getString("job_id"),
                resultSet.getString("source_receipt_identity_key"),
                resultSet.getString("event_type"),
                parseStatus(resultSet.getString("status")),
                resultSet.getString("reason_code"),
                resultSet.getInt("attempt_count"),
                resultSet.getString("worker_id"),
                instant(resultSet, "occurred_at")
        );
    }

    /**
     * 绑定动态 SQL 参数，供 Store 的条件领取和状态更新复用。
     */
    static void bindParameters(PreparedStatement statement, List<?> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object parameter = parameters.get(index);
            int jdbcIndex = index + 1;
            if (parameter == null) {
                statement.setNull(jdbcIndex, Types.NULL);
            } else if (parameter instanceof Long value) {
                statement.setLong(jdbcIndex, value);
            } else if (parameter instanceof Integer value) {
                statement.setInt(jdbcIndex, value);
            } else if (parameter instanceof Boolean value) {
                statement.setBoolean(jdbcIndex, value);
            } else if (parameter instanceof Instant value) {
                statement.setTimestamp(jdbcIndex, Timestamp.from(value));
            } else {
                statement.setString(jdbcIndex, parameter.toString());
            }
        }
    }

    /**
     * 解析存储状态；未知值 fail-safe 回退为 PENDING，随后 worker 会按事实重新验证而不会直接下游写入。
     */
    private static AgentCommandTaskFinalStateCallbackJobStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return AgentCommandTaskFinalStateCallbackJobStatus.PENDING;
        }
        try {
            return AgentCommandTaskFinalStateCallbackJobStatus.valueOf(value.trim());
        } catch (IllegalArgumentException ignored) {
            return AgentCommandTaskFinalStateCallbackJobStatus.PENDING;
        }
    }

    /**
     * 设置允许为空的短文本并裁剪到数据库列长度。
     */
    private static void setString(PreparedStatement statement, int index, String value, int maxLength) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value.length() <= maxLength ? value : value.substring(0, maxLength));
        }
    }

    /**
     * 设置允许为空的 BIGINT。
     */
    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    /**
     * 设置允许为空的时间戳。
     */
    private static void setInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.from(value));
        }
    }

    /**
     * 读取允许为空的 BIGINT。
     */
    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    /**
     * 读取允许为空的时间戳。
     */
    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}

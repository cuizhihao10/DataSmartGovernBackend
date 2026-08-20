/**
 * @Author : Cui
 * @Date: 2026/08/19 00:00
 * @Description DataSmart Govern Backend - JdbcAgentCommandTaskFinalStateCallbackJobStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcSqlExceptionSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL/JDBC 版最终态 callback durable job 仓储。
 *
 * <p>生产 worker 只使用本实现。它的 source receipt anti-join 只读取 Java 已物化的
 * {@code agent_tool_action_worker_receipt_index}，并且只接受四种真实终态 outcome。PUBLISHED 或
 * AUTO_APPROVED 不在 SQL 白名单内，即使它们恰好存在于历史 receipt，也不会生成 callback job。</p>
 *
 * <p>所有状态转换都以 {@code job_id + status + lease_owner + lease_token} 做条件 UPDATE，并在同一个
 * JDBC 事务内插入 history。这样多实例同时扫描时最多一个实例可以领取/完成同一 job；崩溃留下的
 * DISPATCHING 行会在 lease_expires_at 到期后重新可见。</p>
 */
@Component
@ConditionalOnExpression(
        "'${datasmart.agent-runtime.async-task-final-state-callback-worker.enabled:false}'.equalsIgnoreCase('true') "
                + "&& '${datasmart.agent-runtime.persistence.database-enabled:false}'.equalsIgnoreCase('true') "
                + "&& T(com.czh.datasmart.govern.agent.config.AgentRuntimeStoreMode).isPostgresqlDurable("
                + "'${datasmart.agent-runtime.tool-action-resume-facts.worker-receipt-index-store:memory}')"
)
public class JdbcAgentCommandTaskFinalStateCallbackJobStore
        implements AgentCommandTaskFinalStateCallbackJobStore {

    private static final String RECEIPT_SELECT_COLUMNS = """
            r.event_identity_key, r.command_id, r.task_id, r.task_run_id, r.executor_id, r.audit_id,
            r.tenant_id, r.project_id, r.actor_id,
            r.run_id, r.session_id, r.tool_code, r.task_status, r.outcome,
            r.pre_check_passed, r.side_effect_executed, r.error_code, r.id AS replay_sequence,
            r.consumed_at, r.indexed_at
            """;

    private final AgentRuntimeJdbcConnectionManager connectionManager;

    /**
     * 注入统一 JDBC 连接管理器，让 job 状态和 history 可以在同一事务里提交。
     */
    public JdbcAgentCommandTaskFinalStateCallbackJobStore(AgentRuntimeJdbcConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    /**
     * 从 Java receipt index 查询尚未创建 job 的真实终态候选。
     */
    @Override
    public List<AgentToolActionWorkerReceiptIndexRecord> listUnregisteredTerminalReceiptCandidates(int limit) {
        String sql = "SELECT " + RECEIPT_SELECT_COLUMNS
                + " FROM agent_tool_action_worker_receipt_index r"
                + " LEFT JOIN agent_command_task_final_callback_job j"
                + " ON j.source_receipt_identity_key = r.event_identity_key"
                + " WHERE j.source_receipt_identity_key IS NULL"
                + " AND r.outcome IN (?, ?, ?, ?)"
                /*
                 * r.id 是数据库分配的跨重启、多实例稳定顺序；不能使用会在进程内重新计数的
                 * r.replay_sequence，否则旧失败 receipt 可能在重启后错误覆盖新成功事实。
                 */
                + " ORDER BY r.id, r.consumed_at LIMIT ?";
        List<Object> parameters = List.of(
                "EXECUTION_SUCCEEDED", "EXECUTION_FAILED", "COMPENSATION_REQUIRED", "FAILED_PRECHECK",
                normalizeLimit(limit));
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    JdbcAgentCommandTaskFinalStateCallbackJobRecordMapper.bindParameters(statement, parameters);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        List<AgentToolActionWorkerReceiptIndexRecord> receipts = new ArrayList<>();
                        while (resultSet.next()) {
                            receipts.add(JdbcAgentToolActionWorkerReceiptIndexRecordMapper.toRecord(resultSet));
                        }
                        return List.copyOf(receipts);
                    }
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("查询最终态 callback Java receipt 候选失败", exception);
        }
    }

    /**
     * 用 source receipt 唯一键创建 job，并将 DISCOVERED 历史和 INSERT 放在同一事务。
     */
    @Override
    public boolean append(AgentCommandTaskFinalStateCallbackJob job,
                          String eventType,
                          String reasonCode,
                          Instant now) {
        if (job == null) {
            return false;
        }
        try {
            return connectionManager.executeInTransaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        JdbcAgentCommandTaskFinalStateCallbackJobRecordMapper.INSERT_JOB_SQL)) {
                    JdbcAgentCommandTaskFinalStateCallbackJobRecordMapper.bindJobInsert(statement, job);
                    if (statement.executeUpdate() != 1) {
                        return false;
                    }
                }
                appendHistory(connection, job, eventType, reasonCode, null, now);
                return true;
            });
        } catch (RuntimeException exception) {
            /*
             * PostgreSQL 的 ON CONFLICT DO NOTHING 通常通过 executeUpdate()==0 表示重复；兼容驱动或迁移期
             * 数据库也可能直接抛 SQLState=23505。两种表现的业务含义相同：另一个实例已经为同一 receipt
             * 建立 job，本次发现不应让 scheduler 失败或重复触发 callback。
             */
            if (AgentRuntimeJdbcSqlExceptionSupport.isDuplicateKey(exception)) {
                return false;
            }
            throw new IllegalStateException("写入最终态 callback durable job 失败，jobId=" + job.jobId(), exception);
        }
    }

    /**
     * 先读取少量可能到期的 job ID，再逐条条件更新领取；真正的并发裁决发生在 UPDATE WHERE 中。
     */
    @Override
    public List<AgentCommandTaskFinalStateCallbackJob> claimDue(String workerId,
                                                                String leaseToken,
                                                                Instant now,
                                                                Instant leaseExpiresAt,
                                                                int limit) {
        try {
            return connectionManager.executeInTransaction(connection -> {
                List<String> candidateIds = selectDueJobIds(connection, now, normalizeLimit(limit));
                List<AgentCommandTaskFinalStateCallbackJob> claimed = new ArrayList<>();
                for (String jobId : candidateIds) {
                    claimOne(connection, jobId, workerId, leaseToken, now, leaseExpiresAt)
                            .ifPresent(claimed::add);
                }
                return List.copyOf(claimed);
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("领取最终态 callback durable job 失败", exception);
        }
    }

    /**
     * 续期当前 job 的 visibility lease，并在成功后留下 heartbeat 历史。
     */
    @Override
    public boolean heartbeat(String jobId,
                             String workerId,
                             String leaseToken,
                             Instant leaseExpiresAt,
                             Instant now) {
        String sql = "UPDATE agent_command_task_final_callback_job "
                + "SET lease_expires_at = ?, update_time = ? "
                + "WHERE job_id = ? AND status = 'DISPATCHING' AND lease_owner = ? AND lease_token = ? "
                + "AND lease_expires_at > ?";
        return updateHeldJob(sql, parameters(leaseExpiresAt, now, jobId, workerId, leaseToken, now), jobId, workerId,
                "CALLBACK_HEARTBEAT", null, now);
    }

    /**
     * 标记 task-management 已接受 callback；失败终态仍可在送达后进入人工补偿状态。
     */
    @Override
    public boolean markDelivered(String jobId,
                                 String workerId,
                                 String leaseToken,
                                 boolean requiresManualCompensation,
                                 Instant now) {
        AgentCommandTaskFinalStateCallbackJobStatus status = requiresManualCompensation
                ? AgentCommandTaskFinalStateCallbackJobStatus.COMPENSATION_REQUIRED
                : AgentCommandTaskFinalStateCallbackJobStatus.DELIVERED;
        String failureCode = requiresManualCompensation ? "POST_CALLBACK_MANUAL_COMPENSATION_REQUIRED" : null;
        String eventType = requiresManualCompensation
                ? "CALLBACK_DELIVERED_COMPENSATION_REQUIRED" : "CALLBACK_DELIVERED";
        String sql = "UPDATE agent_command_task_final_callback_job "
                + "SET status = ?, next_attempt_at = NULL, lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL, "
                + "failure_code = ?, callback_delivered_at = ?, update_time = ? "
                + "WHERE job_id = ? AND status = 'DISPATCHING' AND lease_owner = ? AND lease_token = ? "
                + "AND lease_expires_at > ?";
        return updateHeldJob(sql, parameters(status.name(), failureCode, now, now, jobId, workerId, leaseToken, now),
                jobId, workerId, eventType, failureCode, now);
    }

    /**
     * 标记被更高 Java replay sequence 覆盖的旧 job，停止其自动副作用。
     */
    @Override
    public boolean markSuperseded(String jobId,
                                  String workerId,
                                  String leaseToken,
                                  String reasonCode,
                                  Instant now) {
        return finishWithoutDelivery(jobId, workerId, leaseToken,
                AgentCommandTaskFinalStateCallbackJobStatus.SUPERSEDED,
                "CALLBACK_SUPERSEDED", reasonCode, now);
    }

    /**
     * 将可恢复下游故障写为 RETRY_WAIT，并清除本次 lease 以便到期后重试。
     */
    @Override
    public boolean markRetry(String jobId,
                             String workerId,
                             String leaseToken,
                             String failureCode,
                             Instant nextAttemptAt,
                             Instant now) {
        String sql = "UPDATE agent_command_task_final_callback_job "
                + "SET status = 'RETRY_WAIT', next_attempt_at = ?, lease_owner = NULL, lease_token = NULL, "
                + "lease_expires_at = NULL, failure_code = ?, update_time = ? "
                + "WHERE job_id = ? AND status = 'DISPATCHING' AND lease_owner = ? AND lease_token = ? "
                + "AND lease_expires_at > ?";
        return updateHeldJob(sql, parameters(nextAttemptAt, failureCode, now, jobId, workerId, leaseToken, now), jobId,
                workerId, "CALLBACK_RETRY_SCHEDULED", failureCode, now);
    }

    /**
     * 将不能安全自动收敛的 job 固定为人工补偿待办。
     */
    @Override
    public boolean markCompensationRequired(String jobId,
                                            String workerId,
                                            String leaseToken,
                                            String reasonCode,
                                            boolean callbackDelivered,
                                            Instant now) {
        String sql = "UPDATE agent_command_task_final_callback_job "
                + "SET status = 'COMPENSATION_REQUIRED', next_attempt_at = NULL, lease_owner = NULL, lease_token = NULL, "
                + "lease_expires_at = NULL, failure_code = ?, callback_delivered_at = ?, update_time = ? "
                + "WHERE job_id = ? AND status = 'DISPATCHING' AND lease_owner = ? AND lease_token = ? "
                + "AND lease_expires_at > ?";
        return updateHeldJob(sql, parameters(reasonCode, callbackDelivered ? now : null, now, jobId, workerId, leaseToken, now),
                jobId, workerId, "CALLBACK_COMPENSATION_REQUIRED", reasonCode, now);
    }

    /**
     * 将耗尽最大尝试次数的 job 放入死信，停止无人值守重试。
     */
    @Override
    public boolean markDeadLetter(String jobId,
                                  String workerId,
                                  String leaseToken,
                                  String reasonCode,
                                  Instant now) {
        return finishWithoutDelivery(jobId, workerId, leaseToken,
                AgentCommandTaskFinalStateCallbackJobStatus.DEAD_LETTER,
                "CALLBACK_DEAD_LETTERED", reasonCode, now);
    }

    /**
     * 按 source receipt identity 查询当前 job。
     */
    @Override
    public Optional<AgentCommandTaskFinalStateCallbackJob> findBySourceReceiptIdentityKey(String sourceReceiptIdentityKey) {
        if (!hasText(sourceReceiptIdentityKey)) {
            return Optional.empty();
        }
        String sql = "SELECT " + JdbcAgentCommandTaskFinalStateCallbackJobRecordMapper.JOB_SELECT_COLUMNS
                + " FROM agent_command_task_final_callback_job WHERE source_receipt_identity_key = ?";
        try {
            return connectionManager.executeWithConnection(connection -> queryJob(connection, sql,
                    List.of(sourceReceiptIdentityKey)));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("查询最终态 callback job 失败", exception);
        }
    }

    /**
     * 查询同源 receipt 的 immutable history，按发生顺序返回。
     */
    @Override
    public List<AgentCommandTaskFinalStateCallbackHistoryRecord> historyFor(String sourceReceiptIdentityKey) {
        if (!hasText(sourceReceiptIdentityKey)) {
            return List.of();
        }
        String sql = "SELECT " + JdbcAgentCommandTaskFinalStateCallbackJobRecordMapper.HISTORY_SELECT_COLUMNS
                + " FROM agent_command_task_final_callback_history WHERE source_receipt_identity_key = ?"
                + " ORDER BY occurred_at, id";
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    JdbcAgentCommandTaskFinalStateCallbackJobRecordMapper.bindParameters(statement,
                            List.of(sourceReceiptIdentityKey));
                    try (ResultSet resultSet = statement.executeQuery()) {
                        List<AgentCommandTaskFinalStateCallbackHistoryRecord> histories = new ArrayList<>();
                        while (resultSet.next()) {
                            histories.add(JdbcAgentCommandTaskFinalStateCallbackJobRecordMapper.toHistory(resultSet));
                        }
                        return List.copyOf(histories);
                    }
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("查询最终态 callback job 历史失败", exception);
        }
    }

    /**
     * 查询当前到期候选 ID。读取不加锁无妨，因为真正领取紧随其后的条件 UPDATE 会裁决并发。
     */
    private List<String> selectDueJobIds(Connection connection, Instant now, int limit) throws SQLException {
        String sql = "SELECT job_id FROM agent_command_task_final_callback_job WHERE "
                + "(status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= ?)) "
                + "OR (status = 'RETRY_WAIT' AND next_attempt_at <= ?) "
                + "OR (status = 'DISPATCHING' AND lease_expires_at <= ?) "
                + "ORDER BY id LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcAgentCommandTaskFinalStateCallbackJobRecordMapper.bindParameters(statement,
                    List.of(now, now, now, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (resultSet.next()) {
                    ids.add(resultSet.getString("job_id"));
                }
                return ids;
            }
        }
    }

    /**
     * 通过条件 UPDATE 原子领取单条 job，并在同一事务写入 CLAIMED/STALENESS history。
     */
    private Optional<AgentCommandTaskFinalStateCallbackJob> claimOne(Connection connection,
                                                                      String jobId,
                                                                      String workerId,
                                                                      String leaseToken,
                                                                      Instant now,
                                                                      Instant leaseExpiresAt) throws SQLException {
        String sql = "UPDATE agent_command_task_final_callback_job "
                + "SET status = 'DISPATCHING', attempt_count = attempt_count + 1, next_attempt_at = NULL, "
                + "lease_owner = ?, lease_token = ?, lease_expires_at = ?, failure_code = NULL, update_time = ? "
                + "WHERE job_id = ? AND ((status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= ?)) "
                + "OR (status = 'RETRY_WAIT' AND next_attempt_at <= ?) "
                + "OR (status = 'DISPATCHING' AND lease_expires_at <= ?))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcAgentCommandTaskFinalStateCallbackJobRecordMapper.bindParameters(statement,
                    List.of(workerId, leaseToken, leaseExpiresAt, now, jobId, now, now, now));
            if (statement.executeUpdate() != 1) {
                return Optional.empty();
            }
        }
        AgentCommandTaskFinalStateCallbackJob job = selectJobById(connection, jobId).orElse(null);
        if (job == null) {
            return Optional.empty();
        }
        appendHistory(connection, job, "CALLBACK_CLAIMED", null, workerId, now);
        return Optional.of(job);
    }

    /**
     * 复用 held-job 条件更新，成功后再查询实际状态并写一条与状态严格一致的 history。
     */
    private boolean updateHeldJob(String sql,
                                  List<?> parameters,
                                  String jobId,
                                  String workerId,
                                  String eventType,
                                  String reasonCode,
                                  Instant now) {
        try {
            return connectionManager.executeInTransaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    JdbcAgentCommandTaskFinalStateCallbackJobRecordMapper.bindParameters(statement, parameters);
                    if (statement.executeUpdate() != 1) {
                        return false;
                    }
                }
                AgentCommandTaskFinalStateCallbackJob job = selectJobById(connection, jobId).orElse(null);
                if (job == null) {
                    throw new IllegalStateException("最终态 callback job 条件更新成功后无法回读 jobId=" + jobId);
                }
                appendHistory(connection, job, eventType, reasonCode, workerId, now);
                return true;
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("更新最终态 callback durable job 失败，jobId=" + jobId, exception);
        }
    }

    /**
     * 统一处理 SUPERSEDED/DEAD_LETTER 这类未向下游确认成功的终止状态。
     */
    private boolean finishWithoutDelivery(String jobId,
                                          String workerId,
                                          String leaseToken,
                                          AgentCommandTaskFinalStateCallbackJobStatus status,
                                          String eventType,
                                          String reasonCode,
                                          Instant now) {
        String sql = "UPDATE agent_command_task_final_callback_job "
                + "SET status = ?, next_attempt_at = NULL, lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL, "
                + "failure_code = ?, update_time = ? "
                + "WHERE job_id = ? AND status = 'DISPATCHING' AND lease_owner = ? AND lease_token = ? "
                + "AND lease_expires_at > ?";
        return updateHeldJob(sql, parameters(status.name(), reasonCode, now, jobId, workerId, leaseToken, now), jobId,
                workerId, eventType, reasonCode, now);
    }

    /**
     * 在当前连接中按主键回读 job，避免事务中重新打开连接造成读取未提交状态不可见。
     */
    private Optional<AgentCommandTaskFinalStateCallbackJob> selectJobById(Connection connection, String jobId)
            throws SQLException {
        String sql = "SELECT " + JdbcAgentCommandTaskFinalStateCallbackJobRecordMapper.JOB_SELECT_COLUMNS
                + " FROM agent_command_task_final_callback_job WHERE job_id = ?";
        return queryJob(connection, sql, List.of(jobId));
    }

    /**
     * 执行单行 job 查询。
     */
    private Optional<AgentCommandTaskFinalStateCallbackJob> queryJob(Connection connection,
                                                                       String sql,
                                                                       List<?> parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcAgentCommandTaskFinalStateCallbackJobRecordMapper.bindParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(JdbcAgentCommandTaskFinalStateCallbackJobRecordMapper.toJob(resultSet))
                        : Optional.empty();
            }
        }
    }

    /**
     * 为某次状态变更插入 immutable history；调用方已处于同一事务，因此 job 与 history 不会半提交。
     */
    private void appendHistory(Connection connection,
                               AgentCommandTaskFinalStateCallbackJob job,
                               String eventType,
                               String reasonCode,
                               String workerId,
                               Instant now) throws SQLException {
        AgentCommandTaskFinalStateCallbackHistoryRecord history = new AgentCommandTaskFinalStateCallbackHistoryRecord(
                "callback-history:" + UUID.randomUUID(),
                job.jobId(),
                job.sourceReceiptIdentityKey(),
                eventType == null ? "CALLBACK_STATE_CHANGED" : eventType,
                job.status(),
                reasonCode,
                job.attemptCount(),
                workerId,
                now == null ? Instant.now() : now
        );
        try (PreparedStatement statement = connection.prepareStatement(
                JdbcAgentCommandTaskFinalStateCallbackJobRecordMapper.INSERT_HISTORY_SQL)) {
            JdbcAgentCommandTaskFinalStateCallbackJobRecordMapper.bindHistoryInsert(statement, history);
            statement.executeUpdate();
        }
    }

    /**
     * 对批次大小设置数据库层硬上限，防止错误配置导致一次锁住大量 job。
     */
    private int normalizeLimit(int limit) {
        return Math.max(1, Math.min(limit, 500));
    }

    /**
     * 判断 source receipt identity 是否可作为查询键。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 构造允许包含 null 的 JDBC 参数列表；{@link List#of(Object[])} 会拒绝 null，不能用于可选失败码和时间戳。
     */
    private List<Object> parameters(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }
}

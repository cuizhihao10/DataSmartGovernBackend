/**
 * @Author : Cui
 * @Date: 2026/06/24 23:42
 * @Description DataSmart Govern Backend - JdbcAgentAsyncTaskCommandOutboxOperationStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MySQL 版 command outbox 人工补偿 Store。
 *
 * <p>该类有意从 {@link JdbcAgentAsyncTaskCommandOutboxStore} 拆出来。主 Store 已经负责 append、dispatcher 领取、
 * 投递成功、失败退避、阻断和 stale 恢复；如果继续把 dead-letter、requeue、ignore、note 都塞进去，
 * 单文件会变成难以维护的“大 Impl”。拆出操作 Store 后，自动投递状态机与人工补偿状态机各自清晰。</p>
 *
 * <p>安全边界：所有方法只更新 status、next_retry_at、last_error 和 update_time，不读取 payload_json。
 * 更新后回读也只由 mapper 还原领域记录，Controller 最终只返回低敏 view，不会把 payload 正文暴露给管理台。</p>
 */
@Component
@ConditionalOnExpression(
        "'${datasmart.agent-runtime.async-task-commands.outbox.store:memory}'.equalsIgnoreCase('mysql') "
                + "&& '${datasmart.agent-runtime.persistence.database-enabled:false}'.equalsIgnoreCase('true')"
)
public class JdbcAgentAsyncTaskCommandOutboxOperationStore implements AgentAsyncTaskCommandOutboxOperationStore {

    private final AgentRuntimeJdbcConnectionManager connectionManager;

    public JdbcAgentAsyncTaskCommandOutboxOperationStore(AgentRuntimeJdbcConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    /**
     * 重新入队：FAILED/BLOCKED/DEAD_LETTER -> PENDING。
     *
     * <p>next_retry_at 允许为空或未来时间。为空表示立即可被 dispatcher 领取；未来时间适合灰度恢复、
     * 等待下游配置生效或避免大量死信同时恢复造成冲击。</p>
     */
    @Override
    public Optional<AgentAsyncTaskCommandOutboxRecord> markRequeued(String outboxId,
                                                                    String reason,
                                                                    Instant now,
                                                                    Instant nextRetryAt) {
        String sql = "UPDATE agent_async_task_command_outbox SET status = ?, next_retry_at = ?, "
                + "published_at = NULL, last_error = ?, update_time = ? "
                + "WHERE outbox_id = ? AND status IN (?, ?, ?)";
        return updateThenFind(outboxId, sql, parameters(
                AgentAsyncTaskCommandOutboxStatus.PENDING.name(),
                nextRetryAt,
                truncate(reason),
                now,
                outboxId,
                AgentAsyncTaskCommandOutboxStatus.FAILED.name(),
                AgentAsyncTaskCommandOutboxStatus.BLOCKED.name(),
                AgentAsyncTaskCommandOutboxStatus.DEAD_LETTER.name()
        ));
    }

    /**
     * 转入死信：FAILED/BLOCKED -> DEAD_LETTER。
     */
    @Override
    public Optional<AgentAsyncTaskCommandOutboxRecord> markDeadLetter(String outboxId, String reason, Instant now) {
        String sql = "UPDATE agent_async_task_command_outbox SET status = ?, next_retry_at = NULL, "
                + "last_error = ?, update_time = ? WHERE outbox_id = ? AND status IN (?, ?)";
        return updateThenFind(outboxId, sql, parameters(
                AgentAsyncTaskCommandOutboxStatus.DEAD_LETTER.name(),
                truncate(reason),
                now,
                outboxId,
                AgentAsyncTaskCommandOutboxStatus.FAILED.name(),
                AgentAsyncTaskCommandOutboxStatus.BLOCKED.name()
        ));
    }

    /**
     * 人工忽略：FAILED/BLOCKED/DEAD_LETTER -> IGNORED。
     */
    @Override
    public Optional<AgentAsyncTaskCommandOutboxRecord> markIgnored(String outboxId, String reason, Instant now) {
        String sql = "UPDATE agent_async_task_command_outbox SET status = ?, next_retry_at = NULL, "
                + "last_error = ?, update_time = ? WHERE outbox_id = ? AND status IN (?, ?, ?)";
        return updateThenFind(outboxId, sql, parameters(
                AgentAsyncTaskCommandOutboxStatus.IGNORED.name(),
                truncate(reason),
                now,
                outboxId,
                AgentAsyncTaskCommandOutboxStatus.FAILED.name(),
                AgentAsyncTaskCommandOutboxStatus.BLOCKED.name(),
                AgentAsyncTaskCommandOutboxStatus.DEAD_LETTER.name()
        ));
    }

    /**
     * 追加人工备注。
     */
    @Override
    public Optional<AgentAsyncTaskCommandOutboxRecord> appendOperationNote(String outboxId,
                                                                           String reason,
                                                                           Instant now) {
        String sql = "UPDATE agent_async_task_command_outbox SET last_error = ?, update_time = ? "
                + "WHERE outbox_id = ? AND status <> ?";
        return updateThenFind(outboxId, sql, parameters(
                truncate(reason),
                now,
                outboxId,
                AgentAsyncTaskCommandOutboxStatus.PUBLISHED.name()
        ));
    }

    private Optional<AgentAsyncTaskCommandOutboxRecord> updateThenFind(String outboxId,
                                                                       String sql,
                                                                       List<Object> parameters) {
        if (!hasText(outboxId)) {
            return Optional.empty();
        }
        try {
            return connectionManager.executeWithConnection(connection -> updateThenFind(connection, outboxId, sql, parameters));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("执行 Agent 异步命令 outbox 人工补偿失败，outboxId=" + outboxId, exception);
        }
    }

    private Optional<AgentAsyncTaskCommandOutboxRecord> updateThenFind(Connection connection,
                                                                       String outboxId,
                                                                       String sql,
                                                                       List<Object> parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcAgentAsyncTaskCommandOutboxRecordMapper.bindParameters(statement, parameters);
            int updatedRows = statement.executeUpdate();
            if (updatedRows == 0) {
                return Optional.empty();
            }
        }
        return findByOutboxId(connection, outboxId);
    }

    private Optional<AgentAsyncTaskCommandOutboxRecord> findByOutboxId(Connection connection, String outboxId)
            throws SQLException {
        String sql = "SELECT " + JdbcAgentAsyncTaskCommandOutboxRecordMapper.SELECT_COLUMNS
                + " FROM agent_async_task_command_outbox WHERE outbox_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcAgentAsyncTaskCommandOutboxRecordMapper.bindParameters(statement, List.of(outboxId));
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(JdbcAgentAsyncTaskCommandOutboxRecordMapper.toRecord(resultSet));
            }
        }
    }

    private List<Object> parameters(Object... values) {
        List<Object> result = new ArrayList<>();
        for (Object value : values) {
            result.add(value);
        }
        return result;
    }

    private String truncate(String reason) {
        return JdbcAgentAsyncTaskCommandOutboxRecordMapper.truncate(reason, 1024);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

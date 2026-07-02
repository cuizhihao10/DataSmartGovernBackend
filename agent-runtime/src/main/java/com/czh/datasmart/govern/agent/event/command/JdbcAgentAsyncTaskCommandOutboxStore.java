/**
 * @Author : Cui
 * @Date: 2026/05/31 18:00
 * @Description DataSmart Govern Backend - JdbcAgentAsyncTaskCommandOutboxStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import com.czh.datasmart.govern.agent.config.AgentAsyncTaskCommandOutboxProperties;
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
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 JDBC/MySQL 的 Agent ASYNC_TASK 命令 outbox 仓储。
 *
 * <p>该 Store 解决的是 Agent 工具“异步任务化”链路里的生产可靠性问题：
 * 当模型规划出一个 {@code ASYNC_TASK} 工具后，agent-runtime 不能直接在请求线程里调用 task-management，
 * 也不能只把命令放在 JVM 内存里。真实商用环境需要在本地数据库先保存“某个 command 等待投递”这一事实，
 * 然后由 dispatcher 可靠投递到 Kafka 或内部 HTTP 通道。</p>
 *
 * <p>它只在 {@code datasmart.agent-runtime.async-task-commands.outbox.store=mysql} 且
 * {@code datasmart.agent-runtime.persistence.database-enabled=true} 时注册。默认 memory 模式仍保持轻量，
 * 避免本地学习、单测或没有 MySQL 的环境启动失败。</p>
 *
 * <p>状态流转通过条件 UPDATE 控制：PENDING/FAILED 可被领取为 PUBLISHING，发送成功后变为 PUBLISHED，
 * 失败后变为 FAILED，超过重试或契约错误时进入 BLOCKED。当前用 status + update_time 作为最小租约语义，
 * 后续多实例高并发投递时应继续扩展 workerId、lockedAt、lockExpireAt、分片扫描和死信 topic。</p>
 */
@Component
@ConditionalOnExpression(
        "T(com.czh.datasmart.govern.agent.config.AgentRuntimeStoreMode)"
                + ".isJdbcDurable('${datasmart.agent-runtime.async-task-commands.outbox.store:memory}') "
                + "&& '${datasmart.agent-runtime.persistence.database-enabled:false}'.equalsIgnoreCase('true')"
)
public class JdbcAgentAsyncTaskCommandOutboxStore implements AgentAsyncTaskCommandOutboxStore {

    private final AgentRuntimeJdbcConnectionManager connectionManager;
    private final boolean enabled;
    private final int maxCommandsPerRun;
    private final int maxTotalRecords;

    public JdbcAgentAsyncTaskCommandOutboxStore(AgentRuntimeJdbcConnectionManager connectionManager,
                                                AgentAsyncTaskCommandOutboxProperties properties) {
        this.connectionManager = connectionManager;
        this.enabled = properties.isEnabled();
        this.maxCommandsPerRun = Math.max(1, properties.getMaxCommandsPerRun());
        this.maxTotalRecords = Math.max(1, properties.getMaxTotalRecords());
    }

    /**
     * 追加一条待投递 command。
     *
     * <p>append 依赖 MySQL 唯一索引保证幂等：同一个 commandId 或 idempotencyKey 重复写入时返回 false，
     * 让上层回读已有记录，避免用户刷新、模型重试或网关重放制造多条下游任务。</p>
     */
    @Override
    public boolean append(AgentAsyncTaskCommandOutboxRecord record) {
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        JdbcAgentAsyncTaskCommandOutboxRecordMapper.INSERT_SQL)) {
                    JdbcAgentAsyncTaskCommandOutboxRecordMapper.bindRecord(statement, record);
                    statement.executeUpdate();
                    return true;
                }
            });
        } catch (RuntimeException exception) {
            if (AgentRuntimeJdbcSqlExceptionSupport.isDuplicateKey(exception)) {
                return false;
            }
            throw new IllegalStateException("写入 Agent 异步命令 outbox 到 JDBC/PostgreSQL 失败，outboxId=" + record.outboxId(), exception);
        }
    }

    /**
     * 按 outboxId 查询单条记录。
     * <p>outboxId 是运维和补偿侧最稳定的记录 ID。dispatcher 状态更新后也会通过该 ID 回读最新记录，
     * 便于把数据库实际状态返回给上层，而不是依赖内存中的旧对象。</p>
     */
    @Override
    public Optional<AgentAsyncTaskCommandOutboxRecord> findByOutboxId(String outboxId) {
        if (!hasText(outboxId)) {
            return Optional.empty();
        }
        try {
            return connectionManager.executeWithConnection(connection -> findByOutboxId(connection, outboxId));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("查询 Agent 异步命令 outbox 失败，outboxId=" + outboxId, exception);
        }
    }

    /**
     * 按 commandId 查询单条记录。
     *
     * <p>commandId 会进入 task-management Inbox 和 Kafka payload，是跨服务排查链路中最常用的业务 ID。
     * 重复入箱、重复消费、任务创建回执都应该能通过 commandId 串起来。</p>
     */
    @Override
    public Optional<AgentAsyncTaskCommandOutboxRecord> findByCommandId(String commandId) {
        if (!hasText(commandId)) {
            return Optional.empty();
        }
        String sql = "SELECT " + JdbcAgentAsyncTaskCommandOutboxRecordMapper.SELECT_COLUMNS
                + " FROM agent_async_task_command_outbox WHERE command_id = ?";
        try {
            return connectionManager.executeWithConnection(connection -> queryOne(connection, sql, List.of(commandId)));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("查询 Agent 异步命令 outbox 失败，commandId=" + commandId, exception);
        }
    }

    /**
     * 查询 outbox 列表。
     *
     * <p>当前查询面向诊断和 run 详情页，所以先支持 runId/status 两个高频过滤条件，并强制 limit 上限。
     * 后续做运营后台时应继续增加 tenantId、projectId、workspaceId、时间范围、commandType、toolCode 和分页游标，
     * 避免大租户历史记录堆积后列表接口退化为全表扫描。</p>
     */
    @Override
    public List<AgentAsyncTaskCommandOutboxRecord> list(String runId,
                                                        AgentAsyncTaskCommandOutboxStatus status,
                                                        int limit) {
        QueryPlan queryPlan = buildListQuery(runId, status, normalizeLimit(limit));
        return query(queryPlan, "查询 Agent 异步命令 outbox 列表失败，runId=" + runId + ", status=" + status);
    }

    @Override
    public long countByRunAndStatuses(String runId, Collection<AgentAsyncTaskCommandOutboxStatus> statuses) {
        if (!hasText(runId)) {
            return 0L;
        }
        return JdbcAgentAsyncTaskCommandOutboxCountSupport.countByScope(
                connectionManager,
                "run_id",
                runId,
                statuses,
                "统计 Agent 异步命令 outbox run 积压失败，runId=" + runId
        );
    }

    @Override
    public long countByTenantAndStatuses(Long tenantId, Collection<AgentAsyncTaskCommandOutboxStatus> statuses) {
        if (tenantId == null) {
            return 0L;
        }
        return JdbcAgentAsyncTaskCommandOutboxCountSupport.countByScope(
                connectionManager,
                "tenant_id",
                tenantId,
                statuses,
                "统计 Agent 异步命令 outbox 租户积压失败，tenantId=" + tenantId
        );
    }

    /**
     * 查询当前可被 dispatcher 领取的记录。
     *
     * <p>只有 PENDING 和到期的 FAILED 记录会被自动领取。BLOCKED 代表系统已经判断它不适合继续自动重试，
     * 需要人工修复配置、补齐权限、检查 payloadReference 或进入死信治理。</p>
     */
    @Override
    public List<AgentAsyncTaskCommandOutboxRecord> listPublishable(int limit, Instant now) {
        Instant referenceTime = now == null ? Instant.now() : now;
        String sql = "SELECT " + JdbcAgentAsyncTaskCommandOutboxRecordMapper.SELECT_COLUMNS
                + " FROM agent_async_task_command_outbox"
                + " WHERE status IN (?, ?)"
                + " AND (next_retry_at IS NULL OR next_retry_at <= ?)"
                + " ORDER BY id ASC LIMIT ?";
        QueryPlan queryPlan = new QueryPlan(sql, parameters(
                AgentAsyncTaskCommandOutboxStatus.PENDING.name(),
                AgentAsyncTaskCommandOutboxStatus.FAILED.name(),
                referenceTime,
                normalizeLimit(limit)
        ));
        return query(queryPlan, "查询可投递 Agent 异步命令 outbox 失败");
    }

    /**
     * 领取一条待投递记录。
     *
     * <p>这里用条件 UPDATE 完成轻量并发控制：只有状态仍是 PENDING/FAILED 且重试时间已到的记录才能被改成
     * PUBLISHING。多实例 dispatcher 同时抢同一条记录时，只有一个实例能更新成功，其他实例会拿到 Optional.empty()。
     * attempt_count 在领取时递增，即使 worker 在发送途中崩溃，也能看出这条 command 曾经被尝试过。</p>
     */
    @Override
    public Optional<AgentAsyncTaskCommandOutboxRecord> markPublishing(String outboxId, Instant now) {
        Instant referenceTime = now == null ? Instant.now() : now;
        String sql = "UPDATE agent_async_task_command_outbox SET status = ?, attempt_count = attempt_count + 1, "
                + "next_retry_at = NULL, update_time = ? "
                + "WHERE outbox_id = ? AND status IN (?, ?) AND (next_retry_at IS NULL OR next_retry_at <= ?)";
        return updateThenFind(outboxId, sql, parameters(
                AgentAsyncTaskCommandOutboxStatus.PUBLISHING.name(),
                referenceTime,
                outboxId,
                AgentAsyncTaskCommandOutboxStatus.PENDING.name(),
                AgentAsyncTaskCommandOutboxStatus.FAILED.name(),
                referenceTime
        ));
    }

    /**
     * 标记投递成功。
     *
     * <p>只有 PUBLISHING 记录允许变为 PUBLISHED。这样可以防止外部误调用把尚未领取的 PENDING 记录直接标为成功，
     * 造成 task-management 实际没有收到 command，但 outbox 已经从待投递列表消失。</p>
     */
    @Override
    public Optional<AgentAsyncTaskCommandOutboxRecord> markPublished(String outboxId, Instant now) {
        Instant referenceTime = now == null ? Instant.now() : now;
        String sql = "UPDATE agent_async_task_command_outbox SET status = ?, next_retry_at = NULL, "
                + "published_at = ?, last_error = '', update_time = ? WHERE outbox_id = ? AND status = ?";
        return updateThenFind(outboxId, sql, parameters(
                AgentAsyncTaskCommandOutboxStatus.PUBLISHED.name(),
                referenceTime,
                referenceTime,
                outboxId,
                AgentAsyncTaskCommandOutboxStatus.PUBLISHING.name()
        ));
    }

    /**
     * 标记投递失败并设置下一次重试时间。
     *
     * <p>失败不代表 command 作废，而是进入带退避的补偿队列。lastError 只保存短诊断文本，避免把 broker 异常、
     * HTTP 响应体或堆栈长文本塞入业务表；完整异常仍应进入日志平台。</p>
     */
    @Override
    public Optional<AgentAsyncTaskCommandOutboxRecord> markFailed(String outboxId,
                                                                 String error,
                                                                 Instant now,
                                                                 Instant nextRetryAt) {
        Instant referenceTime = now == null ? Instant.now() : now;
        String sql = "UPDATE agent_async_task_command_outbox SET status = ?, next_retry_at = ?, "
                + "last_error = ?, update_time = ? WHERE outbox_id = ? AND status = ?";
        return updateThenFind(outboxId, sql, parameters(
                AgentAsyncTaskCommandOutboxStatus.FAILED.name(),
                nextRetryAt,
                JdbcAgentAsyncTaskCommandOutboxRecordMapper.truncate(error, 1024),
                referenceTime,
                outboxId,
                AgentAsyncTaskCommandOutboxStatus.PUBLISHING.name()
        ));
    }

    /**
     * 标记为阻断。
     *
     * <p>BLOCKED 用于表达“继续自动重试没有意义或存在风险”，例如超过最大尝试次数、缺少 commandTopic、
     * payloadReference 不可解析、租户权限异常或下游契约版本不兼容。后续运维台应围绕 BLOCKED 提供重新入队、
     * 人工忽略、导出排查和修复后补偿能力。</p>
     */
    @Override
    public Optional<AgentAsyncTaskCommandOutboxRecord> markBlocked(String outboxId,
                                                                  String error,
                                                                  Instant now) {
        Instant referenceTime = now == null ? Instant.now() : now;
        String sql = "UPDATE agent_async_task_command_outbox SET status = ?, next_retry_at = NULL, "
                + "last_error = ?, update_time = ? WHERE outbox_id = ? AND status IN (?, ?, ?)";
        return updateThenFind(outboxId, sql, parameters(
                AgentAsyncTaskCommandOutboxStatus.BLOCKED.name(),
                JdbcAgentAsyncTaskCommandOutboxRecordMapper.truncate(error, 1024),
                referenceTime,
                outboxId,
                AgentAsyncTaskCommandOutboxStatus.PENDING.name(),
                AgentAsyncTaskCommandOutboxStatus.PUBLISHING.name(),
                AgentAsyncTaskCommandOutboxStatus.FAILED.name()
        ));
    }

    /**
     * 恢复长时间卡在 PUBLISHING 的记录。
     *
     * <p>PUBLISHING 表示记录已经被某一轮 dispatcher 领取，但尚未写回最终状态。如果进程在发送 Kafka 后、
     * 写回 MySQL 前崩溃，记录可能长期卡住。这里把 update_time 早于阈值的 PUBLISHING 转回 FAILED，并把
     * next_retry_at 设置为当前时间，让下一轮 dispatcher 可以重新领取。</p>
     *
     * <p>这是一种“至少一次投递”的可靠性取舍：stale 恢复可能导致下游再次收到同一 command，
     * 因此 task-management Inbox 必须继续依赖 commandId/idempotencyKey 做幂等去重。</p>
     */
    @Override
    public int recoverStalePublishing(Instant staleBefore,
                                      Instant now,
                                      String error) {
        Instant cutoff = staleBefore == null ? Instant.now() : staleBefore;
        Instant referenceTime = now == null ? Instant.now() : now;
        String sql = "UPDATE agent_async_task_command_outbox SET status = ?, next_retry_at = ?, "
                + "last_error = ?, update_time = ? WHERE status = ? AND update_time <= ?";
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    JdbcAgentAsyncTaskCommandOutboxRecordMapper.bindParameters(statement, parameters(
                            AgentAsyncTaskCommandOutboxStatus.FAILED.name(),
                            referenceTime,
                            JdbcAgentAsyncTaskCommandOutboxRecordMapper.truncate(error, 1024),
                            referenceTime,
                            AgentAsyncTaskCommandOutboxStatus.PUBLISHING.name(),
                            cutoff
                    ));
                    return statement.executeUpdate();
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("恢复 stale PUBLISHING Agent 异步命令 outbox 失败，staleBefore=" + cutoff, exception);
        }
    }

    /**
     * 返回 outbox 诊断摘要。
     *
     * <p>该方法只返回状态计数和容量策略，不返回 payload 明细，避免诊断接口泄露治理上下文。
     * 当 PENDING/FAILED/BLOCKED 持续升高时，后续可接入 Prometheus 指标、Grafana 面板和告警规则。</p>
     */
    @Override
    public AgentAsyncTaskCommandOutboxDiagnostics diagnostics() {
        String sql = "SELECT status, COUNT(*) AS count_value FROM agent_async_task_command_outbox GROUP BY status";
        Map<AgentAsyncTaskCommandOutboxStatus, Integer> counts = new EnumMap<>(AgentAsyncTaskCommandOutboxStatus.class);
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql);
                     ResultSet resultSet = statement.executeQuery()) {
                    int total = 0;
                    while (resultSet.next()) {
                        Optional<AgentAsyncTaskCommandOutboxStatus> status = parseStatus(resultSet.getString("status"));
                        if (status.isEmpty()) {
                            continue;
                        }
                        int count = toIntSafely(resultSet.getLong("count_value"));
                        counts.put(status.get(), count);
                        total += count;
                    }
                    return new AgentAsyncTaskCommandOutboxDiagnostics(enabled,
                            total,
                            counts.getOrDefault(AgentAsyncTaskCommandOutboxStatus.PENDING, 0),
                            counts.getOrDefault(AgentAsyncTaskCommandOutboxStatus.PUBLISHING, 0),
                            counts.getOrDefault(AgentAsyncTaskCommandOutboxStatus.PUBLISHED, 0),
                            counts.getOrDefault(AgentAsyncTaskCommandOutboxStatus.FAILED, 0),
                            counts.getOrDefault(AgentAsyncTaskCommandOutboxStatus.BLOCKED, 0),
                            counts.getOrDefault(AgentAsyncTaskCommandOutboxStatus.DEAD_LETTER, 0),
                            counts.getOrDefault(AgentAsyncTaskCommandOutboxStatus.IGNORED, 0),
                            maxCommandsPerRun,
                            maxTotalRecords,
                            Instant.now()
                    );
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("生成 Agent 异步命令 outbox 诊断摘要失败", exception);
        }
    }

    private Optional<AgentAsyncTaskCommandOutboxRecord> findByOutboxId(Connection connection, String outboxId)
            throws SQLException {
        String sql = "SELECT " + JdbcAgentAsyncTaskCommandOutboxRecordMapper.SELECT_COLUMNS
                + " FROM agent_async_task_command_outbox WHERE outbox_id = ?";
        return queryOne(connection, sql, List.of(outboxId));
    }

    private Optional<AgentAsyncTaskCommandOutboxRecord> queryOne(Connection connection,
                                                                 String sql,
                                                                 List<Object> parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcAgentAsyncTaskCommandOutboxRecordMapper.bindParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(JdbcAgentAsyncTaskCommandOutboxRecordMapper.toRecord(resultSet));
            }
        }
    }

    private List<AgentAsyncTaskCommandOutboxRecord> query(QueryPlan queryPlan, String errorMessage) {
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(queryPlan.sql())) {
                    JdbcAgentAsyncTaskCommandOutboxRecordMapper.bindParameters(statement, queryPlan.parameters());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        List<AgentAsyncTaskCommandOutboxRecord> records = new ArrayList<>();
                        while (resultSet.next()) {
                            records.add(JdbcAgentAsyncTaskCommandOutboxRecordMapper.toRecord(resultSet));
                        }
                        return records;
                    }
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException(errorMessage, exception);
        }
    }

    private Optional<AgentAsyncTaskCommandOutboxRecord> updateThenFind(String outboxId,
                                                                       String sql,
                                                                       List<Object> parameters) {
        if (!hasText(outboxId)) {
            return Optional.empty();
        }
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    JdbcAgentAsyncTaskCommandOutboxRecordMapper.bindParameters(statement, parameters);
                    int updatedRows = statement.executeUpdate();
                    if (updatedRows == 0) {
                        return Optional.empty();
                    }
                    return findByOutboxId(connection, outboxId);
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("更新 Agent 异步命令 outbox 状态失败，outboxId=" + outboxId, exception);
        }
    }

    private QueryPlan buildListQuery(String runId, AgentAsyncTaskCommandOutboxStatus status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(JdbcAgentAsyncTaskCommandOutboxRecordMapper.SELECT_COLUMNS)
                .append(" FROM agent_async_task_command_outbox WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();
        if (hasText(runId)) {
            sql.append(" AND run_id = ?");
            parameters.add(runId);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            parameters.add(status.name());
        }
        sql.append(" ORDER BY id ASC LIMIT ?");
        parameters.add(limit);
        return new QueryPlan(sql.toString(), parameters);
    }

    /**
     * 构造允许包含 null 的参数列表。
     *
     * <p>状态更新时 nextRetryAt 允许为空，lastError 也可能为空；使用 {@link List#of(Object[])} 会因为 null
     * 直接抛出 NPE。这里使用 ArrayList 显式保留 null，让 Mapper 统一绑定为 JDBC NULL。</p>
     */
    private List<Object> parameters(Object... values) {
        List<Object> result = new ArrayList<>();
        for (Object value : values) {
            result.add(value);
        }
        return result;
    }

    private Optional<AgentAsyncTaskCommandOutboxStatus> parseStatus(String value) {
        if (!hasText(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(AgentAsyncTaskCommandOutboxStatus.valueOf(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 100;
        }
        return Math.min(limit, 1000);
    }

    private int toIntSafely(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record QueryPlan(String sql, List<Object> parameters) {
    }
}

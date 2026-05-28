/**
 * @Author : Cui
 * @Date: 2026/05/28 19:45
 * @Description DataSmart Govern Backend - JdbcAgentToolExecutionEventOutboxStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.outbox;

import com.czh.datasmart.govern.agent.config.AgentToolExecutionEventOutboxProperties;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 基于 JDBC/MySQL 的工具执行事件 outbox 仓储。
 *
 * <p>outbox 的职责不是保存工具审计状态本身，而是保存“某条工具状态事件等待被投递”的任务事实。
 * 对真实 Agent 产品来说，这一步很关键：工具已经成功、失败或被拒绝之后，前端 WebSocket、Kafka、审计中心、
 * Python Runtime loop 恢复都依赖这条事件事实。如果 JVM 重启就丢失 outbox，生产环境就无法可靠补偿。</p>
 *
 * <p>该实现只在 {@code datasmart.agent-runtime.persistence.outbox-store=mysql} 且
 * {@code datasmart.agent-runtime.persistence.database-enabled=true} 时注册。
 * 默认 memory 模式仍然不创建数据库连接，保持本地开发和单元测试轻量。</p>
 *
 * <p>4.19 起该 store 会通过 Agent Runtime JDBC 连接管理器取连接；当外层服务打开事务时，
 * outbox append 会复用审计状态保存使用的同一条连接。后台 dispatcher 仍是后续阶段能力。</p>
 */
@Component
@ConditionalOnExpression(
        "'${datasmart.agent-runtime.persistence.outbox-store:memory}'.equalsIgnoreCase('mysql') "
                + "&& '${datasmart.agent-runtime.persistence.database-enabled:false}'.equalsIgnoreCase('true')"
)
public class JdbcAgentToolExecutionEventOutboxStore implements AgentToolExecutionEventOutboxStore {

    private static final String SELECT_COLUMNS = """
            outbox_id, event_id, event_type, schema_version, source, partition_key,
            tenant_id, project_id, workspace_id, actor_id, session_id, run_id, audit_id, tool_code, current_state,
            status, attempt_count, payload_json, payload_size_bytes, payload_truncated,
            occurred_at, next_retry_at, published_at, last_error, create_time, update_time
            """;

    private static final String INSERT_SQL = """
            INSERT INTO agent_tool_execution_event_outbox (
                outbox_id, event_id, event_type, schema_version, source, partition_key,
                tenant_id, project_id, workspace_id, actor_id, session_id, run_id, audit_id, tool_code, current_state,
                status, attempt_count, payload_json, payload_size_bytes, payload_truncated,
                occurred_at, next_retry_at, published_at, last_error, create_time, update_time
            ) VALUES (
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?
            )
            """;

    private final AgentRuntimeJdbcConnectionManager connectionManager;
    private final int maxEventsPerRun;
    private final int maxTotalRecords;

    public JdbcAgentToolExecutionEventOutboxStore(AgentRuntimeJdbcConnectionManager connectionManager,
                                                  AgentToolExecutionEventOutboxProperties properties) {
        this.connectionManager = connectionManager;
        this.maxEventsPerRun = Math.max(1, properties.getMaxEventsPerRun());
        this.maxTotalRecords = Math.max(1, properties.getMaxTotalRecords());
    }

    /**
     * 追加一条 outbox 记录。
     *
     * <p>数据库表对 outboxId 和 eventId 都建了唯一键，因此重复事件会被安全拒绝。
     * 这里把唯一键冲突转换成 false，保持与内存实现一致：false 表示“该事件已存在，不需要重复写入”，不是系统异常。</p>
     */
    @Override
    public boolean append(AgentToolExecutionEventOutboxRecord record) {
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
                    bindRecord(statement, record);
                    statement.executeUpdate();
                    return true;
                }
            });
        } catch (RuntimeException exception) {
            if (isDuplicateKey(exception)) {
                return false;
            }
            throw new IllegalStateException("写入 Agent 工具事件 outbox 到 MySQL 失败，outboxId=" + record.outboxId(), exception);
        }
    }

    /**
     * 按 outboxId 查询单条记录。
     *
     * <p>dispatcher、诊断 API 和人工补偿入口都会使用 outboxId 精确定位待投递事件。</p>
     */
    @Override
    public Optional<AgentToolExecutionEventOutboxRecord> findByOutboxId(String outboxId) {
        if (!hasText(outboxId)) {
            return Optional.empty();
        }
        String sql = "SELECT " + SELECT_COLUMNS + " FROM agent_tool_execution_event_outbox WHERE outbox_id = ?";
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, outboxId);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            return Optional.empty();
                        }
                        return Optional.of(toRecord(resultSet));
                    }
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("查询 Agent 工具事件 outbox 失败，outboxId=" + outboxId, exception);
        }
    }

    /**
     * 查询 outbox 列表。
     *
     * <p>runId/status 是当前前端详情页、Python replay 和诊断页最常见的组合过滤条件。
     * 即使 runId 为空，也会通过 limit 控制返回量，避免一次诊断调用扫描大量历史事件。</p>
     */
    @Override
    public List<AgentToolExecutionEventOutboxRecord> list(String runId,
                                                          AgentToolExecutionEventOutboxStatus status,
                                                          int limit) {
        QueryPlan queryPlan = buildListQuery(runId, status, normalizeLimit(limit));
        return query(queryPlan, "查询 Agent 工具事件 outbox 列表失败，runId=" + runId + ", status=" + status);
    }

    /**
     * 查询当前可以被 dispatcher 投递的记录。
     *
     * <p>只有 PENDING 和 FAILED 会被自动领取；BLOCKED 代表契约、payload 或安全问题，需要人工处理。
     * next_retry_at 为空表示可以立即投递。</p>
     */
    @Override
    public List<AgentToolExecutionEventOutboxRecord> listPublishable(int limit, Instant now) {
        Instant referenceTime = now == null ? Instant.now() : now;
        String sql = "SELECT " + SELECT_COLUMNS
                + " FROM agent_tool_execution_event_outbox"
                + " WHERE status IN (?, ?)"
                + " AND (next_retry_at IS NULL OR next_retry_at <= ?)"
                + " ORDER BY id ASC LIMIT ?";
        QueryPlan queryPlan = new QueryPlan(sql, List.of(
                AgentToolExecutionEventOutboxStatus.PENDING.name(),
                AgentToolExecutionEventOutboxStatus.FAILED.name(),
                referenceTime,
                normalizeLimit(limit)
        ));
        return query(queryPlan, "查询可投递 Agent 工具事件 outbox 失败");
    }

    /**
     * 标记事件正在投递。
     *
     * <p>attempt_count 在领取时递增，而不是失败时递增。这样可以统计实际尝试投递次数，
     * 即使 worker 在投递途中崩溃，也能看出该事件曾经被领取过。</p>
     */
    @Override
    public Optional<AgentToolExecutionEventOutboxRecord> markPublishing(String outboxId, Instant now) {
        Instant referenceTime = now == null ? Instant.now() : now;
        String sql = "UPDATE agent_tool_execution_event_outbox SET status = ?, attempt_count = attempt_count + 1, update_time = ?, "
                + "next_retry_at = NULL WHERE outbox_id = ? AND status IN (?, ?) AND (next_retry_at IS NULL OR next_retry_at <= ?)";
        return updateThenFind(outboxId, sql, List.of(
                AgentToolExecutionEventOutboxStatus.PUBLISHING.name(),
                referenceTime,
                outboxId,
                AgentToolExecutionEventOutboxStatus.PENDING.name(),
                AgentToolExecutionEventOutboxStatus.FAILED.name(),
                referenceTime
        ));
    }

    /** 标记事件已经投递成功。 */
    @Override
    public Optional<AgentToolExecutionEventOutboxRecord> markPublished(String outboxId, Instant now) {
        Instant referenceTime = now == null ? Instant.now() : now;
        String sql = "UPDATE agent_tool_execution_event_outbox SET status = ?, update_time = ?, next_retry_at = NULL, "
                + "published_at = ?, last_error = '' WHERE outbox_id = ? AND status = ?";
        return updateThenFind(outboxId, sql, List.of(
                AgentToolExecutionEventOutboxStatus.PUBLISHED.name(),
                referenceTime,
                referenceTime,
                outboxId,
                AgentToolExecutionEventOutboxStatus.PUBLISHING.name()
        ));
    }

    /** 标记事件投递失败并设置下一次重试时间。 */
    @Override
    public Optional<AgentToolExecutionEventOutboxRecord> markFailed(String outboxId,
                                                                   String error,
                                                                   Instant now,
                                                                   Instant nextRetryAt) {
        Instant referenceTime = now == null ? Instant.now() : now;
        String sql = "UPDATE agent_tool_execution_event_outbox SET status = ?, update_time = ?, next_retry_at = ?, "
                + "last_error = ? WHERE outbox_id = ? AND status = ?";
        return updateThenFind(outboxId, sql, parameters(
                AgentToolExecutionEventOutboxStatus.FAILED.name(),
                referenceTime,
                nextRetryAt,
                truncate(error, 1024),
                outboxId,
                AgentToolExecutionEventOutboxStatus.PUBLISHING.name()
        ));
    }

    @Override
    public Optional<AgentToolExecutionEventOutboxRecord> markBlocked(String outboxId, String error, Instant now) {
        Instant referenceTime = now == null ? Instant.now() : now;
        String sql = "UPDATE agent_tool_execution_event_outbox SET status = ?, update_time = ?, next_retry_at = NULL, "
                + "last_error = ? WHERE outbox_id = ? AND status IN (?, ?, ?)";
        return updateThenFind(outboxId, sql, parameters(
                AgentToolExecutionEventOutboxStatus.BLOCKED.name(),
                referenceTime,
                truncate(error, 1024),
                outboxId,
                AgentToolExecutionEventOutboxStatus.PENDING.name(),
                AgentToolExecutionEventOutboxStatus.PUBLISHING.name(),
                AgentToolExecutionEventOutboxStatus.FAILED.name()
        ));
    }

    /**
     * 返回数据库 outbox 诊断摘要。
     *
     * <p>字段名仍复用现有 diagnostics 契约，其中 inMemoryStore=false 用于告诉前端/运维当前是持久化 store。
     * maxEventsPerRun/maxTotalRecords 在数据库实现里不用于内存裁剪，但仍作为当前配置上限展示，便于观察系统策略。</p>
     */
    @Override
    public AgentToolExecutionEventOutboxDiagnostics diagnostics() {
        String sql = "SELECT status, COUNT(*) AS count_value FROM agent_tool_execution_event_outbox GROUP BY status";
        Map<AgentToolExecutionEventOutboxStatus, Integer> counts = new EnumMap<>(AgentToolExecutionEventOutboxStatus.class);
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql);
                     ResultSet resultSet = statement.executeQuery()) {
                    int total = 0;
                    while (resultSet.next()) {
                        AgentToolExecutionEventOutboxStatus status =
                                AgentToolExecutionEventOutboxStatus.valueOf(resultSet.getString("status"));
                        int count = toIntSafely(resultSet.getLong("count_value"));
                        counts.put(status, count);
                        total += count;
                    }
                    return new AgentToolExecutionEventOutboxDiagnostics(
                            false,
                            total,
                            counts.getOrDefault(AgentToolExecutionEventOutboxStatus.PENDING, 0),
                            counts.getOrDefault(AgentToolExecutionEventOutboxStatus.PUBLISHING, 0),
                            counts.getOrDefault(AgentToolExecutionEventOutboxStatus.PUBLISHED, 0),
                            counts.getOrDefault(AgentToolExecutionEventOutboxStatus.FAILED, 0),
                            counts.getOrDefault(AgentToolExecutionEventOutboxStatus.BLOCKED, 0),
                            maxEventsPerRun,
                            maxTotalRecords,
                            Instant.now()
                    );
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("生成 Agent 工具事件 outbox 诊断摘要失败", exception);
        }
    }

    private List<AgentToolExecutionEventOutboxRecord> query(QueryPlan queryPlan, String errorMessage) {
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(queryPlan.sql())) {
                    bindParameters(statement, queryPlan.parameters());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        List<AgentToolExecutionEventOutboxRecord> records = new ArrayList<>();
                        while (resultSet.next()) {
                            records.add(toRecord(resultSet));
                        }
                        return records;
                    }
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException(errorMessage, exception);
        }
    }

    /**
     * 构造允许包含 null 的参数列表。
     *
     * <p>{@link List#of(Object[])} 不允许 null，但 SQL 更新里 next_retry_at/last_error 本来就允许为空。
     * 单独封装这个小方法，是为了避免某个失败重试场景因为 Java 集合限制而在真正进入 JDBC 前抛出 NPE。</p>
     */
    private List<Object> parameters(Object... values) {
        List<Object> result = new ArrayList<>();
        for (Object value : values) {
            result.add(value);
        }
        return result;
    }

    private Optional<AgentToolExecutionEventOutboxRecord> updateThenFind(String outboxId,
                                                                         String sql,
                                                                         List<Object> parameters) {
        if (!hasText(outboxId)) {
            return Optional.empty();
        }
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    bindParameters(statement, parameters);
                    int updatedRows = statement.executeUpdate();
                    if (updatedRows == 0) {
                        return Optional.empty();
                    }
                    return findByOutboxId(outboxId);
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("更新 Agent 工具事件 outbox 状态失败，outboxId=" + outboxId, exception);
        }
    }

    private QueryPlan buildListQuery(String runId, AgentToolExecutionEventOutboxStatus status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(SELECT_COLUMNS)
                .append(" FROM agent_tool_execution_event_outbox WHERE 1 = 1");
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

    private void bindRecord(PreparedStatement statement, AgentToolExecutionEventOutboxRecord record) throws SQLException {
        int index = 1;
        statement.setString(index++, record.outboxId());
        statement.setString(index++, record.eventId());
        statement.setString(index++, record.eventType());
        statement.setString(index++, record.schemaVersion());
        statement.setString(index++, record.source());
        setNullableString(statement, index++, record.partitionKey());
        setNullableLongFromString(statement, index++, record.tenantId());
        setNullableLongFromString(statement, index++, record.projectId());
        setNullableLongFromString(statement, index++, record.workspaceId());
        setNullableString(statement, index++, record.actorId());
        setNullableString(statement, index++, record.sessionId());
        setNullableString(statement, index++, record.runId());
        statement.setString(index++, record.auditId());
        setNullableString(statement, index++, record.toolCode());
        setNullableString(statement, index++, record.currentState());
        statement.setString(index++, record.status().name());
        statement.setInt(index++, record.attemptCount());
        statement.setString(index++, normalizePayloadJson(record.payloadJson()));
        statement.setInt(index++, record.payloadSizeBytes());
        statement.setBoolean(index++, record.payloadTruncated());
        setNullableTimestamp(statement, index++, record.occurredAt());
        setNullableTimestamp(statement, index++, record.nextRetryAt());
        setNullableTimestamp(statement, index++, record.publishedAt());
        setNullableString(statement, index++, truncate(record.lastError(), 1024));
        setNullableTimestamp(statement, index++, record.createdAt());
        setNullableTimestamp(statement, index, record.updatedAt());
    }

    private AgentToolExecutionEventOutboxRecord toRecord(ResultSet resultSet) throws SQLException {
        return new AgentToolExecutionEventOutboxRecord(
                resultSet.getString("outbox_id"),
                resultSet.getString("event_id"),
                resultSet.getString("event_type"),
                resultSet.getString("schema_version"),
                resultSet.getString("source"),
                resultSet.getString("partition_key"),
                getNullableLongAsString(resultSet, "tenant_id"),
                getNullableLongAsString(resultSet, "project_id"),
                getNullableLongAsString(resultSet, "workspace_id"),
                resultSet.getString("actor_id"),
                resultSet.getString("session_id"),
                resultSet.getString("run_id"),
                resultSet.getString("audit_id"),
                resultSet.getString("tool_code"),
                resultSet.getString("current_state"),
                AgentToolExecutionEventOutboxStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempt_count"),
                getInstant(resultSet, "occurred_at"),
                getInstant(resultSet, "create_time"),
                getInstant(resultSet, "update_time"),
                getInstant(resultSet, "next_retry_at"),
                getInstant(resultSet, "published_at"),
                resultSet.getString("last_error"),
                resultSet.getInt("payload_size_bytes"),
                resultSet.getBoolean("payload_truncated"),
                resultSet.getString("payload_json")
        );
    }

    private void bindParameters(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object parameter = parameters.get(index);
            int jdbcIndex = index + 1;
            if (parameter == null) {
                statement.setNull(jdbcIndex, Types.NULL);
            } else if (parameter instanceof Instant instant) {
                statement.setTimestamp(jdbcIndex, Timestamp.from(instant));
            } else if (parameter instanceof Integer integer) {
                statement.setInt(jdbcIndex, integer);
            } else {
                statement.setString(jdbcIndex, parameter.toString());
            }
        }
    }

    private void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private void setNullableLongFromString(PreparedStatement statement, int index, String value) throws SQLException {
        if (!hasText(value)) {
            statement.setNull(index, Types.BIGINT);
            return;
        }
        try {
            statement.setLong(index, Long.parseLong(value));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("outbox 租户/项目/工作空间 ID 必须是数字，value=" + value, exception);
        }
    }

    private void setNullableTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.from(value));
        }
    }

    private String getNullableLongAsString(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : Long.toString(value);
    }

    private Instant getInstant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private boolean isDuplicateKey(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLIntegrityConstraintViolationException sqlException) {
                return true;
            }
            if (current instanceof SQLException sqlException && "23000".equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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

    private String normalizePayloadJson(String payloadJson) {
        return hasText(payloadJson) ? payloadJson : "{}";
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record QueryPlan(String sql, List<Object> parameters) {
    }
}

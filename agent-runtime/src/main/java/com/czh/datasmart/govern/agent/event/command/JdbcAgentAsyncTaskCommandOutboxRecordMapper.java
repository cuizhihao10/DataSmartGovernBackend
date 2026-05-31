/**
 * @Author : Cui
 * @Date: 2026/05/31 18:00
 * @Description DataSmart Govern Backend - JdbcAgentAsyncTaskCommandOutboxRecordMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

/**
 * Agent ASYNC_TASK 命令 outbox 的 JDBC 字段映射工具。
 *
 * <p>这个类刻意不注册为 Spring Bean，也不承载业务状态。它只负责三件事：</p>
 * <p>1. 维护统一的 SELECT 字段清单，保证查询、诊断和状态更新后回读时字段语义一致；</p>
 * <p>2. 把 {@link AgentAsyncTaskCommandOutboxRecord} 绑定到 INSERT/UPDATE 的 JDBC 参数；</p>
 * <p>3. 把数据库 {@link ResultSet} 还原为领域 record。</p>
 *
 * <p>之所以把这些细节从 Store 中拆出来，是为了控制单文件复杂度和耦合度。command outbox 记录包含
 * commandId、幂等键、租户/项目/工作空间、payloadReference、状态、重试时间等大量字段，如果所有映射都写在
 * Store 里，Store 会很快膨胀为难以维护的大 Impl 文件。拆分后，Store 只需要关注状态机语义：
 * 入箱、领取、投递成功、投递失败、阻断和 stale 恢复。</p>
 */
final class JdbcAgentAsyncTaskCommandOutboxRecordMapper {

    /**
     * 所有 outbox 查询统一复用的字段清单。
     *
     * <p>生产问题排查时，“某个查询少取一个字段”会带来很隐蔽的问题，例如 payloadReference 丢失后
     * task-management 无法回读参数快照，或者 nextRetryAt 丢失导致 dispatcher 误以为可以立即重试。
     * 因此这里把字段集中维护，让列表查询、按 ID 查询、状态更新后回读保持完全一致。</p>
     */
    static final String SELECT_COLUMNS = """
            outbox_id, command_id, idempotency_key, schema_version, command_type, partition_key,
            command_topic, consumer_service, session_id, run_id, audit_id, tool_code, target_service, target_endpoint,
            tenant_id, project_id, workspace_id, actor_id, trace_id, payload_reference,
            status, attempt_count, payload_json, payload_size_bytes, payload_truncated,
            next_retry_at, published_at, last_error, create_time, update_time
            """;

    /**
     * command outbox 插入语句。
     *
     * <p>表层已经对 outbox_id、command_id、idempotency_key 建唯一索引。重复入箱时 MySQL 会抛出唯一键冲突，
     * Store 会把该异常转换为 {@code false}，表达“这条 command 已经存在，无需重复写入”，而不是把它当成系统故障。
     * 这对 Agent 工具链路非常重要：模型重试、用户刷新、dispatcher 补偿都可能重复触发同一 command，系统必须以
     * 幂等方式收敛。</p>
     */
    static final String INSERT_SQL = """
            INSERT INTO agent_async_task_command_outbox (
                outbox_id, command_id, idempotency_key, schema_version, command_type, partition_key,
                command_topic, consumer_service, session_id, run_id, audit_id, tool_code, target_service, target_endpoint,
                tenant_id, project_id, workspace_id, actor_id, trace_id, payload_reference,
                status, attempt_count, payload_json, payload_size_bytes, payload_truncated,
                next_retry_at, published_at, last_error, create_time, update_time
            ) VALUES (
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?
            )
            """;

    private JdbcAgentAsyncTaskCommandOutboxRecordMapper() {
    }

    /**
     * 绑定 INSERT 参数。
     *
     * <p>字段顺序必须与 {@link #INSERT_SQL} 完全一致。这里没有把 command payload 当作 Java 对象序列化，
     * 而是保存字符串 JSON，原因是 command 消息会跨 Java、Python、Kafka UI、运维脚本和未来其他语言组件传播；
     * 字符串 JSON 更容易排查，也避免被 Java 类型头或类名耦合。</p>
     *
     * <p>安全约束：payloadJson 只能包含 envelope、payloadReference、参数名、敏感参数名和治理上下文。
     * 不应保存真实连接密钥、完整 SQL、样本数据、文件内容或用户隐私原文。真正执行时应由 worker 按权限重新解析
     * payloadReference。</p>
     */
    static void bindRecord(PreparedStatement statement, AgentAsyncTaskCommandOutboxRecord record) throws SQLException {
        int index = 1;
        statement.setString(index++, record.outboxId());
        statement.setString(index++, record.commandId());
        statement.setString(index++, record.idempotencyKey());
        statement.setString(index++, record.schemaVersion());
        statement.setString(index++, record.commandType());
        setNullableString(statement, index++, record.partitionKey());
        setNullableString(statement, index++, record.commandTopic());
        setNullableString(statement, index++, record.consumerService());
        statement.setString(index++, record.sessionId());
        statement.setString(index++, record.runId());
        statement.setString(index++, record.auditId());
        statement.setString(index++, record.toolCode());
        statement.setString(index++, record.targetService());
        statement.setString(index++, record.targetEndpoint());
        setNullableLong(statement, index++, record.tenantId());
        setNullableLong(statement, index++, record.projectId());
        setNullableLong(statement, index++, record.workspaceId());
        setNullableString(statement, index++, record.actorId());
        setNullableString(statement, index++, record.traceId());
        statement.setString(index++, record.payloadReference());
        statement.setString(index++, record.status().name());
        statement.setInt(index++, record.attemptCount());
        statement.setString(index++, normalizePayloadJson(record.payloadJson()));
        statement.setInt(index++, record.payloadSizeBytes());
        statement.setBoolean(index++, record.payloadTruncated());
        setNullableTimestamp(statement, index++, record.nextRetryAt());
        setNullableTimestamp(statement, index++, record.publishedAt());
        setNullableString(statement, index++, truncate(record.lastError(), 1024));
        setNullableTimestamp(statement, index++, record.createdAt());
        setNullableTimestamp(statement, index, record.updatedAt());
    }

    /**
     * 从 ResultSet 还原 command outbox 记录。
     *
     * <p>Mapper 不在这里“修正”状态，也不推断业务含义。数据库里是什么状态，就还原为什么状态；
     * 状态是否允许继续流转，由 Store 的条件 UPDATE 和 dispatcher 负责。这样可以避免映射层悄悄改变业务事实，
     * 也方便后续运维台准确展示异常状态。</p>
     */
    static AgentAsyncTaskCommandOutboxRecord toRecord(ResultSet resultSet) throws SQLException {
        return new AgentAsyncTaskCommandOutboxRecord(
                resultSet.getString("outbox_id"),
                resultSet.getString("command_id"),
                resultSet.getString("idempotency_key"),
                resultSet.getString("schema_version"),
                resultSet.getString("command_type"),
                resultSet.getString("partition_key"),
                resultSet.getString("command_topic"),
                resultSet.getString("consumer_service"),
                resultSet.getString("session_id"),
                resultSet.getString("run_id"),
                resultSet.getString("audit_id"),
                resultSet.getString("tool_code"),
                resultSet.getString("target_service"),
                resultSet.getString("target_endpoint"),
                getNullableLong(resultSet, "tenant_id"),
                getNullableLong(resultSet, "project_id"),
                getNullableLong(resultSet, "workspace_id"),
                resultSet.getString("actor_id"),
                resultSet.getString("trace_id"),
                resultSet.getString("payload_reference"),
                AgentAsyncTaskCommandOutboxStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempt_count"),
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

    /**
     * 绑定通用 SQL 参数。
     *
     * <p>状态流转 SQL 会混合使用 String、Instant、Long、Integer、Boolean 和 null。
     * 集中处理这些类型，可以避免每个 Store 方法重复写参数判断，也能保证 null 进入 JDBC 时被显式绑定，
     * 不会因为 {@link java.util.List#of(Object[])} 不支持 null 而在真正执行 SQL 前抛出异常。</p>
     */
    static void bindParameters(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object parameter = parameters.get(index);
            int jdbcIndex = index + 1;
            if (parameter == null) {
                statement.setNull(jdbcIndex, Types.NULL);
            } else if (parameter instanceof Instant instant) {
                statement.setTimestamp(jdbcIndex, Timestamp.from(instant));
            } else if (parameter instanceof Integer integer) {
                statement.setInt(jdbcIndex, integer);
            } else if (parameter instanceof Long longValue) {
                statement.setLong(jdbcIndex, longValue);
            } else if (parameter instanceof Boolean booleanValue) {
                statement.setBoolean(jdbcIndex, booleanValue);
            } else {
                statement.setString(jdbcIndex, parameter.toString());
            }
        }
    }

    /**
     * 截断诊断文本。
     *
     * <p>last_error 是定位字段，不是完整日志存储。真实堆栈、请求上下文和 broker 返回体应进入日志平台或告警事件，
     * 这里保留短文本用于列表页快速判断原因，防止超长异常把 outbox 行撑大。</p>
     */
    static String truncate(String value, int maxLength) {
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

    private static String normalizePayloadJson(String payloadJson) {
        return hasText(payloadJson) ? payloadJson : "{}";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

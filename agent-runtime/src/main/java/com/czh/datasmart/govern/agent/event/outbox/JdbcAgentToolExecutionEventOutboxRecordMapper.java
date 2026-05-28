/**
 * @Author : Cui
 * @Date: 2026/05/28 20:45
 * @Description DataSmart Govern Backend - JdbcAgentToolExecutionEventOutboxRecordMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.outbox;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

/**
 * JDBC outbox 记录映射工具。
 *
 * <p>这个类刻意保持为包内可见的基础设施 helper，而不是 Spring Bean。它没有业务状态，也不需要依赖注入；
 * 主要职责是把“数据库列、JDBC 参数绑定、ResultSet 还原 record”从
 * {@link JdbcAgentToolExecutionEventOutboxStore} 中拆出来，避免 Store 既负责状态流转又负责大量字段映射，
 * 最终演变成难以维护的超大 Impl。</p>
 *
 * <p>拆分后，Store 可以更聚焦于 outbox 的业务语义：追加、查询、领取、成功、失败、阻断和 stale 恢复；
 * Mapper 则专注解决 JDBC 细节：时间类型转换、可空字段绑定、租户/项目数字化、防止超长错误信息写库等。
 * 这也是当前项目“低耦合、单文件尽量低于 500 行”的落地方式之一。</p>
 */
final class JdbcAgentToolExecutionEventOutboxRecordMapper {

    /**
     * outbox 查询统一列清单。
     *
     * <p>所有查询都复用同一列清单，可以避免某个查询漏选 payloadTruncated、nextRetryAt 等字段，
     * 导致从数据库还原出的领域记录语义不完整。</p>
     */
    static final String SELECT_COLUMNS = """
            outbox_id, event_id, event_type, schema_version, source, partition_key,
            tenant_id, project_id, workspace_id, actor_id, session_id, run_id, audit_id, tool_code, current_state,
            status, attempt_count, payload_json, payload_size_bytes, payload_truncated,
            occurred_at, next_retry_at, published_at, last_error, create_time, update_time
            """;

    /**
     * outbox 插入语句。
     *
     * <p>字段顺序必须与 {@link #bindRecord(PreparedStatement, AgentToolExecutionEventOutboxRecord)}
     * 保持一致。表层通过 outbox_id/event_id 唯一键保证幂等写入，重复事件会被 Store 捕获并转换为 false。</p>
     */
    static final String INSERT_SQL = """
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

    private JdbcAgentToolExecutionEventOutboxRecordMapper() {
    }

    /**
     * 把领域 record 绑定为 INSERT 参数。
     *
     * <p>这里没有把 tenantId/projectId/workspaceId 当作字符串直接入库，而是转换成 BIGINT。
     * 原因是这些字段后续会参与租户隔离、项目范围查询、审计过滤和分区归档，如果数据库层保存成字符串，
     * 后续索引选择性、排序和数据一致性都会更差。</p>
     */
    static void bindRecord(PreparedStatement statement, AgentToolExecutionEventOutboxRecord record) throws SQLException {
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

    /**
     * 把数据库行还原为 outbox 领域记录。
     *
     * <p>该方法只做字段映射，不做状态修正。状态是否合法、是否允许从某个状态流转到另一个状态，
     * 由 Store 的条件 SQL 和领域 record 方法负责，避免 Mapper 悄悄改变业务语义。</p>
     */
    static AgentToolExecutionEventOutboxRecord toRecord(ResultSet resultSet) throws SQLException {
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

    /**
     * 绑定通用 SQL 参数。
     *
     * <p>Store 中的状态更新 SQL 很多都会包含 Instant、String、Integer 和 null。
     * 统一放在这里处理，可以避免每个状态方法重复写类型判断，也能保证 null 参数进入 JDBC 时不会触发 NPE。</p>
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
            } else {
                statement.setString(jdbcIndex, parameter.toString());
            }
        }
    }

    /**
     * 截断超长文本。
     *
     * <p>last_error 是诊断字段，不是完整日志存储。限制长度可以防止异常堆栈或下游返回的大段 HTML/JSON
     * 把 outbox 行撑得过大；完整排障信息后续应进入日志平台或运维导出包。</p>
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

    private static void setNullableLongFromString(PreparedStatement statement, int index, String value) throws SQLException {
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

    private static void setNullableTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.from(value));
        }
    }

    private static String getNullableLongAsString(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : Long.toString(value);
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

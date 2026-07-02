/**
 * @Author : Cui
 * @Date: 2026/06/18 00:00
 * @Description DataSmart Govern Backend - JdbcAgentToolActionWorkerReceiptIndexRecordMapper.java
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
 * worker/dry-run receipt 低敏索引的 JDBC 字段映射器。
 *
 * <p>该类故意保持 package-private，并且不注册为 Spring Bean。它不是业务服务，而是
 * {@link JdbcAgentToolActionWorkerReceiptIndexStore} 与 PostgreSQL/JDBC 控制面表之间的字段翻译层：
 * Store 负责仓储语义、查询范围收口、异常包装和可替换边界；Mapper 只负责 SQL 字段清单、
 * 插入/刷新参数绑定、动态查询参数绑定和 ResultSet 还原。</p>
 *
 * <p>安全边界必须非常清晰：本 mapper 只处理 commandId、租户/项目/actor、run/session、toolCode、
 * taskStatus、outcome、preCheckPassed、sideEffectExecuted、errorCode、replaySequence 和时间戳。
 * 它没有任何 JSON payload 字段，也不会保存 prompt、SQL、工具参数值、样本数据、模型输出、凭证、
 * token、内部 endpoint 或工具结果正文。worker receipt 是恢复前的宿主事实，不是第二份敏感上下文缓存。</p>
 */
final class JdbcAgentToolActionWorkerReceiptIndexRecordMapper {

    /**
     * worker receipt 查询统一字段清单。
     *
     * <p>所有查询方法都复用这一份字段清单，避免 INSERT/UPSERT 与 SELECT 字段漂移。
     * 表内自增 id 不进入领域模型，因为恢复事实包只关心低敏业务定位符和最新状态，不关心数据库行号。</p>
     */
    static final String SELECT_COLUMNS = """
            event_identity_key, command_id, tenant_id, project_id, actor_id,
            run_id, session_id, tool_code, task_status, outcome,
            pre_check_passed, side_effect_executed, error_code, replay_sequence,
            consumed_at, indexed_at
            """;

    /**
     * worker receipt 首次插入 SQL。
     *
     * <p>event_identity_key 是幂等主键。同一条 receipt 如果被 HTTP 重试、Kafka 重放或 fallback 补物化多次，
     * PostgreSQL 的唯一索引会让重复请求走 {@code DO NOTHING}，不会放大 receiptCount。
     * 这里故意不用 {@code ON CONFLICT DO UPDATE} 直接完成刷新，是因为 PostgreSQL 对 insert/update
     * 都通常返回 1 行影响数，无法像 MySQL {@code ON DUPLICATE KEY UPDATE} 那样通过影响行数稳定区分
     * “首次插入”和“重复更新”。Store 会先执行本 SQL，只有真正插入成功才返回 true。</p>
     */
    static final String INSERT_SQL = """
            INSERT INTO agent_tool_action_worker_receipt_index (
                event_identity_key, command_id, tenant_id, project_id, actor_id,
                run_id, session_id, tool_code, task_status, outcome,
                pre_check_passed, side_effect_executed, error_code, replay_sequence,
                consumed_at, indexed_at
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?
            )
            ON CONFLICT (event_identity_key) DO NOTHING
            """;

    /**
     * worker receipt 重复事件刷新 SQL。
     *
     * <p>当 {@link #INSERT_SQL} 返回 0 时，说明相同 event_identity_key 已经存在。此时再执行 UPDATE，
     * 把低敏白名单字段刷新到最新状态。拆成两条 SQL 的设计意图有两个：
     * 第一，保留 {@link AgentToolActionWorkerReceiptIndexStore#upsert(AgentToolActionWorkerReceiptIndexRecord)}
     * 的返回值语义，true 只代表首次插入；第二，把“并发去重”和“补偿刷新”拆开，便于后续给重复刷新单独加指标、
     * 限流或审计事件，而不会让 PostgreSQL 行影响数语义污染业务判断。</p>
     */
    static final String UPDATE_SQL = """
            UPDATE agent_tool_action_worker_receipt_index SET
                command_id = ?,
                tenant_id = ?,
                project_id = ?,
                actor_id = ?,
                run_id = ?,
                session_id = ?,
                tool_code = ?,
                task_status = ?,
                outcome = ?,
                pre_check_passed = ?,
                side_effect_executed = ?,
                error_code = ?,
                replay_sequence = ?,
                consumed_at = ?,
                indexed_at = ?,
                update_time = CURRENT_TIMESTAMP
            WHERE event_identity_key = ?
            """;

    private JdbcAgentToolActionWorkerReceiptIndexRecordMapper() {
    }

    /**
     * 绑定首次插入参数。
     *
     * <p>长度裁剪在这里集中处理，原因是 JDBC 低敏索引表不应该因为某个上游异常长 ID 直接写库失败。
     * 真正完整的异常上下文应该进入受控审计详情或日志系统，而不是进入 worker receipt index。</p>
     */
    static void bindInsertRecord(PreparedStatement statement,
                                 AgentToolActionWorkerReceiptIndexRecord record) throws SQLException {
        int index = 1;
        setNullableString(statement, index++, truncate(record.eventIdentityKey(), 240));
        setNullableString(statement, index++, truncate(record.commandId(), 180));
        setNullableString(statement, index++, truncate(record.tenantId(), 80));
        setNullableString(statement, index++, truncate(record.projectId(), 80));
        setNullableString(statement, index++, truncate(record.actorId(), 120));
        setNullableString(statement, index++, truncate(record.runId(), 180));
        setNullableString(statement, index++, truncate(record.sessionId(), 180));
        setNullableString(statement, index++, truncate(record.toolCode(), 180));
        setNullableString(statement, index++, truncate(record.taskStatus(), 80));
        setNullableString(statement, index++, truncate(record.outcome(), 120));
        statement.setBoolean(index++, Boolean.TRUE.equals(record.preCheckPassed()));
        statement.setBoolean(index++, Boolean.TRUE.equals(record.sideEffectExecuted()));
        setNullableString(statement, index++, truncate(record.errorCode(), 160));
        setNullableLong(statement, index++, record.replaySequence());
        setNullableTimestamp(statement, index++, record.consumedAt());
        setNullableTimestamp(statement, index, record.indexedAt());
    }

    /**
     * 绑定重复刷新参数。
     *
     * <p>UPDATE 参数顺序故意与 INSERT 的字段顺序保持一致，只是把 eventIdentityKey 移到最后作为 WHERE 条件。
     * 这种写法比复用“更新主键为自身”的 SQL 更直观：event_identity_key 是幂等定位符，不是业务上需要被更新的字段。
     * 如果将来 event identity 生成算法升级，旧行也应保持原键不变，通过补偿任务写入新键对应的新事实。</p>
     */
    static void bindUpdateRecord(PreparedStatement statement,
                                 AgentToolActionWorkerReceiptIndexRecord record) throws SQLException {
        int index = 1;
        setNullableString(statement, index++, truncate(record.commandId(), 180));
        setNullableString(statement, index++, truncate(record.tenantId(), 80));
        setNullableString(statement, index++, truncate(record.projectId(), 80));
        setNullableString(statement, index++, truncate(record.actorId(), 120));
        setNullableString(statement, index++, truncate(record.runId(), 180));
        setNullableString(statement, index++, truncate(record.sessionId(), 180));
        setNullableString(statement, index++, truncate(record.toolCode(), 180));
        setNullableString(statement, index++, truncate(record.taskStatus(), 80));
        setNullableString(statement, index++, truncate(record.outcome(), 120));
        statement.setBoolean(index++, Boolean.TRUE.equals(record.preCheckPassed()));
        statement.setBoolean(index++, Boolean.TRUE.equals(record.sideEffectExecuted()));
        setNullableString(statement, index++, truncate(record.errorCode(), 160));
        setNullableLong(statement, index++, record.replaySequence());
        setNullableTimestamp(statement, index++, record.consumedAt());
        setNullableTimestamp(statement, index++, record.indexedAt());
        setNullableString(statement, index, truncate(record.eventIdentityKey(), 240));
    }

    /**
     * 从数据库行还原低敏 receipt 索引记录。
     *
     * <p>还原后，上层仍会把记录放在 fact bundle 的访问上下文中解释。也就是说，数据库命中不是最终授权；
     * Store 的 SQL 范围条件和上层事实聚合一起构成多层防护，避免 commandId 被跨租户或跨项目误用。</p>
     */
    static AgentToolActionWorkerReceiptIndexRecord toRecord(ResultSet resultSet) throws SQLException {
        return new AgentToolActionWorkerReceiptIndexRecord(
                resultSet.getString("event_identity_key"),
                resultSet.getString("command_id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("project_id"),
                resultSet.getString("actor_id"),
                resultSet.getString("run_id"),
                resultSet.getString("session_id"),
                resultSet.getString("tool_code"),
                resultSet.getString("task_status"),
                resultSet.getString("outcome"),
                resultSet.getBoolean("pre_check_passed"),
                resultSet.getBoolean("side_effect_executed"),
                resultSet.getString("error_code"),
                nullableLong(resultSet, "replay_sequence"),
                getInstant(resultSet, "consumed_at"),
                getInstant(resultSet, "indexed_at")
        );
    }

    /**
     * 绑定动态查询参数。
     *
     * <p>worker receipt 查询会根据 tenant/project/actor/run/session/toolCode/authorizedProjectIds 动态拼接 SQL。
     * 统一参数绑定能避免 Store 层散落大量 setString/setInt，也让后续增加时间范围、分页游标或归档查询时更容易维护。</p>
     */
    static void bindParameters(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object parameter = parameters.get(index);
            int jdbcIndex = index + 1;
            if (parameter == null) {
                statement.setNull(jdbcIndex, Types.NULL);
            } else if (parameter instanceof Integer integer) {
                statement.setInt(jdbcIndex, integer);
            } else if (parameter instanceof Long longValue) {
                statement.setLong(jdbcIndex, longValue);
            } else if (parameter instanceof Instant instant) {
                statement.setTimestamp(jdbcIndex, Timestamp.from(instant));
            } else {
                statement.setString(jdbcIndex, parameter.toString());
            }
        }
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

    private static void setNullableTimestamp(PreparedStatement statement, int index, Instant value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.from(value));
        }
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Instant getInstant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}

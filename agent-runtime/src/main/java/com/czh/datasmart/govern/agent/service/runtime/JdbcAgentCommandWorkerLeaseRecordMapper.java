/**
 * @Author : Cui
 * @Date: 2026/06/24 02:10
 * @Description DataSmart Govern Backend - JdbcAgentCommandWorkerLeaseRecordMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;

/**
 * command worker lease 的 JDBC 字段映射器。
 *
 * <p>该类只负责“Java record 与 MySQL 行之间怎么转换”，不负责领取策略、并发判断或业务异常。
 * 这样拆分是为了让 {@link JdbcAgentCommandWorkerLeaseStore} 聚焦 lease 状态流转，避免一个 Store 文件同时塞入
 * 大量 SQL 字段绑定细节，后续如果表结构扩展为 Redis、ClickHouse 审计旁路或归档表，也能独立替换映射层。</p>
 *
 * <p>安全边界：表中确实会保存 fencingToken 明文，因为 receipt 写回时必须校验 token 是否仍是当前有效持有者。
 * 但该 mapper 不把 token 映射到 runtime event、timeline、指标或外部查询响应。token 只能在内部 store 与
 * worker claim/receipt 的短生命周期路径中出现。</p>
 */
final class JdbcAgentCommandWorkerLeaseRecordMapper {

    /**
     * 查询字段清单。
     *
     * <p>所有 SELECT 都复用这份字段清单，避免 insert/update/select 字段漂移。
     * MySQL 表里的 update_time 只作为数据库运维字段，不进入领域 record。</p>
     */
    static final String SELECT_COLUMNS = """
            lease_identity_key, session_id, run_id, command_id, executor_id,
            tenant_id, project_id, actor_id, fencing_token, lease_version,
            lease_expires_at, acquired_at, updated_at
            """;

    /**
     * 新 lease 插入 SQL。
     *
     * <p>lease_identity_key 是主键，等价于 sessionId/runId/commandId 的组合定位符。
     * 如果两个 worker 并发领取同一 command，MySQL 主键和事务锁会保证只有一个写入者成为当前持有者。</p>
     */
    static final String INSERT_SQL = """
            INSERT INTO agent_command_worker_lease (
                lease_identity_key, session_id, run_id, command_id, executor_id,
                tenant_id, project_id, actor_id, fencing_token, lease_version,
                lease_expires_at, acquired_at, updated_at
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?
            )
            """;

    /**
     * 过期 lease 被新 worker 抢占或同 worker 重新领取时的更新 SQL。
     *
     * <p>更新时必须同时写入新的 executor、token、version、expiresAt 和 acquiredAt。version 单调递增后，
     * 旧 worker 即使还持有旧 token，也会因为 version/token 不匹配被 receipt service 拒绝。</p>
     */
    static final String UPDATE_SQL = """
            UPDATE agent_command_worker_lease
            SET executor_id = ?,
                tenant_id = ?,
                project_id = ?,
                actor_id = ?,
                fencing_token = ?,
                lease_version = ?,
                lease_expires_at = ?,
                acquired_at = ?,
                updated_at = ?,
                update_time = CURRENT_TIMESTAMP
            WHERE lease_identity_key = ?
            """;

    private JdbcAgentCommandWorkerLeaseRecordMapper() {
    }

    /**
     * 绑定插入参数。
     *
     * <p>这里统一做长度裁剪，原因是 lease 表属于控制面事实表，不应该因为某个外部 worker 传入异常长低敏 ID
     * 直接把写库路径打断。真正敏感文本已经在 service 层被拒绝，mapper 只做数据库字段长度保护。</p>
     */
    static void bindInsert(PreparedStatement statement, AgentCommandWorkerLeaseRecord record) throws SQLException {
        int index = 1;
        setNullableString(statement, index++, truncate(record.leaseIdentityKey(), 260));
        setNullableString(statement, index++, truncate(record.sessionId(), 180));
        setNullableString(statement, index++, truncate(record.runId(), 180));
        setNullableString(statement, index++, truncate(record.commandId(), 180));
        setNullableString(statement, index++, truncate(record.executorId(), 160));
        setNullableString(statement, index++, truncate(record.tenantId(), 80));
        setNullableString(statement, index++, truncate(record.projectId(), 80));
        setNullableString(statement, index++, truncate(record.actorId(), 120));
        setNullableString(statement, index++, truncate(record.fencingToken(), 128));
        statement.setLong(index++, record.leaseVersion());
        setNullableTimestamp(statement, index++, record.leaseExpiresAt());
        setNullableTimestamp(statement, index++, record.acquiredAt());
        setNullableTimestamp(statement, index, record.updatedAt());
    }

    /**
     * 绑定更新参数。
     *
     * <p>更新只允许发生在当前 Store 已经判定“旧 lease 不活跃”之后。SQL 本身不重新判断业务状态，
     * 是为了把并发语义集中在 Store 的 select-for-update 事务里，避免两个地方各自维护一套状态判断。</p>
     */
    static void bindUpdate(PreparedStatement statement, AgentCommandWorkerLeaseRecord record) throws SQLException {
        int index = 1;
        setNullableString(statement, index++, truncate(record.executorId(), 160));
        setNullableString(statement, index++, truncate(record.tenantId(), 80));
        setNullableString(statement, index++, truncate(record.projectId(), 80));
        setNullableString(statement, index++, truncate(record.actorId(), 120));
        setNullableString(statement, index++, truncate(record.fencingToken(), 128));
        statement.setLong(index++, record.leaseVersion());
        setNullableTimestamp(statement, index++, record.leaseExpiresAt());
        setNullableTimestamp(statement, index++, record.acquiredAt());
        setNullableTimestamp(statement, index++, record.updatedAt());
        setNullableString(statement, index, truncate(record.leaseIdentityKey(), 260));
    }

    /**
     * 从 ResultSet 还原 lease fact。
     *
     * <p>调用方会继续判断 activeAt、heldBy 和 token/version 是否匹配；mapper 只还原数据库事实，
     * 不提前把过期 lease 过滤掉，便于 Store 在 claim 时根据旧版本号生成递增的新 fencing token。</p>
     */
    static AgentCommandWorkerLeaseRecord toRecord(ResultSet resultSet) throws SQLException {
        return new AgentCommandWorkerLeaseRecord(
                resultSet.getString("lease_identity_key"),
                resultSet.getString("session_id"),
                resultSet.getString("run_id"),
                resultSet.getString("command_id"),
                resultSet.getString("executor_id"),
                resultSet.getString("tenant_id"),
                resultSet.getString("project_id"),
                resultSet.getString("actor_id"),
                resultSet.getString("fencing_token"),
                resultSet.getLong("lease_version"),
                getInstant(resultSet, "lease_expires_at"),
                getInstant(resultSet, "acquired_at"),
                getInstant(resultSet, "updated_at")
        );
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

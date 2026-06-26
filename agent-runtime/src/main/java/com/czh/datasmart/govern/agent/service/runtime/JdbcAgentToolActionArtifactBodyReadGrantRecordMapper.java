/**
 * @Author : Cui
 * @Date: 2026/06/26 21:18
 * @Description DataSmart Govern Backend - JdbcAgentToolActionArtifactBodyReadGrantRecordMapper.java
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
 * artifact 正文读取 grant fact 的 JDBC 字段映射器。
 *
 * <p>该类故意保持 package-private，不注册成 Spring Bean。它的职责非常窄：
 * 1. 维护低敏 grant fact 查询字段清单；
 * 2. 维护幂等 upsert SQL；
 * 3. 将 {@link AgentToolActionArtifactBodyReadGrantRecord} 绑定为 PreparedStatement 参数；
 * 4. 从 ResultSet 还原领域 record。</p>
 *
 * <p>安全边界：本 mapper 只处理 grantDecisionReference、commandId、artifactReference、租户/项目/actor、
 * run/session、toolCode、receipt 摘要、状态和时间戳等低敏控制面字段。它没有 artifact 正文列，也不会接触
 * sample bytes、stdout/stderr、bucket/key、签名 URL、bearer token、prompt、SQL、工具参数、模型输出、凭据或内部 endpoint。</p>
 */
final class JdbcAgentToolActionArtifactBodyReadGrantRecordMapper {

    /**
     * grant fact 查询统一字段清单。
     *
     * <p>所有 SELECT 都复用这一份清单，避免后续新增列时 upsert 与查询还原发生字段漂移。
     * 数据库自增 id、create_time、update_time 不进入领域 record，因为执行控制面只关心授权事实本身。</p>
     */
    static final String SELECT_COLUMNS = """
            grant_decision_reference, command_id, artifact_reference, artifact_reference_type,
            read_purpose, requested_content_mode, max_readable_bytes,
            tenant_id, project_id, actor_id, run_id, session_id, tool_code,
            matched_receipt_fingerprint, replay_sequence, receipt_outcome,
            issued_at, expires_at, status, revoked_at, revoked_by, revoke_reason_code
            """;

    /**
     * grant fact 幂等写入 SQL。
     *
     * <p>grant_decision_reference 是唯一键。同一条授权事实因为 HTTP 重试、控制面重放或补物化多次写入时，
     * 只刷新同一行，不制造重复事实。这里允许状态字段被更新，是为了支持后续 EXPIRED/REVOKED 物化；
     * 但字段集合始终只包含低敏白名单，不能把 artifact 正文或对象存储定位信息写入表。</p>
     */
    static final String UPSERT_SQL = """
            INSERT INTO agent_artifact_body_read_grant_fact (
                grant_decision_reference, command_id, artifact_reference, artifact_reference_type,
                read_purpose, requested_content_mode, max_readable_bytes,
                tenant_id, project_id, actor_id, run_id, session_id, tool_code,
                matched_receipt_fingerprint, replay_sequence, receipt_outcome,
                issued_at, expires_at, status, revoked_at, revoked_by, revoke_reason_code
            ) VALUES (
                ?, ?, ?, ?,
                ?, ?, ?,
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?,
                ?, ?, ?, ?, ?, ?
            )
            ON DUPLICATE KEY UPDATE
                command_id = VALUES(command_id),
                artifact_reference = VALUES(artifact_reference),
                artifact_reference_type = VALUES(artifact_reference_type),
                read_purpose = VALUES(read_purpose),
                requested_content_mode = VALUES(requested_content_mode),
                max_readable_bytes = VALUES(max_readable_bytes),
                tenant_id = VALUES(tenant_id),
                project_id = VALUES(project_id),
                actor_id = VALUES(actor_id),
                run_id = VALUES(run_id),
                session_id = VALUES(session_id),
                tool_code = VALUES(tool_code),
                matched_receipt_fingerprint = VALUES(matched_receipt_fingerprint),
                replay_sequence = VALUES(replay_sequence),
                receipt_outcome = VALUES(receipt_outcome),
                issued_at = VALUES(issued_at),
                expires_at = VALUES(expires_at),
                status = VALUES(status),
                revoked_at = VALUES(revoked_at),
                revoked_by = VALUES(revoked_by),
                revoke_reason_code = VALUES(revoke_reason_code),
                update_time = CURRENT_TIMESTAMP(3)
            """;

    private JdbcAgentToolActionArtifactBodyReadGrantRecordMapper() {
    }

    /**
     * 绑定 grant fact upsert 参数。
     *
     * <p>字符串会按数据库列宽做防御性裁剪，避免异常长低敏 ID 直接打断写库链路。
     * 裁剪不是为了隐藏敏感正文，而是因为这里本来就不允许出现正文；如果上游把 prompt、SQL、token
     * 或对象路径误塞进这些字段，前置服务和测试应先拦截，不能依赖数据库列宽来“脱敏”。</p>
     */
    static void bindRecord(PreparedStatement statement,
                           AgentToolActionArtifactBodyReadGrantRecord record) throws SQLException {
        int index = 1;
        setNullableString(statement, index++, truncate(record.grantDecisionReference(), 240));
        setNullableString(statement, index++, truncate(record.commandId(), 180));
        setNullableString(statement, index++, truncate(record.artifactReference(), 500));
        setNullableString(statement, index++, truncate(record.artifactReferenceType(), 80));
        setNullableString(statement, index++, truncate(record.readPurpose(), 120));
        setNullableString(statement, index++, truncate(record.requestedContentMode(), 120));
        setNullableInteger(statement, index++, record.maxReadableBytes());
        setNullableString(statement, index++, truncate(record.tenantId(), 80));
        setNullableString(statement, index++, truncate(record.projectId(), 80));
        setNullableString(statement, index++, truncate(record.actorId(), 120));
        setNullableString(statement, index++, truncate(record.runId(), 180));
        setNullableString(statement, index++, truncate(record.sessionId(), 180));
        setNullableString(statement, index++, truncate(record.toolCode(), 180));
        setNullableString(statement, index++, truncate(record.matchedReceiptFingerprint(), 160));
        setNullableLong(statement, index++, record.replaySequence());
        setNullableString(statement, index++, truncate(record.receiptOutcome(), 120));
        setNullableEpochMillis(statement, index++, record.issuedAtEpochMs());
        setNullableEpochMillis(statement, index++, record.expiresAtEpochMs());
        setNullableString(statement, index++, record.status() == null ? null : record.status().name());
        setNullableEpochMillis(statement, index++, record.revokedAtEpochMs());
        setNullableString(statement, index++, truncate(record.revokedBy(), 120));
        setNullableString(statement, index, truncate(record.revokeReasonCode(), 120));
    }

    /**
     * 从数据库行还原 grant fact。
     *
     * <p>还原后的 record 仍然只是“授权事实存在”的证据；final-check/probe 会继续校验状态、过期时间、
     * 上下文一致性和当前授权策略，不会因为 MySQL 查询命中就直接读取正文。</p>
     */
    static AgentToolActionArtifactBodyReadGrantRecord toRecord(ResultSet resultSet) throws SQLException {
        return new AgentToolActionArtifactBodyReadGrantRecord(
                resultSet.getString("grant_decision_reference"),
                resultSet.getString("command_id"),
                resultSet.getString("artifact_reference"),
                resultSet.getString("artifact_reference_type"),
                resultSet.getString("read_purpose"),
                resultSet.getString("requested_content_mode"),
                nullableInteger(resultSet, "max_readable_bytes"),
                resultSet.getString("tenant_id"),
                resultSet.getString("project_id"),
                resultSet.getString("actor_id"),
                resultSet.getString("run_id"),
                resultSet.getString("session_id"),
                resultSet.getString("tool_code"),
                resultSet.getString("matched_receipt_fingerprint"),
                nullableLong(resultSet, "replay_sequence"),
                resultSet.getString("receipt_outcome"),
                requireEpochMillis(resultSet, "issued_at"),
                nullableEpochMillis(resultSet, "expires_at"),
                status(resultSet.getString("status")),
                nullableEpochMillis(resultSet, "revoked_at"),
                resultSet.getString("revoked_by"),
                resultSet.getString("revoke_reason_code")
        );
    }

    /**
     * 绑定动态查询参数。
     *
     * <p>Store 会按查询条件动态拼接 SQL。集中绑定可以避免每个查询分支手写 setString/setInt，
     * 也让后续扩展时间范围、分页游标或 TTL 归档查询时保持一致的类型处理。</p>
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

    private static void setNullableInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setNullableEpochMillis(PreparedStatement statement, int index, Long epochMillis)
            throws SQLException {
        if (epochMillis == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.from(Instant.ofEpochMilli(epochMillis)));
        }
    }

    private static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Long nullableEpochMillis(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant().toEpochMilli();
    }

    private static long requireEpochMillis(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        if (timestamp == null) {
            throw new SQLException("grant fact 缺少必填时间列: " + column);
        }
        return timestamp.toInstant().toEpochMilli();
    }

    private static AgentToolActionArtifactBodyReadGrantStatus status(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return AgentToolActionArtifactBodyReadGrantStatus.valueOf(value.trim());
    }
}

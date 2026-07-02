/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - JdbcAgentToolActionResumeLocatorIndexRecordMapper.java
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
 * checkpoint/thread 恢复 locator index 的 JDBC 字段映射器。
 *
 * <p>这个类刻意保持 package-private，并且不注册为 Spring Bean。原因是它不是业务服务，
 * 而是 {@link JdbcAgentToolActionResumeLocatorIndexStore} 与 JDBC/PostgreSQL 表之间的“字段翻译层”：
 * Store 负责仓储语义、异常包装、查询入口和可替换边界；Mapper 只负责 SQL 字段清单、参数绑定、
 * ResultSet 还原和长度裁剪。这样可以避免 JDBC Store 迅速膨胀成几百行的大 Impl。</p>
 *
 * <p>安全边界非常关键：本 mapper 只处理低敏定位符，例如 checkpointId、threadId、commandId、
 * outboxId、approvalFactId、clarificationFactId、toolCode 和 policyVersion。它不提供任何 JSON
 * payload 字段，也不保存 prompt、SQL、工具参数值、样本数据、模型输出、凭证或内部 endpoint。
 * 如果未来要定位敏感执行上下文，也应通过受控 payloadReference 或审计详情接口回查，而不是把正文塞进 locator index。</p>
 */
final class JdbcAgentToolActionResumeLocatorIndexRecordMapper {

    /**
     * locator index 查询统一字段清单。
     *
     * <p>所有查询方法都复用这一份字段清单，避免“INSERT 写了字段，但查询忘记读字段”的维护问题。
     * 这里没有包含数据库自增 id，因为上层业务只关心 checkpoint/thread 与恢复事实定位符的映射关系；
     * 自增 id 只用于数据库内部排序和运维定位。</p>
     */
    static final String SELECT_COLUMNS = """
            checkpoint_id, thread_id, session_id, run_id, command_id, outbox_id,
            approval_fact_id, clarification_fact_id, tool_code, requested_policy_version,
            tenant_id, project_id, actor_id, updated_at
            """;

    /**
     * 按 checkpointId 作为冲突目标的 locator index 幂等 upsert SQL。
     *
     * <p>早期 MySQL 版本可以用 {@code ON DUPLICATE KEY UPDATE} 同时吃掉 checkpoint_id 与 thread_id
     * 两个唯一键冲突；PostgreSQL 的 {@code ON CONFLICT} 必须明确一个冲突目标。因此这里拆成两个 SQL：
     * 本常量面向 checkpointId 精确恢复入口，下面的 {@link #UPSERT_BY_THREAD_SQL} 面向 threadId 兜底恢复入口。
     * Store 会根据请求中实际携带的低敏定位符选择主路径，并在另一个唯一键先命中时做一次受控 fallback。</p>
     *
     * <p>字段合并采用“新值非空才覆盖”的规则。这样第一轮只学到 commandId，第二轮又学到 approvalFactId
     * 时，两者会在同一条 locator 记录里逐步汇合；如果新请求没有携带某个字段，也不会把旧字段冲掉。</p>
     */
    static final String UPSERT_BY_CHECKPOINT_SQL = """
            INSERT INTO agent_tool_action_resume_locator_index (
                checkpoint_id, thread_id, session_id, run_id, command_id, outbox_id,
                approval_fact_id, clarification_fact_id, tool_code, requested_policy_version,
                tenant_id, project_id, actor_id, updated_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?, ?
            )
            ON CONFLICT (checkpoint_id) DO UPDATE SET
                thread_id = COALESCE(NULLIF(EXCLUDED.thread_id, ''), agent_tool_action_resume_locator_index.thread_id),
                session_id = COALESCE(NULLIF(EXCLUDED.session_id, ''), agent_tool_action_resume_locator_index.session_id),
                run_id = COALESCE(NULLIF(EXCLUDED.run_id, ''), agent_tool_action_resume_locator_index.run_id),
                command_id = COALESCE(NULLIF(EXCLUDED.command_id, ''), agent_tool_action_resume_locator_index.command_id),
                outbox_id = COALESCE(NULLIF(EXCLUDED.outbox_id, ''), agent_tool_action_resume_locator_index.outbox_id),
                approval_fact_id = COALESCE(NULLIF(EXCLUDED.approval_fact_id, ''), agent_tool_action_resume_locator_index.approval_fact_id),
                clarification_fact_id = COALESCE(NULLIF(EXCLUDED.clarification_fact_id, ''), agent_tool_action_resume_locator_index.clarification_fact_id),
                tool_code = COALESCE(NULLIF(EXCLUDED.tool_code, ''), agent_tool_action_resume_locator_index.tool_code),
                requested_policy_version = COALESCE(NULLIF(EXCLUDED.requested_policy_version, ''), agent_tool_action_resume_locator_index.requested_policy_version),
                tenant_id = COALESCE(NULLIF(EXCLUDED.tenant_id, ''), agent_tool_action_resume_locator_index.tenant_id),
                project_id = COALESCE(NULLIF(EXCLUDED.project_id, ''), agent_tool_action_resume_locator_index.project_id),
                actor_id = COALESCE(NULLIF(EXCLUDED.actor_id, ''), agent_tool_action_resume_locator_index.actor_id),
                updated_at = COALESCE(EXCLUDED.updated_at, agent_tool_action_resume_locator_index.updated_at),
                update_time = CURRENT_TIMESTAMP
            """;

    /**
     * 按 threadId 作为冲突目标的 locator index 幂等 upsert SQL。
     *
     * <p>threadId 通常比 checkpointId 更宽，适合作为 checkpoint 缺失、外部 Agent 只保留 thread 现场、
     * 或 checkpoint 唯一键尚未建立前的恢复兜底入口。字段合并规则与 checkpoint 路径保持一致，避免两条路径
     * 对同一张表产生不同业务语义。</p>
     */
    static final String UPSERT_BY_THREAD_SQL = """
            INSERT INTO agent_tool_action_resume_locator_index (
                checkpoint_id, thread_id, session_id, run_id, command_id, outbox_id,
                approval_fact_id, clarification_fact_id, tool_code, requested_policy_version,
                tenant_id, project_id, actor_id, updated_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?, ?
            )
            ON CONFLICT (thread_id) DO UPDATE SET
                checkpoint_id = COALESCE(NULLIF(EXCLUDED.checkpoint_id, ''), agent_tool_action_resume_locator_index.checkpoint_id),
                session_id = COALESCE(NULLIF(EXCLUDED.session_id, ''), agent_tool_action_resume_locator_index.session_id),
                run_id = COALESCE(NULLIF(EXCLUDED.run_id, ''), agent_tool_action_resume_locator_index.run_id),
                command_id = COALESCE(NULLIF(EXCLUDED.command_id, ''), agent_tool_action_resume_locator_index.command_id),
                outbox_id = COALESCE(NULLIF(EXCLUDED.outbox_id, ''), agent_tool_action_resume_locator_index.outbox_id),
                approval_fact_id = COALESCE(NULLIF(EXCLUDED.approval_fact_id, ''), agent_tool_action_resume_locator_index.approval_fact_id),
                clarification_fact_id = COALESCE(NULLIF(EXCLUDED.clarification_fact_id, ''), agent_tool_action_resume_locator_index.clarification_fact_id),
                tool_code = COALESCE(NULLIF(EXCLUDED.tool_code, ''), agent_tool_action_resume_locator_index.tool_code),
                requested_policy_version = COALESCE(NULLIF(EXCLUDED.requested_policy_version, ''), agent_tool_action_resume_locator_index.requested_policy_version),
                tenant_id = COALESCE(NULLIF(EXCLUDED.tenant_id, ''), agent_tool_action_resume_locator_index.tenant_id),
                project_id = COALESCE(NULLIF(EXCLUDED.project_id, ''), agent_tool_action_resume_locator_index.project_id),
                actor_id = COALESCE(NULLIF(EXCLUDED.actor_id, ''), agent_tool_action_resume_locator_index.actor_id),
                updated_at = COALESCE(EXCLUDED.updated_at, agent_tool_action_resume_locator_index.updated_at),
                update_time = CURRENT_TIMESTAMP
            """;

    private JdbcAgentToolActionResumeLocatorIndexRecordMapper() {
    }

    /**
     * 绑定 upsert 参数。
     *
     * <p>每个字符串字段都会先裁剪到数据库列允许的最大长度。裁剪不是为了隐藏错误，而是为了保证 locator index
     * 不会因为某个上游异常长 ID 直接写库失败；真正的完整异常上下文应进入日志或受控审计详情，而不是进入这个低敏索引表。</p>
     */
    static void bindRecord(PreparedStatement statement,
                           AgentToolActionResumeLocatorIndexRecord record) throws SQLException {
        int index = 1;
        setNullableString(statement, index++, truncate(record.checkpointId(), 160));
        setNullableString(statement, index++, truncate(record.threadId(), 160));
        setNullableString(statement, index++, truncate(record.sessionId(), 128));
        setNullableString(statement, index++, truncate(record.runId(), 128));
        setNullableString(statement, index++, truncate(record.commandId(), 128));
        setNullableString(statement, index++, truncate(record.outboxId(), 128));
        setNullableString(statement, index++, truncate(record.approvalFactId(), 160));
        setNullableString(statement, index++, truncate(record.clarificationFactId(), 160));
        setNullableString(statement, index++, truncate(record.toolCode(), 160));
        setNullableString(statement, index++, truncate(record.requestedPolicyVersion(), 160));
        setNullableString(statement, index++, truncate(record.tenantId(), 128));
        setNullableString(statement, index++, truncate(record.projectId(), 128));
        setNullableString(statement, index++, truncate(record.actorId(), 128));
        setNullableTimestamp(statement, index, record.updatedAt());
    }

    /**
     * 从数据库行还原 locator 记录。
     *
     * <p>该方法只还原低敏 locator 字段。上层 {@link AgentToolActionResumeLocatorIndexService}
     * 仍会在把记录用于补齐请求前执行 tenant/project/actor/run/session/tool 可见性校验，防止跨租户或跨项目误用。</p>
     */
    static AgentToolActionResumeLocatorIndexRecord toRecord(ResultSet resultSet) throws SQLException {
        return new AgentToolActionResumeLocatorIndexRecord(
                resultSet.getString("checkpoint_id"),
                resultSet.getString("thread_id"),
                resultSet.getString("session_id"),
                resultSet.getString("run_id"),
                resultSet.getString("command_id"),
                resultSet.getString("outbox_id"),
                resultSet.getString("approval_fact_id"),
                resultSet.getString("clarification_fact_id"),
                resultSet.getString("tool_code"),
                resultSet.getString("requested_policy_version"),
                resultSet.getString("tenant_id"),
                resultSet.getString("project_id"),
                resultSet.getString("actor_id"),
                getInstant(resultSet, "updated_at")
        );
    }

    /**
     * 绑定动态查询参数。
     *
     * <p>目前查询参数只有 checkpointId/threadId/limit/count 等简单类型，但集中绑定仍然有价值：
     * 后续增加 tenantId、projectId、TTL 截止时间或诊断分页时，可以复用同一套 null 与时间类型处理规则。</p>
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

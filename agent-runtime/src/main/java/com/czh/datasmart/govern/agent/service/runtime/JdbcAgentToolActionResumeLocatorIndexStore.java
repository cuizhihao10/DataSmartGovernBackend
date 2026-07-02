/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - JdbcAgentToolActionResumeLocatorIndexStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcSqlExceptionSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * MySQL 版 checkpoint/thread 恢复 locator index 仓储。
 *
 * <p>这个 Store 是内存 locator index 的生产化替换点。它解决的是 Agent Host 恢复链路里的一个关键问题：
 * Python Runtime、LangGraph/OpenClaw checkpoint 或外部智能网关只需要记住 checkpointId/threadId，
 * Java 控制面则可以根据这些低敏入口回查 commandId、outboxId、approvalFactId、clarificationFactId、
 * toolCode 和 requestedPolicyVersion，从而继续聚合 outbox、worker receipt、permission-admin 审批事实和未来澄清事实。</p>
 *
 * <p>为什么不直接让 Python 每次都把所有字段传回来？</p>
 * <p>1. 真实商业环境里，Python Runtime 可能重启、外部 Agent 可能只持有 checkpoint/thread，不能依赖它长期保存所有 Java 侧定位符。</p>
 * <p>2. Java 控制面必须成为工具副作用前的可信事实源，而不是完全相信调用方自报 approval/outbox/receipt 字段。</p>
 * <p>3. locator index 持久化后，管理员可以排查“某个 checkpoint 为什么无法恢复”“恢复预检缺哪类事实”“策略版本是否漂移”等问题。</p>
 *
 * <p>安全边界：本表和本 Store 只保存低敏定位符，不保存 prompt、SQL、arguments、payload body、样本数据、
 * 模型输出、凭证或内部 endpoint。fact bundle 响应层也不会把 approvalFactId、clarificationFactId 或 outboxId 原文对外回显。</p>
 */
@Component
@ConditionalOnExpression(
        "T(com.czh.datasmart.govern.agent.config.AgentRuntimeStoreMode)"
                + ".isJdbcDurable('${datasmart.agent-runtime.tool-action-resume-facts.locator-index-store:memory}') "
                + "&& '${datasmart.agent-runtime.persistence.database-enabled:false}'.equalsIgnoreCase('true')"
)
public class JdbcAgentToolActionResumeLocatorIndexStore implements AgentToolActionResumeLocatorIndexStore {

    /**
     * agent-runtime 专用 JDBC 连接管理器。
     *
     * <p>这里不直接注入 Spring Boot 全局 DataSource，是为了保持 agent-runtime 控制面事实库的独立配置。
     * 只有明确开启 database-enabled 且至少一个 Store 选择 mysql 时，连接池才会创建。</p>
     */
    private final AgentRuntimeJdbcConnectionManager connectionManager;

    /**
     * 单次查询最大返回上限。
     *
     * <p>当前 findByCheckpointId/findByThreadId 只返回一条记录，但仍保留该配置用于 count/diagnostics
     * 以及后续分页诊断接口，避免运维查询无意间退化为无上限全表扫描。</p>
     */
    private final int maxQueryLimit;

    public JdbcAgentToolActionResumeLocatorIndexStore(AgentRuntimeJdbcConnectionManager connectionManager,
                                                      AgentRuntimePersistenceProperties persistenceProperties) {
        this.connectionManager = connectionManager;
        this.maxQueryLimit = Math.max(1, persistenceProperties.getJdbc().getMaxQueryLimit());
    }

    /**
     * 幂等写入或合并 locator 记录。
     *
     * <p>调用方通常是 {@link AgentToolActionResumeLocatorIndexService}：每次 fact bundle 查询开始前，
     * 服务都会观察请求中已经携带的低敏定位符，并把它们写入索引。写入采用 PostgreSQL 唯一索引和
     * ON CONFLICT，保证同一个 checkpoint/thread 多次出现时只补齐字段，不生成重复行。</p>
     *
     * <p>如果记录没有 checkpointId/threadId，或没有任何 Java 控制面 locator，则直接跳过。
     * 这能避免数据库里堆积无法用于恢复的空壳记录。</p>
     */
    @Override
    public void upsert(AgentToolActionResumeLocatorIndexRecord record) {
        if (record == null || !record.indexable()) {
            return;
        }
        try {
            executeUpsert(record, primaryUpsertSql(record));
        } catch (RuntimeException exception) {
            /*
             * PostgreSQL 的 ON CONFLICT 必须指定唯一键目标，而本索引有 checkpoint_id 与 thread_id 两个低敏恢复入口。
             * 当记录同时携带两个入口时，主路径可能按 checkpoint_id 处理，但真实已存在行却先命中了 thread_id 唯一键。
             * 这种情况下不应该把它视为系统故障，而应改用 thread_id 再做一次幂等合并，尽量还原早期 MySQL
             * ON DUPLICATE KEY UPDATE “任一唯一键冲突都可合并”的业务效果。
             */
            if (AgentRuntimeJdbcSqlExceptionSupport.isDuplicateKey(exception) && hasText(record.checkpointId())
                    && hasText(record.threadId())) {
                executeUpsert(record, JdbcAgentToolActionResumeLocatorIndexRecordMapper.UPSERT_BY_THREAD_SQL);
                return;
            }
            throw new IllegalStateException("写入 Agent 工具动作恢复 locator index 失败，checkpointId="
                    + record.checkpointId() + ", threadId=" + record.threadId(), exception);
        }
    }

    private String primaryUpsertSql(AgentToolActionResumeLocatorIndexRecord record) {
        if (hasText(record.checkpointId())) {
            return JdbcAgentToolActionResumeLocatorIndexRecordMapper.UPSERT_BY_CHECKPOINT_SQL;
        }
        return JdbcAgentToolActionResumeLocatorIndexRecordMapper.UPSERT_BY_THREAD_SQL;
    }

    private void executeUpsert(AgentToolActionResumeLocatorIndexRecord record, String sql) {
        connectionManager.executeWithConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                JdbcAgentToolActionResumeLocatorIndexRecordMapper.bindRecord(statement, record);
                statement.executeUpdate();
                return null;
            }
        });
    }

    /**
     * 按 checkpointId 精确查询 locator。
     *
     * <p>checkpointId 是恢复预检最精准的入口。即使 MySQL 表上 checkpoint_id 已经有唯一索引，
     * 这里仍然 ORDER BY update_time DESC LIMIT 1，是为了给未来灰度迁移或临时修复历史重复数据时保留稳定读取语义。</p>
     */
    @Override
    public Optional<AgentToolActionResumeLocatorIndexRecord> findByCheckpointId(String checkpointId) {
        if (!hasText(checkpointId)) {
            return Optional.empty();
        }
        String sql = "SELECT " + JdbcAgentToolActionResumeLocatorIndexRecordMapper.SELECT_COLUMNS
                + " FROM agent_tool_action_resume_locator_index"
                + " WHERE checkpoint_id = ? ORDER BY update_time DESC LIMIT ?";
        return queryOne(sql, List.of(checkpointId.trim(), Math.min(1, maxQueryLimit)),
                "按 checkpointId 查询 Agent 工具动作恢复 locator index 失败，checkpointId=" + checkpointId);
    }

    /**
     * 按 threadId 查询最近 locator。
     *
     * <p>threadId 通常覆盖一次会话或一次图运行的恢复上下文。它比 checkpointId 稍宽，因此只作为 checkpointId
     * 未命中时的兜底入口。上层服务仍会继续做 tenant/project/actor/run/session/tool 可见性校验。</p>
     */
    @Override
    public Optional<AgentToolActionResumeLocatorIndexRecord> findByThreadId(String threadId) {
        if (!hasText(threadId)) {
            return Optional.empty();
        }
        String sql = "SELECT " + JdbcAgentToolActionResumeLocatorIndexRecordMapper.SELECT_COLUMNS
                + " FROM agent_tool_action_resume_locator_index"
                + " WHERE thread_id = ? ORDER BY update_time DESC LIMIT ?";
        return queryOne(sql, List.of(threadId.trim(), Math.min(1, maxQueryLimit)),
                "按 threadId 查询 Agent 工具动作恢复 locator index 失败，threadId=" + threadId);
    }

    /**
     * 返回 locator index 当前记录总量。
     *
     * <p>该方法主要用于单元测试、诊断和未来指标化。生产环境如果表增长到百万级以上，不建议高频全表 count；
     * 更成熟的做法是通过低频采样、分区统计或单独的指标表维护总量。</p>
     */
    @Override
    public int size() {
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM agent_tool_action_resume_locator_index");
                     ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return 0;
                    }
                    long count = resultSet.getLong(1);
                    return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("统计 Agent 工具动作恢复 locator index 总量失败", exception);
        }
    }

    private Optional<AgentToolActionResumeLocatorIndexRecord> queryOne(String sql,
                                                                       List<Object> parameters,
                                                                       String errorMessage) {
        try {
            return connectionManager.executeWithConnection(connection -> queryOne(connection, sql, parameters));
        } catch (RuntimeException exception) {
            throw new IllegalStateException(errorMessage, exception);
        }
    }

    private Optional<AgentToolActionResumeLocatorIndexRecord> queryOne(Connection connection,
                                                                       String sql,
                                                                       List<Object> parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcAgentToolActionResumeLocatorIndexRecordMapper.bindParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(JdbcAgentToolActionResumeLocatorIndexRecordMapper.toRecord(resultSet));
            }
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

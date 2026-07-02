/**
 * @Author : Cui
 * @Date: 2026/06/18 00:00
 * @Description DataSmart Govern Backend - JdbcAgentToolActionClarificationFactStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * MySQL 版 Human-in-the-loop 澄清事实仓储。
 *
 * <p>该 Store 是 {@link InMemoryAgentToolActionClarificationFactStore} 的生产化替换点。
 * 它解决的是 Agent Host 恢复链路中的“用户补充信息是否已经被 Java 控制面登记并仍然可采信”的问题。
 * 对 LangGraph/OpenClaw/OpenAI Agents 风格的 pause -> clarify -> resume 来说，恢复入口不能只相信调用方自报
 * clarificationFactId，必须能在服务端 durable store 中回查事实。</p>
 *
 * <p>安全边界：本 Store 只读写低敏控制面元数据，不保存用户澄清原文、prompt、SQL、工具参数、payload body、
 * 样本数据、模型输出、凭证、token、内部 endpoint 或工具结果正文。真正是否可见仍由 evaluator 按租户、项目、
 * actor、run、session、command、tool 和 policyVersion 做 fail-closed 校验。</p>
 */
@Component
@ConditionalOnExpression(
        "T(com.czh.datasmart.govern.agent.config.AgentRuntimeStoreMode)"
                + ".isJdbcDurable('${datasmart.agent-runtime.tool-action-resume-facts.clarification-fact-store:memory}') "
                + "&& '${datasmart.agent-runtime.persistence.database-enabled:false}'.equalsIgnoreCase('true')"
)
public class JdbcAgentToolActionClarificationFactStore implements AgentToolActionClarificationFactStore {

    /**
     * agent-runtime 专用 JDBC 连接管理器。
     *
     * <p>澄清事实属于 Agent Runtime 控制面事实，不复用其他业务模块 DataSource。
     * 这样后续可以单独设置连接池、只读副本、审计库或冷热分层，而不会影响 task-management 等业务服务。</p>
     */
    private final AgentRuntimeJdbcConnectionManager connectionManager;

    /**
     * 单次查询最大返回上限。
     *
     * <p>当前 findByFactId 只返回一条记录，但继续保留 maxQueryLimit，是为了与 locator/receipt JDBC Store
     * 保持一致，并为后续管理员低敏查询、TTL 归档预览和分页诊断预留统一护栏。</p>
     */
    private final int maxQueryLimit;

    public JdbcAgentToolActionClarificationFactStore(AgentRuntimeJdbcConnectionManager connectionManager,
                                                     AgentRuntimePersistenceProperties persistenceProperties) {
        this.connectionManager = connectionManager;
        this.maxQueryLimit = Math.max(1, persistenceProperties.getJdbc().getMaxQueryLimit());
    }

    /**
     * 幂等写入或刷新澄清事实。
     *
     * <p>调用方通常是 {@link AgentToolActionClarificationFactRegistrationService}。
     * 同一个 clarificationFactId 多次登记时，PostgreSQL 唯一索引和 ON CONFLICT 会保证只有一行事实，
     * 用于承载前端重试、用户撤销、网关超时重放和长时间等待后刷新过期时间等场景。</p>
     */
    @Override
    public void upsert(AgentToolActionClarificationFactRecord record) {
        if (record == null || !record.indexable()) {
            return;
        }
        try {
            connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        JdbcAgentToolActionClarificationFactRecordMapper.UPSERT_SQL)) {
                    JdbcAgentToolActionClarificationFactRecordMapper.bindRecord(statement, record);
                    statement.executeUpdate();
                    return null;
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("写入 Agent 澄清事实低敏持久化表失败，clarificationFactId="
                    + record.clarificationFactId(), exception);
        }
    }

    /**
     * 按 factId 精确查询澄清事实。
     *
     * <p>仓储层只负责主键查询和记录还原，不做权限裁决。这样 memory/mysql 两种实现保持相同语义：
     * “找到事实”不等于“当前调用方可以采信事实”，后者必须交给 evaluator 做统一范围校验。</p>
     */
    @Override
    public Optional<AgentToolActionClarificationFactRecord> findByFactId(String clarificationFactId) {
        if (!hasText(clarificationFactId)) {
            return Optional.empty();
        }
        String sql = "SELECT " + JdbcAgentToolActionClarificationFactRecordMapper.SELECT_COLUMNS
                + " FROM agent_tool_action_clarification_fact"
                + " WHERE clarification_fact_id = ? ORDER BY update_time DESC LIMIT ?";
        try {
            return connectionManager.executeWithConnection(connection -> queryOne(
                    connection,
                    sql,
                    List.of(clarificationFactId.trim(), Math.min(1, maxQueryLimit))
            ));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("按 factId 查询 Agent 澄清事实低敏持久化表失败，clarificationFactId="
                    + clarificationFactId, exception);
        }
    }

    /**
     * 返回澄清事实表当前记录总量。
     *
     * <p>该方法主要用于测试、低频诊断和未来指标化。生产环境如果表增长很快，不建议高频全表 count；
     * 更成熟的方式是通过 Micrometer 低基数指标、分区统计或后台采样任务维护统计。</p>
     */
    @Override
    public int size() {
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM agent_tool_action_clarification_fact");
                     ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return 0;
                    }
                    long count = resultSet.getLong(1);
                    return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("统计 Agent 澄清事实低敏持久化表总量失败", exception);
        }
    }

    private Optional<AgentToolActionClarificationFactRecord> queryOne(Connection connection,
                                                                      String sql,
                                                                      List<Object> parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcAgentToolActionClarificationFactRecordMapper.bindParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(JdbcAgentToolActionClarificationFactRecordMapper.toRecord(resultSet));
            }
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

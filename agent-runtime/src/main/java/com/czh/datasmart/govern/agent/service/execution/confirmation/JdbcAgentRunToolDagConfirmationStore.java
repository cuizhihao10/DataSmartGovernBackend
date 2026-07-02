/**
 * @Author : Cui
 * @Date: 2026/06/01 19:31
 * @Description DataSmart Govern Backend - JdbcAgentRunToolDagConfirmationStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution.confirmation;

import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcSqlExceptionSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 基于 JDBC/MySQL 的 DAG selected-node 确认记录仓储。
 *
 * <p>selected-node confirmation 的业务定位是“确认事实证据层”，不是执行器、不是 dispatcher，也不是新的任务状态机。
 * 它记录某个 session/run 在某个 dry-run 指纹下确认了哪些 auditId，并关联 permission-admin policyVersion、
 * delegationEvidence、outboxId 和 commandId。服务重启、横向扩容或管理员排障时，这些证据必须能从数据库恢复，
 * 否则 selected-node outbox 即使能可靠投递，也无法解释“是谁基于哪版授权预案确认了这批副作用”。</p>
 *
 * <p>本实现只在两个条件同时满足时注册：确认记录 store= mysql，且 agent-runtime 数据库持久化开关打开。
 * 默认 memory 模式仍保持轻量可用，避免本地学习和单元测试因为没有 MySQL 而启动失败。</p>
 */
@Component
@ConditionalOnExpression(
        "T(com.czh.datasmart.govern.agent.config.AgentRuntimeStoreMode)"
                + ".isJdbcDurable('${datasmart.agent-runtime.tool-dag.confirmations.store:memory}') "
                + "&& '${datasmart.agent-runtime.persistence.database-enabled:false}'.equalsIgnoreCase('true')"
)
public class JdbcAgentRunToolDagConfirmationStore implements AgentRunToolDagConfirmationStore {

    private final AgentRuntimeJdbcConnectionManager connectionManager;
    private final ObjectMapper objectMapper;
    private final int maxQueryLimit;

    public JdbcAgentRunToolDagConfirmationStore(AgentRuntimeJdbcConnectionManager connectionManager,
                                                ObjectMapper objectMapper,
                                                AgentRuntimePersistenceProperties persistenceProperties) {
        this.connectionManager = connectionManager;
        this.objectMapper = objectMapper;
        this.maxQueryLimit = Math.max(1, persistenceProperties.getJdbc().getMaxQueryLimit());
    }

    /**
     * 幂等保存确认记录。
     *
     * <p>这里采用“直接插入 + 捕获唯一键冲突 + 回读已有记录”的方式实现幂等。
     * 这比“先查询再插入”更适合并发场景：两个网关重试请求或两个 Python agent loop 回放同一 confirmationId 时，
     * 数据库唯一索引会保证只产生一条事实记录，应用层只需要把重复插入解释为“确认已经存在”。</p>
     */
    @Override
    public AgentRunToolDagConfirmationRecord saveIfAbsent(AgentRunToolDagConfirmationRecord record) {
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        JdbcAgentRunToolDagConfirmationRecordMapper.INSERT_SQL)) {
                    JdbcAgentRunToolDagConfirmationRecordMapper.bindRecord(statement, record, objectMapper);
                    statement.executeUpdate();
                    return record;
                }
            });
        } catch (RuntimeException exception) {
            if (AgentRuntimeJdbcSqlExceptionSupport.isDuplicateKey(exception)) {
                return findByConfirmationId(record.confirmationId())
                        .orElseThrow(() -> new IllegalStateException(
                                "DAG 确认记录发生唯一键冲突但回读失败，confirmationId=" + record.confirmationId(),
                                exception
                        ));
            }
            throw new IllegalStateException("写入 DAG selected-node 确认记录到 JDBC/PostgreSQL 失败，confirmationId="
                    + record.confirmationId(), exception);
        }
    }

    /**
     * 按 confirmationId 查询单条确认事实。
     *
     * <p>confirmationId 是稳定业务 ID，不暴露数据库自增主键。审计台、补偿台和 API 响应都应使用 confirmationId，
     * 这样即使未来做冷热分表、归档库或跨区域复制，也不会把内部表主键泄漏成外部契约。</p>
     */
    @Override
    public Optional<AgentRunToolDagConfirmationRecord> findByConfirmationId(String confirmationId) {
        if (!hasText(confirmationId)) {
            return Optional.empty();
        }
        String sql = "SELECT " + JdbcAgentRunToolDagConfirmationRecordMapper.SELECT_COLUMNS
                + " FROM agent_run_tool_dag_confirmation WHERE confirmation_id = ?";
        try {
            return connectionManager.executeWithConnection(connection ->
                    queryOne(connection, sql, List.of(confirmationId)));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("查询 DAG selected-node 确认记录失败，confirmationId="
                    + confirmationId, exception);
        }
    }

    /**
     * 查询某个 run 下的确认历史。
     *
     * <p>当前接口按创建时间倒序返回最近记录，适合 run 详情页、审计时间线和问题排查。
     * 这里强制应用 maxQueryLimit，是为了避免未来某个高频自动化 run 产生大量确认记录后，
     * 诊断接口退化成无上限全表/全 run 扫描。后续管理后台需要更强查询时，应增加分页游标、tenant/project 条件和时间范围。</p>
     */
    @Override
    public List<AgentRunToolDagConfirmationRecord> listByRun(String runId, int limit) {
        if (!hasText(runId)) {
            return List.of();
        }
        String sql = "SELECT " + JdbcAgentRunToolDagConfirmationRecordMapper.SELECT_COLUMNS
                + " FROM agent_run_tool_dag_confirmation WHERE run_id = ? ORDER BY create_time DESC LIMIT ?";
        try {
            return connectionManager.executeWithConnection(connection ->
                    query(connection, sql, List.of(runId, normalizeLimit(limit))));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("查询 DAG selected-node 确认记录列表失败，runId=" + runId, exception);
        }
    }

    private Optional<AgentRunToolDagConfirmationRecord> queryOne(Connection connection,
                                                                 String sql,
                                                                 List<Object> parameters) throws SQLException {
        List<AgentRunToolDagConfirmationRecord> records = query(connection, sql, parameters);
        if (records.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(records.getFirst());
    }

    private List<AgentRunToolDagConfirmationRecord> query(Connection connection,
                                                          String sql,
                                                          List<Object> parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcAgentRunToolDagConfirmationRecordMapper.bindParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentRunToolDagConfirmationRecord> records = new ArrayList<>();
                while (resultSet.next()) {
                    records.add(JdbcAgentRunToolDagConfirmationRecordMapper.toRecord(resultSet, objectMapper));
                }
                return records;
            }
        }
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return Math.min(100, maxQueryLimit);
        }
        return Math.min(limit, maxQueryLimit);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

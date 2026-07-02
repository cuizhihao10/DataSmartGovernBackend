/**
 * @Author : Cui
 * @Date: 2026/06/18 00:00
 * @Description DataSmart Govern Backend - JdbcAgentToolActionWorkerReceiptIndexStore.java
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
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL/JDBC 版 worker/dry-run receipt 低敏索引仓储。
 *
 * <p>该 Store 是内存版 {@link InMemoryAgentToolActionWorkerReceiptIndexStore} 的生产化替换点。
 * 它解决的是 Agent 恢复链路里的“worker receipt 是否真实存在、属于哪个租户/项目/运行、最新低敏结果是什么”的问题。
 * 对 Codex/Claude Code/LangGraph/OpenAI Agents 类 Agent Host 来说，恢复执行前不能只相信调用方自报字段，
 * 也不能每次扫描通用 timeline 热窗口；宿主控制面需要一个可持久化、可索引、可审计的 receipt fact。</p>
 *
 * <p>安全边界：本 Store 只读写低敏机器字段，不保存 receipt message、payload、prompt、SQL、工具参数、
 * 样本数据、模型输出、凭证、token、内部 endpoint 或工具结果正文。查询时也必须把 tenant/project/actor/run/session
 * 和 authorizedProjectIds 下沉到 SQL 条件中，不能先把全量 command 记录取回 JVM 后再过滤。</p>
 */
@Component
@ConditionalOnExpression(
        "T(com.czh.datasmart.govern.agent.config.AgentRuntimeStoreMode)"
                + ".isJdbcDurable('${datasmart.agent-runtime.tool-action-resume-facts.worker-receipt-index-store:memory}') "
                + "&& '${datasmart.agent-runtime.persistence.database-enabled:false}'.equalsIgnoreCase('true')"
)
public class JdbcAgentToolActionWorkerReceiptIndexStore implements AgentToolActionWorkerReceiptIndexStore {

    /**
     * agent-runtime 专用 JDBC 连接管理器。
     *
     * <p>这里复用已有连接管理器，而不是创建新的 DataSource，是为了让 agent-runtime 控制面事实库保持统一配置：
     * 只有明确开启 database-enabled 且某个控制面 Store 选择 JDBC durable 模式时，连接池才会创建。</p>
     */
    private final AgentRuntimeJdbcConnectionManager connectionManager;

    /**
     * 数据库查询硬上限。
     *
     * <p>每次 fact bundle 查询还会使用 request/query 自带 limit，但数据库层仍保留全局最大值。
     * 这样即使上层调用者传入异常大 limit，也不会让 PostgreSQL 返回大量历史 receipt 拖慢恢复预检接口。</p>
     */
    private final int maxQueryLimit;

    public JdbcAgentToolActionWorkerReceiptIndexStore(AgentRuntimeJdbcConnectionManager connectionManager,
                                                      AgentRuntimePersistenceProperties persistenceProperties) {
        this.connectionManager = connectionManager;
        this.maxQueryLimit = Math.max(1, persistenceProperties.getJdbc().getMaxQueryLimit());
    }

    /**
     * 幂等写入或刷新 worker receipt 索引。
     *
     * <p>调用方通常是 {@link AgentToolActionWorkerReceiptIndexService}：HTTP receipt 回写、Kafka runtime event consumer
     * 或 fact bundle fallback 看到 receipt projection 后，都会把白名单字段物化到这里。PostgreSQL 唯一索引负责并发幂等，
     * 同一个 eventIdentityKey 多次出现不会生成多条 receipt。</p>
     *
     * @return true 表示本次确认为首次插入；false 表示记录无效、或 PostgreSQL 唯一索引命中后走了重复刷新路径。
     */
    @Override
    public boolean upsert(AgentToolActionWorkerReceiptIndexRecord record) {
        if (record == null || !record.indexable()) {
            return false;
        }
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        JdbcAgentToolActionWorkerReceiptIndexRecordMapper.INSERT_SQL)) {
                    JdbcAgentToolActionWorkerReceiptIndexRecordMapper.bindInsertRecord(statement, record);
                    int affectedRows = statement.executeUpdate();
                    if (affectedRows > 0) {
                        return true;
                    }
                }
                /*
                 * PostgreSQL 的 ON CONFLICT DO UPDATE 不能像 MySQL 那样可靠通过影响行数判断首次插入还是重复更新。
                 * 因此这里显式拆为“先 INSERT DO NOTHING，再 UPDATE 刷新低敏字段”：
                 * - INSERT 返回 1：说明本次真的创建了新 receipt fact，上层可用 true 统计首次物化。
                 * - INSERT 返回 0：说明唯一键已经存在，只刷新低敏字段并返回 false，避免恢复链路把重复事件当成新事实。
                 * 这对 Agent durable loop 很重要，因为 worker receipt 常常来自 HTTP 重试、Kafka 重放、补偿扫描三条路径；
                 * 若重复事件被误判为首次插入，会让诊断指标、审计解释和幂等恢复策略都变得不可信。
                 */
                try (PreparedStatement statement = connection.prepareStatement(
                        JdbcAgentToolActionWorkerReceiptIndexRecordMapper.UPDATE_SQL)) {
                    JdbcAgentToolActionWorkerReceiptIndexRecordMapper.bindUpdateRecord(statement, record);
                    statement.executeUpdate();
                    return false;
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("写入 Agent worker receipt 低敏索引失败，commandId="
                    + record.commandId() + ", eventIdentityKey=" + record.eventIdentityKey(), exception);
        }
    }

    /**
     * 按 commandId 和访问范围查询 worker receipt。
     *
     * <p>commandId 是核心入口，但绝不是唯一安全条件。真实商业化环境中，commandId 可能来自外部 Agent、
     * Python Runtime、前端确认页或恢复链接，因此必须同时叠加 tenant/project/actor/run/session/toolCode
     * 和 authorizedProjectIds。这里把过滤条件全部下沉到 SQL，是为了避免越权记录先被取回 JVM 形成观察窗口。</p>
     */
    @Override
    public List<AgentToolActionWorkerReceiptIndexRecord> queryByCommandId(
            AgentToolActionWorkerReceiptIndexQuery query) {
        if (query == null || !hasText(query.commandId())) {
            return List.of();
        }
        if (query.authorizedProjectIds() != null && query.authorizedProjectIds().isEmpty()) {
            return List.of();
        }
        SqlQuery sqlQuery = buildQuery(query);
        try {
            return connectionManager.executeWithConnection(connection -> query(connection, sqlQuery));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("按 commandId 查询 Agent worker receipt 低敏索引失败，commandId="
                    + query.commandId(), exception);
        }
    }

    /**
     * 返回 worker receipt index 当前记录总量。
     *
     * <p>该方法主要服务测试、诊断和未来 Micrometer 指标。生产环境如果表增长很快，不建议高频全表 count；
     * 更成熟的做法是按租户/时间分区维护低频统计或通过指标采样上报。</p>
     */
    @Override
    public int size() {
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM agent_tool_action_worker_receipt_index");
                     ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return 0;
                    }
                    long count = resultSet.getLong(1);
                    return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("统计 Agent worker receipt 低敏索引总量失败", exception);
        }
    }

    private List<AgentToolActionWorkerReceiptIndexRecord> query(Connection connection,
                                                                SqlQuery sqlQuery) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sqlQuery.sql())) {
            JdbcAgentToolActionWorkerReceiptIndexRecordMapper.bindParameters(statement, sqlQuery.parameters());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentToolActionWorkerReceiptIndexRecord> records = new ArrayList<>();
                while (resultSet.next()) {
                    records.add(JdbcAgentToolActionWorkerReceiptIndexRecordMapper.toRecord(resultSet));
                }
                return records;
            }
        }
    }

    private SqlQuery buildQuery(AgentToolActionWorkerReceiptIndexQuery query) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(JdbcAgentToolActionWorkerReceiptIndexRecordMapper.SELECT_COLUMNS)
                .append(" FROM agent_tool_action_worker_receipt_index WHERE command_id = ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(query.commandId());
        appendProjectScope(sql, parameters, query.authorizedProjectIds());
        appendEquals(sql, parameters, "tenant_id", query.tenantId());
        appendEquals(sql, parameters, "project_id", query.projectId());
        appendEquals(sql, parameters, "actor_id", query.actorId());
        appendEquals(sql, parameters, "run_id", query.runId());
        appendEquals(sql, parameters, "session_id", query.sessionId());
        appendToolCodeCompatibility(sql, parameters, query.toolCode());
        sql.append(" ORDER BY COALESCE(replay_sequence, -1), consumed_at LIMIT ?");
        parameters.add(Math.min(query.normalizedLimit(), maxQueryLimit));
        return new SqlQuery(sql.toString(), List.copyOf(parameters));
    }

    private void appendProjectScope(StringBuilder sql, List<Object> parameters, List<String> authorizedProjectIds) {
        if (authorizedProjectIds == null) {
            return;
        }
        sql.append(" AND project_id IN (");
        for (int index = 0; index < authorizedProjectIds.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append("?");
            parameters.add(authorizedProjectIds.get(index));
        }
        sql.append(")");
    }

    private void appendEquals(StringBuilder sql, List<Object> parameters, String column, String value) {
        if (!hasText(value)) {
            return;
        }
        sql.append(" AND ").append(column).append(" = ?");
        parameters.add(value);
    }

    private void appendToolCodeCompatibility(StringBuilder sql, List<Object> parameters, String toolCode) {
        if (!hasText(toolCode)) {
            return;
        }
        /*
         * 历史 receipt 可能没有 toolCode。为了兼容已产生的低敏事件，查询侧暂时允许 tool_code 为空的旧记录命中；
         * 等 JDBC durable index 运行稳定并完成历史补物化后，可以把这里收紧为 tool_code = ?。
         */
        sql.append(" AND (tool_code = ? OR tool_code IS NULL OR tool_code = '')");
        parameters.add(toolCode);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 动态 SQL 与参数列表。
     *
     * <p>把它建成一个小 record，是为了让 SQL 拼接和 JDBC 执行之间的边界更明确。
     * Store 主流程读起来像“构造查询 -> 执行查询”，而不是把参数计数细节散落在业务方法里。</p>
     */
    private record SqlQuery(String sql, List<Object> parameters) {
    }
}

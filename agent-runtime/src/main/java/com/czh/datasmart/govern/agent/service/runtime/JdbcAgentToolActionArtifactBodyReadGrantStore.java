/**
 * @Author : Cui
 * @Date: 2026/06/26 21:24
 * @Description DataSmart Govern Backend - JdbcAgentToolActionArtifactBodyReadGrantStore.java
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
import java.util.Optional;

/**
 * MySQL 版 artifact 正文读取授权事实仓储。
 *
 * <p>该 Store 是 {@link InMemoryAgentToolActionArtifactBodyReadGrantStore} 的生产化替换点，用于解决三个关键问题：
 * 1. JVM 重启后 grant fact 仍可回查；
 * 2. agent-runtime 多实例部署时可以共享授权事实；
 * 3. 管理员和审计员可以按低敏定位符查询、撤销和解释历史 grant。</p>
 *
 * <p>安全边界：本 Store 只保存低敏控制面事实，不保存 artifact 正文、sample bytes、stdout/stderr、bucket/key、
 * 签名 URL、bearer token、prompt、SQL、工具参数、模型输出、凭据或内部 endpoint。查询时也必须将 tenant/project/actor
 * 和 authorizedProjectIds 下沉到 SQL 条件，不能先把跨项目记录取回应用内存再过滤。</p>
 */
@Component
@ConditionalOnExpression(
        "T(com.czh.datasmart.govern.agent.config.AgentRuntimeStoreMode)"
                + ".isJdbcDurable('${datasmart.agent-runtime.artifact-body-read-grants.store:memory}') "
                + "&& '${datasmart.agent-runtime.persistence.database-enabled:false}'.equalsIgnoreCase('true')"
)
public class JdbcAgentToolActionArtifactBodyReadGrantStore
        implements AgentToolActionArtifactBodyReadGrantStore {

    /**
     * agent-runtime 专用 JDBC 连接管理器。
     *
     * <p>artifact grant fact 属于 Agent Runtime 控制面事实，不复用 task-management 或 datasource-management 的数据源。
     * 这样后续可以按审计事实库的读写压力单独调连接池、分库、归档和备份策略。</p>
     */
    private final AgentRuntimeJdbcConnectionManager connectionManager;

    /**
     * 数据库查询硬上限。
     *
     * <p>管理查询还会接收 limit 参数，但数据库 Store 必须保留自己的全局上限，防止调用方传入异常大 limit
     * 导致一次扫描大量历史 grant fact。真正审计报表后续应升级为分页游标和导出审批，而不是放开无限列表。</p>
     */
    private final int maxQueryLimit;

    public JdbcAgentToolActionArtifactBodyReadGrantStore(
            AgentRuntimeJdbcConnectionManager connectionManager,
            AgentRuntimePersistenceProperties persistenceProperties) {
        this.connectionManager = connectionManager;
        this.maxQueryLimit = Math.max(1, persistenceProperties.getJdbc().getMaxQueryLimit());
    }

    /**
     * 幂等保存 grant fact。
     *
     * <p>调用方通常是 {@link AgentToolActionArtifactBodyReadGrantRecordService}。同一个 grantDecisionReference
     * 多次写入时，PostgreSQL 唯一键和 ON CONFLICT 会保证只有一条事实，用于承载 HTTP 重试、
     * 运行事件补物化和未来 TTL 状态刷新。</p>
     */
    @Override
    public void save(AgentToolActionArtifactBodyReadGrantRecord record) {
        if (record == null || !record.indexable()) {
            return;
        }
        try {
            connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        JdbcAgentToolActionArtifactBodyReadGrantRecordMapper.UPSERT_SQL)) {
                    JdbcAgentToolActionArtifactBodyReadGrantRecordMapper.bindRecord(statement, record);
                    statement.executeUpdate();
                    return null;
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("写入 artifact 正文读取 grant fact 失败，grantDecisionReference="
                    + record.grantDecisionReference(), exception);
        }
    }

    /**
     * 按低敏 grant 引用精确查询事实。
     *
     * <p>这是 final-check/probe 的关键回查入口。找不到记录时，上层必须 fail-closed；
     * 找到记录也只说明“服务端曾经签发过该 grant”，仍需上层校验未过期、未撤销和上下文一致。</p>
     */
    @Override
    public Optional<AgentToolActionArtifactBodyReadGrantRecord> findByReference(String grantDecisionReference) {
        if (!hasText(grantDecisionReference)) {
            return Optional.empty();
        }
        String sql = "SELECT " + JdbcAgentToolActionArtifactBodyReadGrantRecordMapper.SELECT_COLUMNS
                + " FROM agent_artifact_body_read_grant_fact"
                + " WHERE grant_decision_reference = ? ORDER BY issued_at DESC LIMIT ?";
        try {
            return connectionManager.executeWithConnection(connection -> queryOne(
                    connection,
                    sql,
                    List.of(grantDecisionReference.trim(), Math.min(1, maxQueryLimit))
            ));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("按 grantDecisionReference 查询 artifact 正文读取 grant fact 失败，grantDecisionReference="
                    + grantDecisionReference, exception);
        }
    }

    /**
     * 按低敏条件查询 grant fact。
     *
     * <p>本方法面向管理台和审计台，不面向 artifact 正文下载。为了避免变成全表浏览，本批次要求查询至少携带
     * grantDecisionReference 或 commandId。tenant/project/actor/run/session/tool/status 只作为进一步收口条件。</p>
     */
    @Override
    public List<AgentToolActionArtifactBodyReadGrantRecord> query(
            AgentToolActionArtifactBodyReadGrantQuery query,
            int limit) {
        if (query == null || !query.hasRequiredSelector()) {
            return List.of();
        }
        if (query.authorizedProjectIds() != null && query.authorizedProjectIds().isEmpty()) {
            return List.of();
        }
        SqlQuery sqlQuery = buildQuery(query, limit);
        try {
            return connectionManager.executeWithConnection(connection -> query(connection, sqlQuery));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("查询 artifact 正文读取 grant fact 失败，grantDecisionReference="
                    + query.grantDecisionReference() + ", commandId=" + query.commandId(), exception);
        }
    }

    /**
     * 撤销 grant fact。
     *
     * <p>撤销不会删除记录，而是把状态改为 REVOKED 并写入低敏操作者与原因码。
     * 保留记录的原因是审计解释：当用户问“为什么一个还没过期的 grant 被拒绝”时，系统需要能说明它已被撤销。</p>
     */
    @Override
    public Optional<AgentToolActionArtifactBodyReadGrantRecord> revoke(
            String grantDecisionReference,
            String operatorId,
            String reasonCode,
            long revokedAtEpochMs) {
        if (!hasText(grantDecisionReference)) {
            return Optional.empty();
        }
        try {
            return connectionManager.executeInTransaction(connection -> {
                Optional<AgentToolActionArtifactBodyReadGrantRecord> existing = queryOne(
                        connection,
                        "SELECT " + JdbcAgentToolActionArtifactBodyReadGrantRecordMapper.SELECT_COLUMNS
                                + " FROM agent_artifact_body_read_grant_fact"
                                + " WHERE grant_decision_reference = ? LIMIT ?",
                        List.of(grantDecisionReference.trim(), Math.min(1, maxQueryLimit))
                );
                if (existing.isEmpty()) {
                    return Optional.empty();
                }
                AgentToolActionArtifactBodyReadGrantRecord revoked =
                        existing.get().revoke(operatorId, reasonCode, revokedAtEpochMs);
                try (PreparedStatement statement = connection.prepareStatement(
                        JdbcAgentToolActionArtifactBodyReadGrantRecordMapper.UPSERT_SQL)) {
                    JdbcAgentToolActionArtifactBodyReadGrantRecordMapper.bindRecord(statement, revoked);
                    statement.executeUpdate();
                }
                return Optional.of(revoked);
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("撤销 artifact 正文读取 grant fact 失败，grantDecisionReference="
                    + grantDecisionReference, exception);
        }
    }

    /**
     * 返回 grant fact 表当前记录总量。
     *
     * <p>该方法主要服务测试、低频诊断和未来指标。生产环境表增长后，不建议管理台高频调用全表 count；
     * 更成熟的实现应按租户、时间分区或后台采样任务维护统计。</p>
     */
    @Override
    public int size() {
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM agent_artifact_body_read_grant_fact");
                     ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return 0;
                    }
                    long count = resultSet.getLong(1);
                    return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("统计 artifact 正文读取 grant fact 总量失败", exception);
        }
    }

    private List<AgentToolActionArtifactBodyReadGrantRecord> query(Connection connection,
                                                                   SqlQuery sqlQuery) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sqlQuery.sql())) {
            JdbcAgentToolActionArtifactBodyReadGrantRecordMapper.bindParameters(statement, sqlQuery.parameters());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentToolActionArtifactBodyReadGrantRecord> records = new ArrayList<>();
                while (resultSet.next()) {
                    records.add(JdbcAgentToolActionArtifactBodyReadGrantRecordMapper.toRecord(resultSet));
                }
                return records;
            }
        }
    }

    private Optional<AgentToolActionArtifactBodyReadGrantRecord> queryOne(Connection connection,
                                                                          String sql,
                                                                          List<Object> parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            JdbcAgentToolActionArtifactBodyReadGrantRecordMapper.bindParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(JdbcAgentToolActionArtifactBodyReadGrantRecordMapper.toRecord(resultSet));
            }
        }
    }

    private SqlQuery buildQuery(AgentToolActionArtifactBodyReadGrantQuery query, int requestedLimit) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(JdbcAgentToolActionArtifactBodyReadGrantRecordMapper.SELECT_COLUMNS)
                .append(" FROM agent_artifact_body_read_grant_fact WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();
        appendEquals(sql, parameters, "grant_decision_reference", query.grantDecisionReference());
        appendEquals(sql, parameters, "command_id", query.commandId());
        appendEquals(sql, parameters, "artifact_reference", query.artifactReference());
        appendEquals(sql, parameters, "tenant_id", query.tenantId());
        appendProjectScope(sql, parameters, query.authorizedProjectIds());
        appendEquals(sql, parameters, "project_id", query.projectId());
        appendEquals(sql, parameters, "actor_id", query.actorId());
        appendEquals(sql, parameters, "run_id", query.runId());
        appendEquals(sql, parameters, "session_id", query.sessionId());
        appendEquals(sql, parameters, "tool_code", query.toolCode());
        appendEquals(sql, parameters, "status", query.status());
        sql.append(" ORDER BY issued_at DESC, grant_decision_reference DESC LIMIT ?");
        parameters.add(Math.min(Math.min(query.normalizedLimit(), Math.max(1, requestedLimit)), maxQueryLimit));
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
        parameters.add(value.trim());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 动态 SQL 与参数列表。
     *
     * <p>把 SQL 和参数封装为一个小 record，可以让 build/query 两个阶段边界清晰：
     * build 只负责安全拼接和参数顺序，query 只负责 JDBC 执行和 ResultSet 还原。</p>
     */
    private record SqlQuery(String sql, List<Object> parameters) {
    }
}

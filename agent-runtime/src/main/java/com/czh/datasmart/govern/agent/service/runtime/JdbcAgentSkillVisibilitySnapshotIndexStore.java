/**
 * @Author : Cui
 * @Date: 2026/06/04 23:18
 * @Description DataSmart Govern Backend - JdbcAgentSkillVisibilitySnapshotIndexStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcSqlExceptionSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 版 Skill 可见性快照专用索引仓储。
 *
 * <p>它承接 {@link AgentSkillVisibilitySnapshotIndexStore} 端口，把 Python Runtime 发布的
 * {@code skill_visibility_snapshot_recorded} 低敏事实从 JVM 内存窗口升级为数据库可恢复事实。
 * 这一步的产品价值非常明确：前端治理卡片、运营审计、Skill Marketplace 统计、Manifest 指纹灰度排查
 * 不再因为 agent-runtime 服务重启、多实例切换或通用 projection 热窗口裁剪而丢失。</p>
 *
 * <p>边界也必须同样明确：</p>
 * <p>1. 本 Store 只保存 Skill 可见性快照，不是通用 runtime event 历史库；</p>
 * <p>2. 不保存 prompt、SQL、工具参数、连接密钥、样本数据、长期记忆正文或完整权限清单；</p>
 * <p>3. 查询继续复用 {@link AgentRuntimeEventProjectionQuery}，是为了保持 controller、权限收口、
 * replaySequence 游标和内存实现的契约一致，而不是鼓励调用方按任意 eventType 扫表。</p>
 *
 * <p>启用条件采用三段式保护：</p>
 * <p>1. {@code skill-visibility-index.enabled=true}：业务上确实启用专用索引；</p>
 * <p>2. {@code skill-visibility-index.store=mysql}：明确选择 MySQL 实现；</p>
 * <p>3. {@code persistence.database-enabled=true}：部署方已经确认数据库链路、表结构、账号和运维策略准备好。</p>
 */
@Component
@ConditionalOnExpression(
        "'${datasmart.agent-runtime.runtime-events.skill-visibility-index.enabled:true}'.equalsIgnoreCase('true') "
                + "&& T(com.czh.datasmart.govern.agent.config.AgentRuntimeStoreMode)"
                + ".isJdbcDurable('${datasmart.agent-runtime.runtime-events.skill-visibility-index.store:memory}') "
                + "&& '${datasmart.agent-runtime.persistence.database-enabled:false}'.equalsIgnoreCase('true')"
)
public class JdbcAgentSkillVisibilitySnapshotIndexStore implements AgentSkillVisibilitySnapshotIndexStore {

    private final AgentRuntimeJdbcConnectionManager connectionManager;
    private final ObjectMapper objectMapper;
    private final int maxQueryLimit;

    public JdbcAgentSkillVisibilitySnapshotIndexStore(AgentRuntimeJdbcConnectionManager connectionManager,
                                                      ObjectMapper objectMapper,
                                                      AgentRuntimePersistenceProperties persistenceProperties) {
        this.connectionManager = connectionManager;
        this.objectMapper = objectMapper;
        this.maxQueryLimit = Math.max(1, persistenceProperties.getJdbc().getMaxQueryLimit());
    }

    /**
     * 幂等写入一条 Skill 可见性快照。
     *
     * <p>调用方通常是 {@link AgentRuntimeEventConsumerService}：它会先把 runtime event 写入通用 projection，
     * 再把首次接收成功的 Skill 快照物化到本索引。数据库层通过 {@code identity_key} 唯一索引兜底，处理 Kafka
     * 重放、consumer rebalance、ack 丢失和人工补偿导致的重复消息。</p>
     *
     * @param record 通用 projection 中已经解析好的事件记录。
     * @return true 表示首次写入；false 表示非 Skill 快照或重复快照。
     */
    @Override
    public boolean append(AgentRuntimeEventProjectionRecord record) {
        if (record == null || !AgentSkillVisibilitySnapshotProjectionService.SKILL_VISIBILITY_EVENT_TYPE.equals(record.eventType())) {
            return false;
        }
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        JdbcAgentSkillVisibilitySnapshotIndexRecordMapper.INSERT_SQL)) {
                    JdbcAgentSkillVisibilitySnapshotIndexRecordMapper.bindRecord(statement, record, objectMapper);
                    return statement.executeUpdate() > 0;
                }
            });
        } catch (RuntimeException exception) {
            if (AgentRuntimeJdbcSqlExceptionSupport.isDuplicateKey(exception)) {
                return false;
            }
            throw new IllegalStateException("写入 Skill 可见性快照 MySQL 索引失败，identityKey="
                    + record.identityKey(), exception);
        }
    }

    /**
     * 按控制面查询条件读取 Skill 可见性快照。
     *
     * <p>权限语义与内存实现保持一致，尤其是 PROJECT 数据范围：</p>
     * <p>1. {@code authorizedProjectIds == null} 表示没有额外项目集合约束，例如租户级或平台级视角；</p>
     * <p>2. 空集合表示调用方处于项目级视角但没有任何授权项目，必须直接返回空结果；</p>
     * <p>3. 非空集合会转化为 {@code project_id IN (...)}，数据库层不允许退化为全表可见。</p>
     */
    @Override
    public List<AgentRuntimeEventProjectionRecord> query(AgentRuntimeEventProjectionQuery query) {
        List<String> authorizedProjectIds = query.normalizedAuthorizedProjectIds();
        if (authorizedProjectIds != null && authorizedProjectIds.isEmpty()) {
            return List.of();
        }
        QueryPlan queryPlan = buildQuery(query, authorizedProjectIds);
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(queryPlan.sql())) {
                    JdbcAgentSkillVisibilitySnapshotIndexRecordMapper.bindParameters(statement, queryPlan.parameters());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        List<AgentRuntimeEventProjectionRecord> records = new ArrayList<>();
                        while (resultSet.next()) {
                            records.add(JdbcAgentSkillVisibilitySnapshotIndexRecordMapper.toRecord(resultSet, objectMapper));
                        }
                        return records;
                    }
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("查询 Skill 可见性快照 MySQL 索引失败，runId="
                    + query.runId() + ", sessionId=" + query.sessionId(), exception);
        }
    }

    /**
     * 返回当前 MySQL 索引中的快照总量。
     *
     * <p>该方法主要服务诊断和测试。生产环境未来如果把该值接入 Prometheus，应注意它是全表 count，
     * 不适合在超大表上高频调用；更成熟的做法是使用低频采样、分区统计或物化指标。</p>
     */
    @Override
    public int size() {
        try {
            return connectionManager.executeWithConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM agent_skill_visibility_snapshot_index")) {
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            return 0;
                        }
                        long count = resultSet.getLong(1);
                        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
                    }
                }
            });
        } catch (RuntimeException exception) {
            throw new IllegalStateException("统计 Skill 可见性快照 MySQL 索引总量失败", exception);
        }
    }

    private QueryPlan buildQuery(AgentRuntimeEventProjectionQuery query, List<String> authorizedProjectIds) {
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(JdbcAgentSkillVisibilitySnapshotIndexRecordMapper.SELECT_COLUMNS)
                .append(" FROM agent_skill_visibility_snapshot_index WHERE event_type = ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(AgentSkillVisibilitySnapshotProjectionService.SKILL_VISIBILITY_EVENT_TYPE);
        appendEquals(sql, parameters, "tenant_id", query.tenantId());
        appendEquals(sql, parameters, "project_id", query.projectId());
        appendEquals(sql, parameters, "actor_id", query.actorId());
        appendEquals(sql, parameters, "request_id", query.requestId());
        appendEquals(sql, parameters, "run_id", query.runId());
        appendEquals(sql, parameters, "session_id", query.sessionId());
        appendEquals(sql, parameters, "severity", query.severity());
        appendAuthorizedProjects(sql, parameters, authorizedProjectIds);
        sql.append(" AND replay_sequence IS NOT NULL AND replay_sequence > ?");
        parameters.add(query.normalizedAfterSequence());
        sql.append(" ORDER BY replay_sequence ASC, id ASC LIMIT ?");
        parameters.add(Math.min(query.normalizedLimit(), maxQueryLimit));
        return new QueryPlan(sql.toString(), parameters);
    }

    private void appendEquals(StringBuilder sql, List<Object> parameters, String column, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sql.append(" AND ").append(column).append(" = ?");
        parameters.add(value.trim());
    }

    private void appendAuthorizedProjects(StringBuilder sql,
                                          List<Object> parameters,
                                          List<String> authorizedProjectIds) {
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

    private record QueryPlan(String sql, List<Object> parameters) {
    }
}

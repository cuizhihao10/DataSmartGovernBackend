/**
 * @Author : Cui
 * @Date: 2026/08/05 00:00
 * @Description DataSmart Govern Backend - SpecialistTurnFactJdbcStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.specialist;

import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL 版专业 Agent turn 事实仓储。
 *
 * <p>该类只负责把已经通过 {@link SpecialistTurnFact} 低敏校验的事实写入
 * {@code agent_runtime.agent_specialist_turn_fact}。它不接收 prompt、模型输出正文、SQL、工具参数、凭据或样本数据，
 * 也不尝试从这些字段中提取摘要。这样可以把“什么允许落库”的规则固定在领域对象，而不是让每个 JDBC 调用方各自解释。</p>
 *
 * <p>Store 额外做两件重要的可靠性工作：</p>
 * <ol>
 *     <li>写入前锁定相同幂等键或相同 turn 身份，防止重试把一个事实悄悄改成另一个用户、租户、应用、项目或 Agent 的事实；</li>
 *     <li>查询时把租户、应用、项目和普通用户 actor 条件下沉到 SQL，减少无边界扫描；Service 层仍会对返回结果做第二次对象归属校验。</li>
 * </ol>
 *
 * <p>该 Bean 只在已有 Agent Runtime JDBC 连接管理器存在时注册。memory 模式下没有连接池，也就不会因为本事实表的
 * 仓储类而强制本地开发环境启动 PostgreSQL。</p>
 */
@Component
@ConditionalOnBean(AgentRuntimeJdbcConnectionManager.class)
public class SpecialistTurnFactJdbcStore implements SpecialistTurnFactStore {

    /** 使用显式 schema，避免 JDBC 连接的 search_path 被外部配置改变后写错业务库。 */
    private static final String TABLE_NAME = "agent_runtime.agent_specialist_turn_fact";

    /** Jackson 列表类型，用于把低敏引用数组安全地映射为 JSONB。 */
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    /**
     * 统一的低敏列清单。
     *
     * <p>id 是数据库内部排序字段，不属于对外事实模型，因此不返回给领域对象；其余列必须和
     * {@link SpecialistTurnFact} 的字段顺序保持一致，避免新增字段时出现 INSERT/SELECT 错位。</p>
     */
    static final String SELECT_COLUMNS = """
            user_id, tenant_id, application_id, project_id, session_id, run_id, turn_id, idempotency_key,
            agent_id, role, delegation_id, status, low_sensitive_summary,
            model_invocation_id, model_name, tool_activity_summary_refs, evidence_refs,
            duration_millis, started_at, finished_at, created_at, updated_at
            """;

    /**
     * 幂等 upsert SQL。
     *
     * <p>ON CONFLICT 只针对 idempotency_key；WHERE 子句再次比较不可变身份字段。即使并发请求在
     * “先查后写”之间同时到达，身份不同的请求也不会覆盖原事实，而会返回 0 行并由 Java 层转成明确冲突。</p>
     */
    static final String UPSERT_SQL = """
            INSERT INTO %s AS stored_fact (
                user_id, tenant_id, application_id, project_id, session_id, run_id, turn_id, idempotency_key,
                agent_id, role, delegation_id, status, low_sensitive_summary,
                model_invocation_id, model_name, tool_activity_summary_refs, evidence_refs,
                duration_millis, started_at, finished_at, created_at, updated_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, CAST(? AS jsonb), CAST(? AS jsonb),
                ?, ?, ?, ?, ?
            )
            ON CONFLICT (idempotency_key) DO UPDATE SET
                status = EXCLUDED.status,
                low_sensitive_summary = EXCLUDED.low_sensitive_summary,
                model_invocation_id = EXCLUDED.model_invocation_id,
                model_name = EXCLUDED.model_name,
                tool_activity_summary_refs = EXCLUDED.tool_activity_summary_refs,
                evidence_refs = EXCLUDED.evidence_refs,
                duration_millis = EXCLUDED.duration_millis,
                started_at = EXCLUDED.started_at,
                finished_at = EXCLUDED.finished_at,
                updated_at = GREATEST(stored_fact.updated_at, EXCLUDED.updated_at)
            WHERE stored_fact.user_id = EXCLUDED.user_id
              AND stored_fact.tenant_id = EXCLUDED.tenant_id
              AND stored_fact.application_id = EXCLUDED.application_id
              AND stored_fact.project_id = EXCLUDED.project_id
              AND stored_fact.session_id = EXCLUDED.session_id
              AND stored_fact.run_id = EXCLUDED.run_id
              AND stored_fact.turn_id = EXCLUDED.turn_id
              AND stored_fact.agent_id = EXCLUDED.agent_id
              AND stored_fact.role = EXCLUDED.role
              AND stored_fact.delegation_id IS NOT DISTINCT FROM EXCLUDED.delegation_id
            RETURNING %s
            """.formatted(
            TABLE_NAME,
            SELECT_COLUMNS
    );

    /** 连接管理器负责连接生命周期、事务提交和失败回滚。 */
    private final AgentRuntimeJdbcConnectionManager connectionManager;

    /**
     * JSON 序列化器。
     *
     * <p>引用数组不是自由 JSON 文档，而是由领域对象先校验过的字符串列表。这里使用 ObjectMapper 而不是手工拼接，
     * 防止引用中出现引号或反斜杠时生成非法 JSON。</p>
     */
    private final ObjectMapper objectMapper;

    /** 数据库实现自己的硬上限，避免调用方通过 limit 触发超大历史查询。 */
    private final int maxQueryLimit;

    /**
     * 创建 PostgreSQL 事实仓储。
     *
     * @param connectionManager Agent Runtime 专用 JDBC 连接管理器
     * @param objectMapper Spring 管理的 Jackson 序列化器
     * @param persistenceProperties 用于读取查询上限的持久化配置
     */
    public SpecialistTurnFactJdbcStore(AgentRuntimeJdbcConnectionManager connectionManager,
                                       ObjectMapper objectMapper,
                                       AgentRuntimePersistenceProperties persistenceProperties) {
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager 不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        Objects.requireNonNull(persistenceProperties, "persistenceProperties 不能为空");
        this.maxQueryLimit = Math.max(1, persistenceProperties.getJdbc().getMaxQueryLimit());
    }

    /**
     * 以事务方式保存一条事实。
     *
     * <p>同一幂等键的重复写入只更新可变事实状态；不可变身份一旦发生变化就拒绝。除了幂等键外，数据库还对
     * {@code session_id + run_id + turn_id} 建立唯一约束，因此同一 turn 不能通过更换幂等键制造第二条事实。</p>
     *
     * @param fact 已经完成低敏字段校验的 turn 事实
     * @return PostgreSQL 最终保存的低敏事实
     */
    @Override
    public SpecialistTurnFact save(SpecialistTurnFact fact) {
        if (fact == null) {
            throw new IllegalArgumentException("专业 Agent turn 事实不能为空");
        }
        try {
            return connectionManager.executeInTransaction(connection -> saveWithinTransaction(connection, fact));
        } catch (PlatformBusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("写入专业 Agent turn 事实失败，idempotencyKey="
                    + fact.idempotencyKey(), exception);
        }
    }

    /**
     * 在当前 JDBC 事务中完成身份检查和 upsert。
     *
     * <p>先按幂等键、再按 turn 三元组加锁查询，可以把“相同 turn 换了 key”这种错误尽早转成业务冲突，
     * 而不是把 PostgreSQL 唯一键异常原样暴露给调用方。</p>
     */
    private SpecialistTurnFact saveWithinTransaction(java.sql.Connection connection,
                                                     SpecialistTurnFact fact) throws SQLException {
        SpecialistTurnFact existingByKey = findForUpdate(connection,
                "idempotency_key = ?", List.of(fact.idempotencyKey()));
        if (existingByKey != null) {
            requireSameIdentity(existingByKey, fact, "idempotencyKey");
        }

        SpecialistTurnFact existingByTurn = findForUpdate(connection,
                "session_id = ? AND run_id = ? AND turn_id = ?",
                List.of(fact.sessionId(), fact.runId(), fact.turnId()));
        if (existingByTurn != null
                && !Objects.equals(existingByTurn.idempotencyKey(), fact.idempotencyKey())) {
            throw duplicateIdentity("sessionId/runId/turnId", fact.idempotencyKey());
        }
        if (existingByTurn != null) {
            requireSameIdentity(existingByTurn, fact, "sessionId/runId/turnId");
        }

        try (PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
            bindFact(statement, fact);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw duplicateIdentity("idempotencyKey", fact.idempotencyKey());
                }
                return readFact(resultSet);
            }
        }
    }

    /**
     * 按 session 查询事实。
     *
     * <p>tenant/application/project 永远是固定过滤条件；普通用户范围还会追加 user_id，项目审计范围才允许查看项目内其他用户。
     * 结果按更新时间倒序，便于前端实时刷新时优先看到最新状态。</p>
     */
    @Override
    public List<SpecialistTurnFact> findBySession(SpecialistTurnFact.QueryScope scope,
                                                  String sessionId,
                                                  int limit) {
        return findByLocator(scope, "session_id", sessionId, limit);
    }

    /** 按 run 查询事实，过滤规则与 session 查询完全一致。 */
    @Override
    public List<SpecialistTurnFact> findByRun(SpecialistTurnFact.QueryScope scope,
                                              String runId,
                                              int limit) {
        return findByLocator(scope, "run_id", runId, limit);
    }

    /** 复用 session/run 查询的安全 SQL 构造，locatorColumn 只由本类内部固定传入。 */
    private List<SpecialistTurnFact> findByLocator(SpecialistTurnFact.QueryScope scope,
                                                   String locatorColumn,
                                                   String locatorValue,
                                                   int limit) {
        Objects.requireNonNull(scope, "查询范围不能为空");
        if (locatorValue == null || locatorValue.isBlank()) {
            return List.of();
        }
        int safeLimit = normalizeLimit(limit);
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(SELECT_COLUMNS)
                .append(" FROM ").append(TABLE_NAME)
                .append(" WHERE tenant_id = ? AND application_id = ? AND project_id = ? AND ")
                .append(locatorColumn).append(" = ?");
        List<Object> parameters = new ArrayList<>(List.of(
                scope.tenantId(), scope.applicationId(), scope.projectId(), locatorValue.trim()));
        if (!scope.allowOtherActors()) {
            sql.append(" AND user_id = ?");
            parameters.add(scope.actorId());
        }
        sql.append(" ORDER BY updated_at DESC, created_at DESC, id DESC LIMIT ?");
        parameters.add(safeLimit);
        try {
            return connectionManager.executeWithConnection(connection -> queryMany(connection, sql.toString(), parameters));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("查询专业 Agent turn 事实失败，" + locatorColumn + "=" + locatorValue, exception);
        }
    }

    /** 按唯一定位条件加锁读取，供 save 的并发幂等判断使用。 */
    private SpecialistTurnFact findForUpdate(java.sql.Connection connection,
                                             String condition,
                                             List<Object> parameters) throws SQLException {
        String sql = "SELECT " + SELECT_COLUMNS + " FROM " + TABLE_NAME
                + " WHERE " + condition + " LIMIT 1 FOR UPDATE";
        return queryOne(connection, sql, parameters).orElse(null);
    }

    /** 执行单行查询并把 ResultSet 转换为低敏领域对象。 */
    private Optional<SpecialistTurnFact> queryOne(java.sql.Connection connection,
                                                  String sql,
                                                  List<Object> parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindQueryParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readFact(resultSet)) : Optional.empty();
            }
        }
    }

    /** 执行列表查询；SQL 中的 locator 列名只来自本类固定白名单，值全部使用 PreparedStatement。 */
    private List<SpecialistTurnFact> queryMany(java.sql.Connection connection,
                                               String sql,
                                               List<Object> parameters) throws SQLException {
        List<SpecialistTurnFact> facts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindQueryParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    facts.add(readFact(resultSet));
                }
            }
        }
        return List.copyOf(facts);
    }

    /** 把领域对象绑定到 upsert 的全部参数，数组使用 Jackson 序列化而不是手工拼 JSON。 */
    private void bindFact(PreparedStatement statement, SpecialistTurnFact fact) throws SQLException {
        int index = 1;
        setString(statement, index++, fact.userId());
        statement.setLong(index++, fact.tenantId());
        statement.setLong(index++, fact.applicationId());
        statement.setLong(index++, fact.projectId());
        setString(statement, index++, fact.sessionId());
        setString(statement, index++, fact.runId());
        setString(statement, index++, fact.turnId());
        setString(statement, index++, fact.idempotencyKey());
        setString(statement, index++, fact.agentId());
        setString(statement, index++, fact.role());
        setNullableString(statement, index++, fact.delegationId());
        setString(statement, index++, fact.status());
        setString(statement, index++, fact.lowSensitiveSummary());
        setNullableString(statement, index++, fact.modelInvocationId());
        setNullableString(statement, index++, fact.modelName());
        setString(statement, index++, writeReferences(fact.toolActivitySummaryRefs()));
        setString(statement, index++, writeReferences(fact.evidenceRefs()));
        if (fact.durationMillis() == null) {
            statement.setNull(index++, Types.BIGINT);
        } else {
            statement.setLong(index++, fact.durationMillis());
        }
        setNullableTimestamp(statement, index++, fact.startedAt());
        setNullableTimestamp(statement, index++, fact.finishedAt());
        setNullableTimestamp(statement, index++, fact.createdAt());
        setNullableTimestamp(statement, index, fact.updatedAt());
    }

    /** 绑定查询值，避免把 locator 或分页值通过字符串拼接进入 SQL。 */
    private void bindQueryParameters(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object parameter = parameters.get(index);
            int jdbcIndex = index + 1;
            if (parameter instanceof Long value) {
                statement.setLong(jdbcIndex, value);
            } else if (parameter instanceof Integer value) {
                statement.setInt(jdbcIndex, value);
            } else {
                setString(statement, jdbcIndex, parameter == null ? null : parameter.toString());
            }
        }
    }

    /** 从 ResultSet 读取一条事实；如果数据库数据已经违反领域约束，直接让读取失败而不是返回不可信对象。 */
    private SpecialistTurnFact readFact(ResultSet resultSet) throws SQLException {
        return new SpecialistTurnFact(
                resultSet.getString("user_id"),
                requiredLong(resultSet, "tenant_id"),
                requiredLong(resultSet, "application_id"),
                requiredLong(resultSet, "project_id"),
                resultSet.getString("session_id"),
                resultSet.getString("run_id"),
                resultSet.getString("turn_id"),
                resultSet.getString("idempotency_key"),
                resultSet.getString("agent_id"),
                resultSet.getString("role"),
                resultSet.getString("delegation_id"),
                resultSet.getString("status"),
                resultSet.getString("low_sensitive_summary"),
                resultSet.getString("model_invocation_id"),
                resultSet.getString("model_name"),
                readReferences(resultSet.getString("tool_activity_summary_refs")),
                readReferences(resultSet.getString("evidence_refs")),
                nullableLong(resultSet, "duration_millis"),
                instant(resultSet, "started_at"),
                instant(resultSet, "finished_at"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at")
        );
    }

    /** 校验幂等冲突中的不可变身份字段，防止 key 被用作跨范围覆盖工具。 */
    private void requireSameIdentity(SpecialistTurnFact existing,
                                     SpecialistTurnFact incoming,
                                     String locator) {
        if (!existing.sameIdentity(incoming)) {
            throw duplicateIdentity(locator, incoming.idempotencyKey());
        }
    }

    /** 统一构造可被前端识别的重复/身份冲突业务异常。 */
    private PlatformBusinessException duplicateIdentity(String locator, String idempotencyKey) {
        return new PlatformBusinessException(
                PlatformErrorCode.DUPLICATE_OPERATION,
                "专业 Agent turn 幂等冲突：" + locator + " 已绑定其他身份，不能覆盖，key=" + idempotencyKey
        );
    }

    /** 限制查询条数，保证负数和过大值都不能改变 Store 的资源边界。 */
    private int normalizeLimit(int limit) {
        return Math.max(1, Math.min(limit <= 0 ? 100 : limit, Math.min(maxQueryLimit, SpecialistTurnFact.MAX_QUERY_LIMIT)));
    }

    /** 把引用数组序列化成 JSONB 文本。领域对象已经完成格式和数量校验。 */
    private String writeReferences(List<String> references) throws SQLException {
        try {
            return objectMapper.writeValueAsString(references == null ? List.of() : references);
        } catch (JsonProcessingException exception) {
            throw new SQLException("序列化专业 Agent turn 引用失败", exception);
        }
    }

    /** 从 JSONB 文本读取引用数组，并再次交给领域对象做安全校验。 */
    private List<String> readReferences(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> references = objectMapper.readValue(json, STRING_LIST_TYPE);
            return references == null ? List.of() : references;
        } catch (JsonProcessingException exception) {
            throw new SQLException("解析专业 Agent turn 引用失败", exception);
        }
    }

    /** 绑定必填字符串。 */
    private void setString(PreparedStatement statement, int index, String value) throws SQLException {
        statement.setString(index, value);
    }

    /** 绑定可空字符串，避免把 null 变成字符串 "null"。 */
    private void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    /** 绑定带时区语义的时间戳；PostgreSQL JDBC 会按瞬时点写入 timestamptz。 */
    private void setNullableTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            statement.setTimestamp(index, Timestamp.from(value));
        }
    }

    /** 读取必填 BIGINT，并把数据库中的 NULL 视为损坏事实。 */
    private long requiredLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        if (resultSet.wasNull() || value <= 0) {
            throw new SQLException("专业 Agent turn 事实的 " + column + " 无效");
        }
        return value;
    }

    /** 读取可空 BIGINT。 */
    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    /** 把 JDBC 时间戳恢复为统一的 Instant。 */
    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}

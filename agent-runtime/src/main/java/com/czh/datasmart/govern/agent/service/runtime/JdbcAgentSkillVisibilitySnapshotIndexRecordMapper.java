/**
 * @Author : Cui
 * @Date: 2026/06/04 23:10
 * @Description DataSmart Govern Backend - JdbcAgentSkillVisibilitySnapshotIndexRecordMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Skill 可见性快照 MySQL 索引的 JDBC 字段映射工具。
 *
 * <p>该类刻意保持 package-private，并且不注册为 Spring Bean。它不是业务服务，而是一个“映射边界”：
 * {@link JdbcAgentSkillVisibilitySnapshotIndexStore} 负责幂等写入、条件查询和异常语义；
 * 本类负责 SQL 字段清单、参数绑定、JSON 序列化和 ResultSet 还原。这样可以避免 Store 变成几百行的
 * “巨型 Impl”，也让后续字段扩展时更容易检查数据库列、DTO 字段和 runtime event attributes 是否一致。</p>
 *
 * <p>为什么既保存拆出的索引列，又保存 {@code attributes_json}：</p>
 * <p>1. 拆出的列用于高频查询和聚合，例如 tenant/project/run、Manifest 指纹、权限事实来源、隐藏状态计数；</p>
 * <p>2. {@code attributes_json} 保留 Python Runtime 事件的低敏聚合快照，保证 Java 强类型视图在字段演进初期
 * 不需要每新增一个展示字段就立刻改数据库结构；</p>
 * <p>3. 该 JSON 只允许保存 Skill 可见性低敏事实，不能扩展为 prompt、SQL、工具参数、连接密钥、样本数据、
 * 长期记忆正文或权限明细的容器。</p>
 */
final class JdbcAgentSkillVisibilitySnapshotIndexRecordMapper {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP_TYPE = new TypeReference<>() {
    };

    /**
     * MySQL 查询统一字段清单。
     *
     * <p>Store 的列表查询、诊断查询和未来按 identityKey 回读都应复用该字段清单。这样可以避免“写入时有字段，
     * 查询时忘记取字段”的隐蔽问题。虽然 {@code toRecord(...)} 当前主要依赖核心事件字段和
     * {@code attributes_json}，但保留完整列清单可以让 SQL 查询结果在数据库客户端中也一眼看到关键索引维度。</p>
     */
    static final String SELECT_COLUMNS = """
            identity_key, schema_version, source, event_type, stage, message, severity,
            tenant_id, project_id, actor_id, request_id, run_id, session_id,
            producer_sequence, replay_sequence, created_at, published_at, consumed_at,
            snapshot_type, snapshot_source, available, available_skill_count,
            visible_skill_count, hidden_skill_count, conditional_visible_skill_count,
            permission_fact_source, actor_role_source, actor_role, granted_permission_count,
            tenant_skill_enabled, workspace_risk_level, tenant_plan_code, policy_version,
            legacy_request_variables_detected, model_gateway_available, tool_budget_allowed,
            manifest_binding_status, manifest_status, manifest_source, manifest_fingerprint,
            manifest_schema_version, manifest_skill_count, manifest_ready_skill_count,
            manifest_non_ready_skill_count, manifest_fallback,
            visible_skill_codes_json, visible_skill_codes_truncated_count,
            hidden_skill_codes_json, hidden_skill_codes_truncated_count,
            visible_risk_level_counts_json, visible_domain_counts_json,
            hidden_admission_status_counts_json, display_summary, recommended_action_count,
            attributes_json, create_time, update_time
            """;

    /**
     * Skill 可见性快照首次入库 SQL。
     *
     * <p>这里使用普通 INSERT，而不是 INSERT IGNORE。原因是 INSERT IGNORE 在 MySQL 中可能把某些数据问题降级为
     * warning，容易掩盖字段截断、非法 JSON 等真正需要排查的问题。重复消息由唯一索引抛出的 duplicate key
     * 异常表达，Store 再把它转换为 {@code false}，形成清晰的幂等语义。</p>
     */
    static final String INSERT_SQL = """
            INSERT INTO agent_skill_visibility_snapshot_index (
                identity_key, schema_version, source, event_type, stage, message, severity,
                tenant_id, project_id, actor_id, request_id, run_id, session_id,
                producer_sequence, replay_sequence, created_at, published_at, consumed_at,
                snapshot_type, snapshot_source, available, available_skill_count,
                visible_skill_count, hidden_skill_count, conditional_visible_skill_count,
                permission_fact_source, actor_role_source, actor_role, granted_permission_count,
                tenant_skill_enabled, workspace_risk_level, tenant_plan_code, policy_version,
                legacy_request_variables_detected, model_gateway_available, tool_budget_allowed,
                manifest_binding_status, manifest_status, manifest_source, manifest_fingerprint,
                manifest_schema_version, manifest_skill_count, manifest_ready_skill_count,
                manifest_non_ready_skill_count, manifest_fallback,
                visible_skill_codes_json, visible_skill_codes_truncated_count,
                hidden_skill_codes_json, hidden_skill_codes_truncated_count,
                visible_risk_level_counts_json, visible_domain_counts_json,
                hidden_admission_status_counts_json, display_summary, recommended_action_count,
                attributes_json, create_time, update_time
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?,
                ?, ?,
                ?, ?,
                ?, ?,
                ?, ?,
                ?, ?, ?,
                ?, ?, ?
            )
            """;

    private JdbcAgentSkillVisibilitySnapshotIndexRecordMapper() {
    }

    /**
     * 绑定 Skill 可见性快照 INSERT 参数。
     *
     * <p>字段来源分为两类：</p>
     * <p>1. {@link AgentRuntimeEventProjectionRecord} 核心字段：identityKey、tenant/project/run/session、
     * producerSequence、replaySequence、createdAt 等，用于查询定位和 replay 游标；</p>
     * <p>2. attributes 中的低敏 Skill 可见性字段：可见/隐藏数量、权限事实来源、Manifest 指纹、隐藏状态分布等，
     * 用于治理页聚合和审计报表。</p>
     *
     * <p>注意：该方法不会把 attributes 中不存在的字段推断成“业务真实值”，只会写入 null、false 或 0 的保守默认。
     * 这是为了避免 Java 索引层在不了解 Python Runtime 决策细节时制造伪事实。</p>
     */
    static void bindRecord(PreparedStatement statement,
                           AgentRuntimeEventProjectionRecord record,
                           ObjectMapper objectMapper) throws SQLException {
        Map<String, Object> attributes = safeAttributes(record);
        Instant storedAt = record.consumedAt() == null ? Instant.now() : record.consumedAt();
        int index = 1;
        statement.setString(index++, truncate(record.identityKey(), 512));
        setNullableString(statement, index++, record.schemaVersion());
        setNullableString(statement, index++, record.source());
        statement.setString(index++, record.eventType());
        setNullableString(statement, index++, record.stage());
        setNullableString(statement, index++, truncate(record.message(), 1024));
        setNullableString(statement, index++, normalizeSeverity(record.severity()));
        setNullableString(statement, index++, record.tenantId());
        setNullableString(statement, index++, record.projectId());
        setNullableString(statement, index++, record.actorId());
        setNullableString(statement, index++, record.requestId());
        setNullableString(statement, index++, record.runId());
        setNullableString(statement, index++, record.sessionId());
        setNullableLong(statement, index++, record.sequence());
        setNullableLong(statement, index++, record.replaySequence());
        setNullableTimestamp(statement, index++, record.createdAt());
        setNullableTimestamp(statement, index++, record.publishedAt());
        setNullableTimestamp(statement, index++, record.consumedAt());
        setNullableString(statement, index++, text(attributes, "snapshotType"));
        setNullableString(statement, index++, text(attributes, "snapshotSource"));
        statement.setBoolean(index++, bool(attributes, "available"));
        statement.setInt(index++, integer(attributes, "availableSkillCount"));
        statement.setInt(index++, integer(attributes, "visibleSkillCount"));
        statement.setInt(index++, integer(attributes, "hiddenSkillCount"));
        statement.setInt(index++, integer(attributes, "conditionalVisibleSkillCount"));
        setNullableString(statement, index++, normalizedText(attributes, "permissionFactSource"));
        setNullableString(statement, index++, normalizedText(attributes, "actorRoleSource"));
        setNullableString(statement, index++, text(attributes, "actorRole"));
        statement.setInt(index++, integer(attributes, "grantedPermissionCount"));
        statement.setBoolean(index++, bool(attributes, "tenantSkillEnabled"));
        setNullableString(statement, index++, text(attributes, "workspaceRiskLevel"));
        setNullableString(statement, index++, text(attributes, "tenantPlanCode"));
        setNullableString(statement, index++, text(attributes, "policyVersion"));
        statement.setBoolean(index++, bool(attributes, "legacyRequestVariablesDetected"));
        statement.setBoolean(index++, bool(attributes, "modelGatewayAvailable"));
        statement.setBoolean(index++, bool(attributes, "toolBudgetAllowed"));
        setNullableString(statement, index++, defaultedText(attributes, "manifestBindingStatus", "UNBOUND_UNKNOWN"));
        setNullableString(statement, index++, defaultedText(attributes, "manifestStatus", "UNKNOWN"));
        setNullableString(statement, index++, defaultedText(attributes, "manifestSource", "unknown"));
        setNullableString(statement, index++, text(attributes, "manifestFingerprint"));
        setNullableString(statement, index++, text(attributes, "manifestSchemaVersion"));
        statement.setInt(index++, integer(attributes, "manifestSkillCount"));
        statement.setInt(index++, integer(attributes, "manifestReadySkillCount"));
        statement.setInt(index++, integer(attributes, "manifestNonReadySkillCount"));
        statement.setBoolean(index++, bool(attributes, "manifestFallback"));
        statement.setString(index++, toJson(valueOrDefault(attributes.get("visibleSkillCodes"), List.of()), objectMapper));
        statement.setInt(index++, integer(attributes, "visibleSkillCodesTruncatedCount"));
        statement.setString(index++, toJson(valueOrDefault(attributes.get("hiddenSkillCodes"), List.of()), objectMapper));
        statement.setInt(index++, integer(attributes, "hiddenSkillCodesTruncatedCount"));
        statement.setString(index++, toJson(valueOrDefault(attributes.get("visibleRiskLevelCounts"), Map.of()), objectMapper));
        statement.setString(index++, toJson(valueOrDefault(attributes.get("visibleDomainCounts"), Map.of()), objectMapper));
        statement.setString(index++, toJson(valueOrDefault(attributes.get("hiddenAdmissionStatusCounts"), Map.of()), objectMapper));
        setNullableString(statement, index++, truncate(text(attributes, "displaySummary"), 2048));
        statement.setInt(index++, integer(attributes, "recommendedActionCount"));
        statement.setString(index++, toJson(attributes, objectMapper));
        setNullableTimestamp(statement, index++, storedAt);
        setNullableTimestamp(statement, index, storedAt);
    }

    /**
     * 从数据库行还原为控制面 projection record。
     *
     * <p>查询服务后续仍复用 {@link AgentSkillVisibilitySnapshotProjectionService#querySnapshots} 的强类型转换逻辑，
     * 因此这里不直接返回 DTO，而是还原成与内存 projection 一致的 record。这样 MySQL Store 和内存 Store 对上层
     * 完全同构：Service 不需要知道数据来自 JVM Map 还是 MySQL 表。</p>
     */
    static AgentRuntimeEventProjectionRecord toRecord(ResultSet resultSet, ObjectMapper objectMapper) throws SQLException {
        return new AgentRuntimeEventProjectionRecord(
                resultSet.getString("identity_key"),
                resultSet.getString("schema_version"),
                resultSet.getString("source"),
                resultSet.getString("event_type"),
                resultSet.getString("stage"),
                resultSet.getString("message"),
                resultSet.getString("severity"),
                resultSet.getString("tenant_id"),
                resultSet.getString("project_id"),
                resultSet.getString("actor_id"),
                resultSet.getString("request_id"),
                resultSet.getString("run_id"),
                resultSet.getString("session_id"),
                getNullableLong(resultSet, "producer_sequence"),
                getNullableLong(resultSet, "replay_sequence"),
                getInstant(resultSet, "created_at"),
                getInstant(resultSet, "published_at"),
                getInstant(resultSet, "consumed_at"),
                fromJsonMap(resultSet.getString("attributes_json"), objectMapper)
        );
    }

    /**
     * 绑定动态查询参数。
     *
     * <p>Skill 快照查询会根据租户、项目、actor、run、session、request、severity、afterSequence 和授权项目集合
     * 动态拼接 WHERE 条件。集中绑定参数可以保证 null、Long、Integer 等 JDBC 类型处理一致。</p>
     */
    static void bindParameters(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object parameter = parameters.get(index);
            int jdbcIndex = index + 1;
            if (parameter == null) {
                statement.setNull(jdbcIndex, Types.NULL);
            } else if (parameter instanceof Long longValue) {
                statement.setLong(jdbcIndex, longValue);
            } else if (parameter instanceof Integer integer) {
                statement.setInt(jdbcIndex, integer);
            } else if (parameter instanceof Boolean booleanValue) {
                statement.setBoolean(jdbcIndex, booleanValue);
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

    private static Map<String, Object> safeAttributes(AgentRuntimeEventProjectionRecord record) {
        if (record.attributes() == null || record.attributes().isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(record.attributes());
    }

    private static Object valueOrDefault(Object value, Object defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static String text(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value == null) {
            return null;
        }
        String text = Objects.toString(value, "").trim();
        return text.isEmpty() ? null : text;
    }

    private static String normalizedText(Map<String, Object> attributes, String key) {
        String value = text(attributes, key);
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static String defaultedText(Map<String, Object> attributes, String key, String defaultValue) {
        String value = text(attributes, key);
        return value == null ? defaultValue : value;
    }

    private static int integer(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value instanceof String text) {
            try {
                return Math.max(0, Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static boolean bool(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value == null) {
            return false;
        }
        return switch (Objects.toString(value, "").trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on", "enabled" -> true;
            default -> false;
        };
    }

    private static String normalizeSeverity(String severity) {
        if (severity == null || severity.isBlank()) {
            return "info";
        }
        return severity.trim().toLowerCase(Locale.ROOT);
    }

    private static String toJson(Object value, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化 Skill 可见性快照索引 JSON 字段失败", exception);
        }
    }

    private static Map<String, Object> fromJsonMap(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> value = objectMapper.readValue(json, OBJECT_MAP_TYPE);
            return value == null ? Map.of() : value;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("反序列化 Skill 可见性快照索引 attributes_json 失败", exception);
        }
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static void setNullableTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.from(value));
        }
    }

    private static Long getNullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Instant getInstant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}

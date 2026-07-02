/**
 * @Author : Cui
 * @Date: 2026/06/18 00:00
 * @Description DataSmart Govern Backend - JdbcAgentToolActionClarificationFactRecordMapper.java
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
import java.util.List;

/**
 * 澄清事实 MySQL 表字段映射器。
 *
 * <p>该类保持 package-private，不注册为 Spring Bean。它只承担“Java record 与 JDBC 字段之间如何翻译”的职责：
 * 1. 维护统一 SELECT 字段清单；
 * 2. 维护 UPSERT SQL；
 * 3. 绑定 PreparedStatement 参数；
 * 4. 从 ResultSet 还原 {@link AgentToolActionClarificationFactRecord}。</p>
 *
 * <p>安全边界：本 mapper 只处理低敏元数据和低敏 code 数组。它没有任何用户澄清原文字段，
 * 也不会接触 prompt、SQL、arguments、payload body、模型输出、凭证或内部 endpoint。</p>
 */
final class JdbcAgentToolActionClarificationFactRecordMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    /**
     * 澄清事实查询统一字段清单。
     *
     * <p>所有查询方法都复用这一份字段清单，避免新增列后 INSERT 与 SELECT 漂移。
     * 自增 id、create_time、update_time 不进入领域 record，因为恢复预检只关心业务事实本身。</p>
     */
    static final String SELECT_COLUMNS = """
            clarification_fact_id, session_id, run_id, command_id, tool_code,
            requested_policy_version, tenant_id, project_id, actor_id, status,
            evidence_codes_json, issue_codes_json, expires_at, created_at, updated_at
            """;

    /**
     * 澄清事实幂等 upsert SQL。
     *
     * <p>clarification_fact_id 是唯一键。同一个 factId 重复登记时不生成多条事实，而是刷新低敏状态、code 和过期时间。
     * 对 run/session/command/tool 等定位字段使用“新值非空才覆盖”的规则，避免撤销或重试请求没带完整字段时冲掉旧定位符。</p>
     */
    static final String UPSERT_SQL = """
            INSERT INTO agent_tool_action_clarification_fact (
                clarification_fact_id, session_id, run_id, command_id, tool_code,
                requested_policy_version, tenant_id, project_id, actor_id, status,
                evidence_codes_json, issue_codes_json, expires_at, created_at, updated_at
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?
            )
            ON CONFLICT (clarification_fact_id) DO UPDATE SET
                session_id = COALESCE(NULLIF(EXCLUDED.session_id, ''), agent_tool_action_clarification_fact.session_id),
                run_id = COALESCE(NULLIF(EXCLUDED.run_id, ''), agent_tool_action_clarification_fact.run_id),
                command_id = COALESCE(NULLIF(EXCLUDED.command_id, ''), agent_tool_action_clarification_fact.command_id),
                tool_code = COALESCE(NULLIF(EXCLUDED.tool_code, ''), agent_tool_action_clarification_fact.tool_code),
                requested_policy_version = COALESCE(NULLIF(EXCLUDED.requested_policy_version, ''), agent_tool_action_clarification_fact.requested_policy_version),
                tenant_id = COALESCE(NULLIF(EXCLUDED.tenant_id, ''), agent_tool_action_clarification_fact.tenant_id),
                project_id = COALESCE(NULLIF(EXCLUDED.project_id, ''), agent_tool_action_clarification_fact.project_id),
                actor_id = COALESCE(NULLIF(EXCLUDED.actor_id, ''), agent_tool_action_clarification_fact.actor_id),
                status = EXCLUDED.status,
                evidence_codes_json = EXCLUDED.evidence_codes_json,
                issue_codes_json = EXCLUDED.issue_codes_json,
                expires_at = COALESCE(EXCLUDED.expires_at, agent_tool_action_clarification_fact.expires_at),
                updated_at = COALESCE(EXCLUDED.updated_at, agent_tool_action_clarification_fact.updated_at),
                update_time = CURRENT_TIMESTAMP
            """;

    private JdbcAgentToolActionClarificationFactRecordMapper() {
    }

    /**
     * 绑定澄清事实 upsert 参数。
     *
     * <p>字符串字段会按数据库列宽裁剪，低敏 code 会序列化为 JSON 数组。这里的 JSON 只是枚举码数组，
     * 不是用户澄清正文；如果调用方把疑似 SQL、prompt 或密钥塞进 code，登记服务已经会通过格式白名单过滤。</p>
     */
    static void bindRecord(PreparedStatement statement,
                           AgentToolActionClarificationFactRecord record) throws SQLException {
        int index = 1;
        setNullableString(statement, index++, truncate(record.clarificationFactId(), 180));
        setNullableString(statement, index++, truncate(record.sessionId(), 180));
        setNullableString(statement, index++, truncate(record.runId(), 180));
        setNullableString(statement, index++, truncate(record.commandId(), 180));
        setNullableString(statement, index++, truncate(record.toolCode(), 180));
        setNullableString(statement, index++, truncate(record.requestedPolicyVersion(), 180));
        setNullableString(statement, index++, truncate(record.tenantId(), 80));
        setNullableString(statement, index++, truncate(record.projectId(), 80));
        setNullableString(statement, index++, truncate(record.actorId(), 120));
        setNullableString(statement, index++, truncate(record.status(), 40));
        setNullableString(statement, index++, writeCodes(record.evidenceCodes()));
        setNullableString(statement, index++, writeCodes(record.issueCodes()));
        setNullableTimestamp(statement, index++, record.expiresAt());
        setNullableTimestamp(statement, index++, record.createdAt());
        setNullableTimestamp(statement, index, record.updatedAt());
    }

    /**
     * 从数据库行还原澄清事实记录。
     *
     * <p>仓储只负责按 factId 找到记录。租户、项目、actor、run、session、command、tool 和策略版本是否匹配，
     * 仍由 {@link AgentToolActionClarificationFactEvaluator} 做统一判定，避免不同 Store 实现产生不同安全语义。</p>
     */
    static AgentToolActionClarificationFactRecord toRecord(ResultSet resultSet) throws SQLException {
        return new AgentToolActionClarificationFactRecord(
                resultSet.getString("clarification_fact_id"),
                resultSet.getString("session_id"),
                resultSet.getString("run_id"),
                resultSet.getString("command_id"),
                resultSet.getString("tool_code"),
                resultSet.getString("requested_policy_version"),
                resultSet.getString("tenant_id"),
                resultSet.getString("project_id"),
                resultSet.getString("actor_id"),
                resultSet.getString("status"),
                readCodes(resultSet.getString("evidence_codes_json")),
                readCodes(resultSet.getString("issue_codes_json")),
                getInstant(resultSet, "expires_at"),
                getInstant(resultSet, "created_at"),
                getInstant(resultSet, "updated_at")
        );
    }

    /**
     * 绑定动态查询参数。
     *
     * <p>当前查询参数主要是 factId 和 limit。集中绑定可以让后续增加 TTL 归档、租户诊断查询或分页时，
     * 继续复用同一套 null、数字和时间类型处理规则。</p>
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

    private static String writeCodes(List<String> codes) throws SQLException {
        try {
            return OBJECT_MAPPER.writeValueAsString(codes == null ? List.of() : codes);
        } catch (JsonProcessingException exception) {
            throw new SQLException("序列化澄清事实低敏 code 数组失败", exception);
        }
    }

    private static List<String> readCodes(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = OBJECT_MAPPER.readValue(json, STRING_LIST_TYPE);
            return values == null ? List.of() : values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
        } catch (JsonProcessingException exception) {
            throw new SQLException("解析澄清事实低敏 code 数组失败", exception);
        }
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

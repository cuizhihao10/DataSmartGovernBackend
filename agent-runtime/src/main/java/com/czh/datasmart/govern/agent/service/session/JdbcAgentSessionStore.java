/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - JdbcAgentSessionStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import com.czh.datasmart.govern.agent.config.AgentRuntimePersistenceProperties;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentSessionState;
import com.czh.datasmart.govern.agent.model.AgentToolBindingStatus;
import com.czh.datasmart.govern.agent.model.AgentToolType;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 会话聚合的 PostgreSQL 持久化实现。
 *
 * <p>一个会话不仅包含主表信息，还包含委托授权、可用工具、运行记录和对话消息。该类把这些数据视为
 * 同一个聚合快照：写入时使用单个数据库事务，读取时按固定顺序还原完整对象，避免服务重启后只恢复到
 * 一部分状态。它仅负责持久化，不在这里重新判断当前用户是否有权访问会话；对象级授权由
 * {@link AgentSessionEndpointAccessResolver} 和服务层共同完成。</p>
 */
@Component
@ConditionalOnExpression(
        "T(com.czh.datasmart.govern.agent.config.AgentRuntimeStoreMode)"
                + ".isJdbcDurable('${datasmart.agent-runtime.persistence.session-store:memory}') "
                + "&& '${datasmart.agent-runtime.persistence.database-enabled:false}'.equalsIgnoreCase('true')"
)
public class JdbcAgentSessionStore implements AgentSessionStore {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };
    private final AgentRuntimeJdbcConnectionManager connectionManager;
    private final ObjectMapper objectMapper;
    private final int maxQueryLimit;

    /**
     * 创建 JDBC 会话存储，并把配置的查询上限收敛到 1 至 100。
     *
     * @param connectionManager 统一提供连接和事务边界的运行时连接管理器
     * @param objectMapper 将工具权限、运行变量等结构写入或读出 JSONB 的序列化器
     * @param properties Agent Runtime 持久化配置，其中的查询上限用于保护历史会话接口
     */
    public JdbcAgentSessionStore(AgentRuntimeJdbcConnectionManager connectionManager,
                                 ObjectMapper objectMapper,
                                 AgentRuntimePersistenceProperties properties) {
        this.connectionManager = connectionManager;
        this.objectMapper = objectMapper;
        this.maxQueryLimit = Math.max(1, Math.min(properties.getJdbc().getMaxQueryLimit(), 100));
    }

    /**
     * 原子保存一个完整会话快照。
     *
     * <p>主表先执行 upsert，随后整组替换一对一委托和各个一对多子集合。所有步骤共享同一事务；任一
     * 子表写入失败都会回滚主表更新，调用方不会看到“会话已更新但消息或运行记录缺失”的半成品。</p>
     *
     * @param session 已在业务层完成权限校验和状态变更的会话聚合
     */
    @Override
    public void save(AgentSessionRecord session) {
        connectionManager.executeInTransaction(connection -> {
            upsertSession(connection, session);
            replaceDelegation(connection, session);
            replaceToolBindings(connection, session);
            replaceRuns(connection, session);
            replaceMessages(connection, session);
            return null;
        });
    }

    /**
     * 只追加一条对话消息并刷新会话活跃时间，不读取、更不会替换 Run、工具绑定或委托子集合。
     *
     * <p>该方法专门解决跨服务 continuation 的丢失更新问题。确认执行线程调用 Python 时，Python 可能已经通过
     * plan ingestion 向同一 PostgreSQL 会话写入了新的 Run；此时整聚合 {@link #save(AgentSessionRecord)} 会把旧快照
     * 之外的 Run 删除。增量 SQL 把写集合严格限制为 {@code agent_conversation_message} 和
     * {@code agent_session.last_message_at/update_time}，从持久化层保证新 Run 不受影响。</p>
     *
     * <p>消息 ID 使用唯一约束实现幂等。先更新会话主表用于确认父会话存在，再插入消息；两步位于同一事务中，
     * 任一步失败都会整体回滚。时间字段使用条件表达式只向前推进，避免并发消息提交顺序与创建顺序不同导致
     * 历史会话列表的活跃时间倒退。</p>
     *
     * @param sessionId 目标会话 ID
     * @param message 已治理的用户可见消息
     * @return 会话存在且增量写完成时返回 true；会话不存在或参数无效时返回 false
     */
    @Override
    public boolean appendConversationMessage(String sessionId, AgentConversationMessageRecord message) {
        if (!hasText(sessionId) || message == null || !hasText(message.messageId()) || !hasText(message.content())) {
            return false;
        }
        String normalizedSessionId = sessionId.trim();
        LocalDateTime messageTime = message.createTime() == null ? LocalDateTime.now() : message.createTime();
        return connectionManager.executeInTransaction(connection -> {
            String updateSql = """
                    UPDATE agent_session
                    SET last_message_at = CASE
                            WHEN last_message_at IS NULL OR last_message_at < ? THEN ?
                            ELSE last_message_at
                        END,
                        update_time = CASE
                            WHEN update_time < ? THEN ?
                            ELSE update_time
                        END
                    WHERE session_id = ?
                    """;
            try (PreparedStatement update = connection.prepareStatement(updateSql)) {
                setTimestamp(update, 1, messageTime);
                setTimestamp(update, 2, messageTime);
                setTimestamp(update, 3, messageTime);
                setTimestamp(update, 4, messageTime);
                update.setString(5, normalizedSessionId);
                if (update.executeUpdate() == 0) {
                    return false;
                }
            }

            String insertSql = """
                    INSERT INTO agent_conversation_message
                        (message_id, session_id, run_id, role, content, create_time)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (message_id) DO NOTHING
                    """;
            try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                insert.setString(1, message.messageId().trim());
                insert.setString(2, normalizedSessionId);
                insert.setString(3, message.runId());
                insert.setString(4, message.role());
                insert.setString(5, message.content());
                setTimestamp(insert, 6, messageTime);
                insert.executeUpdate();
            }
            return true;
        });
    }

    /**
     * 按业务会话编号恢复完整聚合。
     *
     * @param sessionId Agent 会话业务编号；空白值直接视为不存在，避免无意义数据库访问
     * @return 找到时返回包含委托、工具、运行和消息的聚合，否则返回空
     */
    @Override
    public Optional<AgentSessionRecord> findById(String sessionId) {
        if (!hasText(sessionId)) {
            return Optional.empty();
        }
        return connectionManager.executeWithConnection(connection -> querySession(connection, sessionId.trim()));
    }

    /**
     * 查询指定租户、项目和用户范围内的会话历史。
     *
     * <p>三个范围条件均为可选，是为了同时支持用户历史和受控管理员审计；调用本方法前必须由服务层
     * 决定允许使用哪些范围，不能直接把客户端参数当作授权结论。结果先显示置顶会话，再按更新时间倒序，
     * 并同时受请求上限、100 条硬上限和服务配置上限约束。</p>
     *
     * @param tenantId 可选租户范围
     * @param projectId 可选项目范围
     * @param actorId 可选会话所有者范围
     * @param archived true 查询归档历史，false 查询活跃历史
     * @param limit 调用方期望返回条数
     * @return 已按展示顺序恢复完成的会话聚合列表
     */
    @Override
    public List<AgentSessionRecord> list(Long tenantId,
                                         Long projectId,
                                         String actorId,
                                         boolean archived,
                                         int limit) {
        int normalizedLimit = Math.max(1, Math.min(Math.min(limit, 100), maxQueryLimit));
        return connectionManager.executeWithConnection(connection -> {
            StringBuilder sql = new StringBuilder("SELECT session_id FROM agent_session WHERE archived_at IS ")
                    .append(archived ? "NOT NULL" : "NULL");
            List<Object> parameters = new ArrayList<>();
            if (tenantId != null) {
                sql.append(" AND tenant_id = ?");
                parameters.add(tenantId);
            }
            if (projectId != null) {
                sql.append(" AND project_id = ?");
                parameters.add(projectId);
            }
            if (hasText(actorId)) {
                sql.append(" AND actor_id = ?");
                parameters.add(actorId.trim());
            }
            sql.append(" ORDER BY pinned DESC, update_time DESC LIMIT ?");
            parameters.add(normalizedLimit);
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                bindParameters(statement, parameters);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<AgentSessionRecord> records = new ArrayList<>();
                    while (resultSet.next()) {
                        querySession(connection, resultSet.getString(1)).ifPresent(records::add);
                    }
                    return records;
                }
            }
        });
    }

    /**
     * 新增或更新会话主表。
     *
     * <p>冲突更新只改变运行期间允许变化的身份描述、角色快照和展示状态；租户、项目、用户、目标及
     * 工作区键等创建边界不会被后续保存偷偷改写，从而避免同一 sessionId 被迁移到另一个安全域。</p>
     */
    private void upsertSession(Connection connection, AgentSessionRecord session) throws SQLException {
        String sql = """
                INSERT INTO agent_session (
                    session_id, agent_id, tenant_id, project_id, workspace_id, actor_id, actor_role, actor_type,
                    authorized_project_roles, channel, objective, isolation_level, workspace_key, state,
                    pinned, archived_at, last_message_at, create_time, update_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (session_id) DO UPDATE SET
                    agent_id=EXCLUDED.agent_id, actor_role=EXCLUDED.actor_role, actor_type=EXCLUDED.actor_type,
                    authorized_project_roles=EXCLUDED.authorized_project_roles, state=EXCLUDED.state,
                    pinned=EXCLUDED.pinned, archived_at=EXCLUDED.archived_at,
                    last_message_at=EXCLUDED.last_message_at, update_time=EXCLUDED.update_time
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, session.getSessionId());
            statement.setString(index++, session.getAgentId());
            setNullableLong(statement, index++, session.getTenantId());
            setNullableLong(statement, index++, session.getProjectId());
            setNullableLong(statement, index++, session.getWorkspaceId());
            statement.setString(index++, session.getActorId());
            statement.setString(index++, session.getActorRole());
            statement.setString(index++, session.getActorType());
            statement.setString(index++, session.getAuthorizedProjectRoles());
            statement.setString(index++, session.getChannel());
            statement.setString(index++, session.getObjective());
            statement.setString(index++, session.getIsolationLevel().name());
            statement.setString(index++, session.getWorkspaceKey());
            statement.setString(index++, session.getState().name());
            statement.setBoolean(index++, session.isPinned());
            setTimestamp(statement, index++, session.getArchivedAt());
            setTimestamp(statement, index++, session.getLastMessageAt());
            setTimestamp(statement, index++, session.getCreateTime());
            setTimestamp(statement, index, session.getUpdateTime());
            statement.executeUpdate();
        }
    }

    /**
     * 用当前聚合中的委托快照替换旧委托。
     *
     * <p>会话只允许存在一个当前委托，因此先删除再插入比逐字段合并更容易保持撤销时间、有效期和
     * 资源范围的一致性。该步骤处于外层事务中，删除后插入失败不会永久丢失旧记录。</p>
     */
    private void replaceDelegation(Connection connection, AgentSessionRecord session) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM agent_delegation WHERE session_id = ?")) {
            delete.setString(1, session.getSessionId());
            delete.executeUpdate();
        }
        AgentDelegationRecord record = session.getDelegation();
        String sql = """
                INSERT INTO agent_delegation (
                    delegation_id, session_id, agent_id, user_actor_id, tenant_id, project_id,
                    tool_codes, actions, resource_scopes, status, issued_at, expires_at, revoked_at, update_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, record.getDelegationId());
            statement.setString(index++, session.getSessionId());
            statement.setString(index++, record.getAgentId());
            statement.setString(index++, record.getUserActorId());
            setNullableLong(statement, index++, record.getTenantId());
            setNullableLong(statement, index++, record.getProjectId());
            statement.setString(index++, json(record.getToolCodes()));
            statement.setString(index++, json(record.getActions()));
            statement.setString(index++, json(record.getResourceScopes()));
            statement.setString(index++, record.getStatus());
            setTimestamp(statement, index++, record.getIssuedAt());
            setTimestamp(statement, index++, record.getExpiresAt());
            setTimestamp(statement, index++, record.getRevokedAt());
            setTimestamp(statement, index, record.getUpdateTime());
            statement.executeUpdate();
        }
    }

    /**
     * 整组替换会话绑定的工具清单。
     *
     * <p>工具绑定数量较小且属于运行规划快照，采用 delete-and-batch-insert 可避免处理复杂的新增、
     * 禁用和删除差异；工具允许动作以 JSONB 保存，但真正执行时仍必须经过委托、工具策略和下游权限校验。</p>
     */
    private void replaceToolBindings(Connection connection, AgentSessionRecord session) throws SQLException {
        deleteChildren(connection, "agent_session_tool_binding", session.getSessionId());
        String sql = """
                INSERT INTO agent_session_tool_binding (
                    binding_id, session_id, tool_code, tool_type, display_name, target_service, target_endpoint,
                    target_resource_id, read_only, risk_level, execution_mode, requires_approval, idempotent,
                    status, allowed_actions, create_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (AgentToolBindingRecord binding : session.getToolBindings()) {
                int index = 1;
                statement.setString(index++, binding.bindingId());
                statement.setString(index++, session.getSessionId());
                statement.setString(index++, binding.toolCode());
                statement.setString(index++, binding.toolType().name());
                statement.setString(index++, binding.displayName());
                statement.setString(index++, binding.targetService());
                statement.setString(index++, binding.targetEndpoint());
                setNullableLong(statement, index++, binding.targetResourceId());
                statement.setBoolean(index++, Boolean.TRUE.equals(binding.readOnly()));
                statement.setString(index++, binding.riskLevel());
                statement.setString(index++, binding.executionMode());
                statement.setBoolean(index++, Boolean.TRUE.equals(binding.requiresApproval()));
                statement.setBoolean(index++, Boolean.TRUE.equals(binding.idempotent()));
                statement.setString(index++, binding.status().name());
                statement.setString(index++, json(binding.allowedActions()));
                setTimestamp(statement, index, binding.createTime());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /**
     * 整组替换会话内的运行记录。
     *
     * <p>运行变量和后续动作是可扩展结构，因此使用 JSONB；状态、时间和是否需要人工审批仍保留为
     * 独立列，便于运维查询与索引，而不必扫描 JSON 文档。</p>
     */
    private void replaceRuns(Connection connection, AgentSessionRecord session) throws SQLException {
        deleteChildren(connection, "agent_run", session.getSessionId());
        String sql = """
                INSERT INTO agent_run (
                    run_id, session_id, state, workload_type, user_input_preview, dry_run, require_human_approval,
                    next_actions, variables, message, create_time, update_time, finish_time
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (AgentRunRecord run : session.getRuns()) {
                int index = 1;
                statement.setString(index++, run.getRunId());
                statement.setString(index++, session.getSessionId());
                statement.setString(index++, run.getState().name());
                statement.setString(index++, run.getWorkloadType());
                statement.setString(index++, run.getUserInputPreview());
                statement.setBoolean(index++, Boolean.TRUE.equals(run.getDryRun()));
                statement.setBoolean(index++, Boolean.TRUE.equals(run.getRequireHumanApproval()));
                statement.setString(index++, json(run.getNextActions()));
                statement.setString(index++, json(run.getVariables()));
                statement.setString(index++, run.getMessage());
                setTimestamp(statement, index++, run.getCreateTime());
                setTimestamp(statement, index++, run.getUpdateTime());
                setTimestamp(statement, index, run.getFinishTime());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /**
     * 整组替换会话对话消息，并使用 JDBC batch 减少往返次数。
     *
     * <p>消息保留所属 runId，使一次会话中的多轮追问可以追溯到具体执行；runId 允许为空，以兼容
     * 尚未形成运行计划的用户输入和系统提示。</p>
     */
    private void replaceMessages(Connection connection, AgentSessionRecord session) throws SQLException {
        deleteChildren(connection, "agent_conversation_message", session.getSessionId());
        String sql = "INSERT INTO agent_conversation_message "
                + "(message_id, session_id, run_id, role, content, create_time) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (AgentConversationMessageRecord message : session.getMessages()) {
                statement.setString(1, message.messageId());
                statement.setString(2, session.getSessionId());
                statement.setString(3, message.runId());
                statement.setString(4, message.role());
                statement.setString(5, message.content());
                setTimestamp(statement, 6, message.createTime());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /**
     * 查询主表并按“委托、工具、运行、消息”的顺序还原聚合。
     *
     * <p>所有子查询复用同一个连接，因此在事务隔离可见性上保持一致。历史数据若缺少委托仍可读出，
     * 但后续写操作会由服务层按 fail-closed 原则拒绝。</p>
     */
    private Optional<AgentSessionRecord> querySession(Connection connection, String sessionId) throws SQLException {
        String sql = "SELECT * FROM agent_session WHERE session_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                AgentDelegationRecord delegation = queryDelegation(connection, sessionId).orElse(null);
                return Optional.of(new AgentSessionRecord(
                        sessionId,
                        resultSet.getString("agent_id"),
                        nullableLong(resultSet, "tenant_id"),
                        nullableLong(resultSet, "project_id"),
                        nullableLong(resultSet, "workspace_id"),
                        resultSet.getString("actor_id"),
                        resultSet.getString("actor_role"),
                        resultSet.getString("actor_type"),
                        resultSet.getString("authorized_project_roles"),
                        resultSet.getString("channel"),
                        resultSet.getString("objective"),
                        WorkspaceIsolationLevel.valueOf(resultSet.getString("isolation_level")),
                        resultSet.getString("workspace_key"),
                        AgentSessionState.valueOf(resultSet.getString("state")),
                        delegation,
                        resultSet.getBoolean("pinned"),
                        localDateTime(resultSet, "archived_at"),
                        localDateTime(resultSet, "last_message_at"),
                        localDateTime(resultSet, "create_time"),
                        localDateTime(resultSet, "update_time"),
                        queryToolBindings(connection, sessionId),
                        queryRuns(connection, sessionId),
                        queryMessages(connection, sessionId)
                ));
            }
        }
    }

    /** 查询并反序列化会话唯一的委托凭据；不存在时返回空而不是制造默认授权。 */
    private Optional<AgentDelegationRecord> queryDelegation(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM agent_delegation WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AgentDelegationRecord(
                        resultSet.getString("delegation_id"), resultSet.getString("agent_id"),
                        resultSet.getString("user_actor_id"), nullableLong(resultSet, "tenant_id"),
                        nullableLong(resultSet, "project_id"), strings(resultSet.getString("tool_codes")),
                        strings(resultSet.getString("actions")), strings(resultSet.getString("resource_scopes")),
                        resultSet.getString("status"), localDateTime(resultSet, "issued_at"),
                        localDateTime(resultSet, "expires_at"), localDateTime(resultSet, "revoked_at"),
                        localDateTime(resultSet, "update_time")));
            }
        }
    }

    /** 按创建时间恢复工具绑定，保证重启前后的工具展示和执行规划顺序稳定。 */
    private List<AgentToolBindingRecord> queryToolBindings(Connection connection, String sessionId) throws SQLException {
        String sql = "SELECT * FROM agent_session_tool_binding WHERE session_id = ? ORDER BY create_time";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentToolBindingRecord> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(new AgentToolBindingRecord(
                            resultSet.getString("binding_id"), resultSet.getString("tool_code"),
                            AgentToolType.valueOf(resultSet.getString("tool_type")), resultSet.getString("display_name"),
                            resultSet.getString("target_service"), resultSet.getString("target_endpoint"),
                            nullableLong(resultSet, "target_resource_id"), resultSet.getBoolean("read_only"),
                            resultSet.getString("risk_level"), resultSet.getString("execution_mode"),
                            resultSet.getBoolean("requires_approval"), resultSet.getBoolean("idempotent"),
                            AgentToolBindingStatus.valueOf(resultSet.getString("status")),
                            strings(resultSet.getString("allowed_actions")), localDateTime(resultSet, "create_time")));
                }
                return result;
            }
        }
    }

    /** 按创建时间恢复运行记录，使前端能够按发生顺序继续展示同一会话的多轮执行。 */
    private List<AgentRunRecord> queryRuns(Connection connection, String sessionId) throws SQLException {
        String sql = "SELECT * FROM agent_run WHERE session_id = ? ORDER BY create_time";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentRunRecord> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(new AgentRunRecord(
                            resultSet.getString("run_id"), sessionId,
                            AgentRunState.valueOf(resultSet.getString("state")), resultSet.getString("workload_type"),
                            resultSet.getString("user_input_preview"), resultSet.getBoolean("dry_run"),
                            resultSet.getBoolean("require_human_approval"), strings(resultSet.getString("next_actions")),
                            objectMap(resultSet.getString("variables")), localDateTime(resultSet, "create_time"),
                            localDateTime(resultSet, "update_time"), localDateTime(resultSet, "finish_time"),
                            resultSet.getString("message")));
                }
                return result;
            }
        }
    }

    /** 按创建时间恢复用户、助手及系统消息，作为继续追问时的持久上下文。 */
    private List<AgentConversationMessageRecord> queryMessages(Connection connection, String sessionId) throws SQLException {
        String sql = "SELECT * FROM agent_conversation_message WHERE session_id = ? ORDER BY create_time, message_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<AgentConversationMessageRecord> result = new ArrayList<>();
                while (resultSet.next()) {
                    result.add(new AgentConversationMessageRecord(
                            resultSet.getString("message_id"), resultSet.getString("run_id"),
                            resultSet.getString("role"), resultSet.getString("content"),
                            localDateTime(resultSet, "create_time")));
                }
                return result;
            }
        }
    }

    /**
     * 删除某个会话的一类子记录，为整组替换做准备。
     *
     * <p>table 只由本类的固定常量调用路径传入，不接收外部输入；表名无法使用 JDBC 占位符，因此
     * 如果未来扩展调用点，必须继续保持内部白名单，不能把请求参数传到这里。</p>
     */
    private void deleteChildren(Connection connection, String table, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            statement.executeUpdate();
        }
    }

    /**
     * 按动态筛选条件的构造顺序绑定参数。
     *
     * <p>这里只处理当前列表查询使用的 Long 和 String，新增参数类型时应显式扩展，避免依赖驱动的
     * 隐式转换导致索引失效或跨数据库行为不一致。</p>
     */
    private void bindParameters(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            statement.setObject(index + 1, parameters.get(index));
        }
    }

    /** 将可扩展业务结构序列化为 JSONB 输入文本；失败时中止整次聚合事务。 */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化 Agent 会话聚合 JSON 失败", exception);
        }
    }

    /** 从 JSONB 文本恢复字符串列表，并把数据库空值统一解释为空列表。 */
    private List<String> strings(String value) {
        if (!hasText(value)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("解析 Agent 会话字符串列表失败", exception);
        }
    }

    /** 从 JSONB 文本恢复运行变量，避免调用方处理可变或空 Map。 */
    private Map<String, Object> objectMap(String value) {
        if (!hasText(value)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, OBJECT_MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("解析 Agent Run 变量失败", exception);
        }
    }

    /**
     * 以明确的 BIGINT SQL 类型绑定可空编号。
     *
     * <p>不能直接对 null 调用 setObject 而依赖驱动猜测类型，否则 PostgreSQL 在某些预编译语句下
     * 无法推断参数类型。</p>
     */
    private void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    /** 统一把 Java 本地时间写为 JDBC Timestamp，并保留合法的空结束时间或撤销时间。 */
    private void setTimestamp(PreparedStatement statement, int index, LocalDateTime value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(index, Timestamp.valueOf(value));
        }
    }

    /** 读取可空 BIGINT；通过 {@link ResultSet#wasNull()} 区分数据库 NULL 与数值 0。 */
    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    /** 读取可空时间列，供未归档、未结束或永不过期等状态使用。 */
    private LocalDateTime localDateTime(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    /** 判断外部文本是否包含有效内容，用于在进入数据库层前规范化筛选条件。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

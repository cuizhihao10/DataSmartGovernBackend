/**
 * @Author : Cui
 * @Date: 2026/05/24 22:43
 * @Description DataSmart Govern Backend - AgentToolExecutionOutputStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Agent 工具执行输出仓储。
 *
 * <p>该仓储是工具链上下文的第一版实现。它解决的问题是：
 * Agent 工具不是孤立的一次 HTTP 调用，而是一个 Run 内的多步工作流。
 * 后续工具经常需要引用前序工具的输出，例如元数据、规则草案、任务草稿、审批结果。</p>
 *
 * <p>当前采用内存 `CopyOnWriteArrayList`，原因是 agent-runtime 的会话、Run、审计也仍处于内存阶段。
 * 这不是最终生产形态，但先把“成功输出可被后续工具读取”的业务语义固定下来非常重要。
 * 未来迁移时建议：</p>
 * <p>1. 小摘要进入 MySQL 审计表；</p>
 * <p>2. 大结果进入 MinIO 或对象存储；</p>
 * <p>3. 热路径输出引用进入 Redis；</p>
 * <p>4. 工具执行事件进入 Kafka/EventStore，支持回放。</p>
 */
@Component
public class AgentToolExecutionOutputStore {

    private final List<AgentToolExecutionOutputRecord> records = new CopyOnWriteArrayList<>();
    private final AgentRuntimeJdbcConnectionManager connectionManager;
    private final ObjectMapper objectMapper;

    /** Creates the lightweight memory store used by focused unit tests. */
    public AgentToolExecutionOutputStore() {
        this.connectionManager = null;
        this.objectMapper = new ObjectMapper();
    }

    /** Uses PostgreSQL as the durable source of truth whenever control-plane JDBC is enabled. */
    @Autowired
    public AgentToolExecutionOutputStore(ObjectProvider<AgentRuntimeJdbcConnectionManager> connectionManager,
                                         ObjectMapper objectMapper) {
        this.connectionManager = connectionManager.getIfAvailable();
        this.objectMapper = objectMapper;
    }

    /**
     * 保存工具结构化输出。
     *
     * <p>成功输出可被后续节点通过 outputRef 使用；失败输出仅用于结果查询和用户诊断，编排器仍会依据 FAILED
     * 审计状态阻断依赖节点，因此不会把预检问题项误当作成功上下文继续执行。</p>
     */
    public void save(AgentToolExecutionAuditSnapshot audit, Map<String, Object> output) {
        if (output == null || output.isEmpty()) {
            return;
        }
        AgentToolExecutionOutputRecord record = new AgentToolExecutionOutputRecord(
                audit.sessionId(),
                audit.runId(),
                audit.auditId(),
                audit.toolCode(),
                new LinkedHashMap<>(output),
                LocalDateTime.now()
        );
        records.add(record);
        persist(record);
    }

    /**
     * 查询同一 Run 中某个工具最近一次成功输出。
     *
     * @param sessionId 会话 ID
     * @param runId Run ID
     * @param toolCode 工具编码
     * @return 最近一次成功输出；如果没有执行过该工具，则返回空
     */
    public Optional<AgentToolExecutionOutputRecord> findLatest(String sessionId, String runId, String toolCode) {
        Optional<AgentToolExecutionOutputRecord> memoryRecord = records.stream()
                .filter(record -> record.sessionId().equals(sessionId))
                .filter(record -> record.runId().equals(runId))
                .filter(record -> record.toolCode().equals(toolCode))
                .max(Comparator.comparing(AgentToolExecutionOutputRecord::createTime));
        return memoryRecord.isPresent() ? memoryRecord : findLatestJdbc(sessionId, runId, toolCode);
    }

    /**
     * 按审计 ID 查询同一 Run 内的某次工具输出。
     *
     * <p>这是显式 outputRef 协议的关键能力。
     * 当同一个工具在同一 Run 内执行多次时，仅凭 toolCode 已经无法稳定判断“后续工具应该引用哪一次输出”。
     * auditId 是工具执行审计的唯一标识，使用它可以让工具链引用具备可复现性和可审计性。</p>
     */
    public Optional<AgentToolExecutionOutputRecord> findByAuditId(String sessionId, String runId, String auditId) {
        Optional<AgentToolExecutionOutputRecord> memoryRecord = records.stream()
                .filter(record -> record.sessionId().equals(sessionId))
                .filter(record -> record.runId().equals(runId))
                .filter(record -> record.auditId().equals(auditId))
                .findFirst();
        return memoryRecord.isPresent() ? memoryRecord : findByAuditIdJdbc(sessionId, runId, auditId);
    }

    /**
     * Resolves an explicit output reference across runs in the same Agent session.
     * The session boundary is mandatory; knowing an audit id alone never grants access.
     */
    public Optional<AgentToolExecutionOutputRecord> findBySessionAuditId(String sessionId, String auditId) {
        Optional<AgentToolExecutionOutputRecord> memoryRecord = records.stream()
                .filter(record -> record.sessionId().equals(sessionId))
                .filter(record -> record.auditId().equals(auditId))
                .findFirst();
        return memoryRecord.isPresent() ? memoryRecord : findBySessionAuditIdJdbc(sessionId, auditId);
    }

    /**
     * 查询同一 Run 的所有成功输出。
     * 当前主要用于测试和后续调试接口扩展。
     */
    public List<AgentToolExecutionOutputRecord> listByRun(String sessionId, String runId) {
        List<AgentToolExecutionOutputRecord> result = new ArrayList<>();
        for (AgentToolExecutionOutputRecord record : records) {
            if (record.sessionId().equals(sessionId) && record.runId().equals(runId)) {
                result.add(record);
            }
        }
        return result;
    }

    private void persist(AgentToolExecutionOutputRecord record) {
        if (connectionManager == null) {
            return;
        }
        connectionManager.executeWithConnection(connection -> {
            String sql = "INSERT INTO agent_tool_execution_output "
                    + "(audit_id, session_id, run_id, tool_code, output_json, create_time) "
                    + "VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?) "
                    + "ON CONFLICT (audit_id) DO UPDATE SET session_id = EXCLUDED.session_id, "
                    + "run_id = EXCLUDED.run_id, tool_code = EXCLUDED.tool_code, "
                    + "output_json = EXCLUDED.output_json, create_time = EXCLUDED.create_time";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, record.auditId());
                statement.setString(2, record.sessionId());
                statement.setString(3, record.runId());
                statement.setString(4, record.toolCode());
                statement.setString(5, objectMapper.writeValueAsString(record.output()));
                statement.setTimestamp(6, Timestamp.valueOf(record.createTime()));
                statement.executeUpdate();
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to persist Agent tool output", exception);
            }
            return null;
        });
    }

    private Optional<AgentToolExecutionOutputRecord> findLatestJdbc(String sessionId, String runId, String toolCode) {
        return queryOne(
                "SELECT session_id, run_id, audit_id, tool_code, output_json, create_time "
                        + "FROM agent_tool_execution_output WHERE session_id = ? AND run_id = ? AND tool_code = ? "
                        + "ORDER BY create_time DESC LIMIT 1",
                sessionId, runId, toolCode
        );
    }

    private Optional<AgentToolExecutionOutputRecord> findByAuditIdJdbc(
            String sessionId, String runId, String auditId) {
        return queryOne(
                "SELECT session_id, run_id, audit_id, tool_code, output_json, create_time "
                        + "FROM agent_tool_execution_output WHERE session_id = ? AND run_id = ? AND audit_id = ?",
                sessionId, runId, auditId
        );
    }

    private Optional<AgentToolExecutionOutputRecord> findBySessionAuditIdJdbc(String sessionId, String auditId) {
        return queryOne(
                "SELECT session_id, run_id, audit_id, tool_code, output_json, create_time "
                        + "FROM agent_tool_execution_output WHERE session_id = ? AND audit_id = ?",
                sessionId, auditId
        );
    }

    private Optional<AgentToolExecutionOutputRecord> queryOne(String sql, String... parameters) {
        if (connectionManager == null) {
            return Optional.empty();
        }
        return connectionManager.executeWithConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int index = 0; index < parameters.length; index++) {
                    statement.setString(index + 1, parameters[index]);
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    Map<String, Object> output = objectMapper.readValue(
                            resultSet.getString("output_json"),
                            new TypeReference<Map<String, Object>>() {
                            }
                    );
                    return Optional.of(new AgentToolExecutionOutputRecord(
                            resultSet.getString("session_id"),
                            resultSet.getString("run_id"),
                            resultSet.getString("audit_id"),
                            resultSet.getString("tool_code"),
                            output,
                            resultSet.getTimestamp("create_time").toLocalDateTime()
                    ));
                }
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to read Agent tool output", exception);
            }
        });
    }

    /**
     * 小型快照接口，避免输出仓储直接依赖完整 `AgentToolExecutionAuditRecord`。
     *
     * <p>这样仓储只知道保存输出所需的最小字段，不关心审批、风险、状态机等审计细节。</p>
     */
    public record AgentToolExecutionAuditSnapshot(String sessionId,
                                                  String runId,
                                                  String auditId,
                                                  String toolCode) {
    }
}

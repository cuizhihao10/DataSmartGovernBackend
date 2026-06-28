/**
 * @Author : Cui
 * @Date: 2026/06/28 21:46
 * @Description DataSmart Govern Backend - JdbcAgentToolActionSubmissionFactStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.persistence.AgentRuntimeJdbcConnectionManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Optional;

/**
 * MySQL 版受控工具提交事实仓储。
 *
 * <p>该实现是内存提交缓存的生产化替换点。真实环境中，同一个 commandId 可能被多个 worker、
 * 多个 agent-runtime 实例、Kafka 重放或 HTTP 重试同时触发。如果仍依赖 JVM 内 Map，
 * 服务重启或多实例部署会重新调用 data-quality，造成重复治理任务。</p>
 *
 * <p>本实现使用两层保护：
 * 1. start 时先 `SELECT ... FOR UPDATE` 读取已有事实；
 * 2. 首次并发插入时再依赖 command_id 唯一索引兜底。
 * 只有真正插入 SUBMITTING 的调用者才允许继续执行真实下游调用。</p>
 */
@Component
@ConditionalOnExpression(
        "'${datasmart.agent-runtime.tool-action-submissions.store:memory}'.equalsIgnoreCase('mysql') "
                + "&& '${datasmart.agent-runtime.persistence.database-enabled:false}'.equalsIgnoreCase('true')"
)
public class JdbcAgentToolActionSubmissionFactStore implements AgentToolActionSubmissionFactStore {

    private final AgentRuntimeJdbcConnectionManager connectionManager;

    public JdbcAgentToolActionSubmissionFactStore(AgentRuntimeJdbcConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public Optional<AgentToolActionSubmissionFactRecord> findByCommandId(String commandId) {
        return connectionManager.executeWithConnection(connection ->
                selectByCommandId(connection, commandId, false));
    }

    @Override
    public AgentToolActionSubmissionFactStartResult start(AgentToolActionSubmissionFactRecord candidate) {
        return connectionManager.executeInTransaction(connection -> startInTransaction(connection, candidate));
    }

    @Override
    public AgentToolActionSubmissionFactRecord save(AgentToolActionSubmissionFactRecord record) {
        connectionManager.executeWithConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    JdbcAgentToolActionSubmissionFactRecordMapper.UPSERT_SQL)) {
                JdbcAgentToolActionSubmissionFactRecordMapper.bindRecord(statement, record);
                statement.executeUpdate();
                return null;
            }
        });
        return record;
    }

    private AgentToolActionSubmissionFactStartResult startInTransaction(
            Connection connection,
            AgentToolActionSubmissionFactRecord candidate) throws SQLException {
        Optional<AgentToolActionSubmissionFactRecord> existing =
                selectByCommandId(connection, candidate.commandId(), true);
        if (existing.isPresent()) {
            return new AgentToolActionSubmissionFactStartResult(false, existing.get());
        }
        try (PreparedStatement statement = connection.prepareStatement(
                JdbcAgentToolActionSubmissionFactRecordMapper.INSERT_SQL)) {
            JdbcAgentToolActionSubmissionFactRecordMapper.bindRecord(statement, candidate);
            statement.executeUpdate();
            return new AgentToolActionSubmissionFactStartResult(true, candidate);
        } catch (SQLIntegrityConstraintViolationException duplicate) {
            /*
             * 如果两个 worker 同时看到“尚无记录”，其中一个 insert 会成功，另一个会命中唯一键。
             * 这里不把它当成系统异常，而是重新读取已有事实并返回 started=false，
             * 让上层按幂等命中处理，避免重复下游副作用。
             */
            Optional<AgentToolActionSubmissionFactRecord> raced =
                    selectByCommandId(connection, candidate.commandId(), true);
            return new AgentToolActionSubmissionFactStartResult(false, raced.orElse(candidate));
        }
    }

    private Optional<AgentToolActionSubmissionFactRecord> selectByCommandId(
            Connection connection,
            String commandId,
            boolean forUpdate) throws SQLException {
        String sql = "SELECT " + JdbcAgentToolActionSubmissionFactRecordMapper.SELECT_COLUMNS
                + " FROM agent_tool_action_submission_fact WHERE command_id = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, commandId == null ? "" : commandId.trim());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(JdbcAgentToolActionSubmissionFactRecordMapper.toRecord(resultSet));
            }
        }
    }
}

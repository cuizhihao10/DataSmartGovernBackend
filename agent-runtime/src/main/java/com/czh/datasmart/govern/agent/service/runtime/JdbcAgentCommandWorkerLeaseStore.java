/**
 * @Author : Cui
 * @Date: 2026/06/24 02:10
 * @Description DataSmart Govern Backend - JdbcAgentCommandWorkerLeaseStore.java
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
import java.time.Instant;
import java.util.Optional;

/**
 * MySQL 版 command worker lease 仓储。
 *
 * <p>这是 {@link InMemoryAgentCommandWorkerLeaseStore} 的生产化替换点。内存版足以表达学习和单测语义，
 * 但真实商业化部署会有多个 Java 控制面实例、多个 Python/worker 进程、队列重复投递、服务重启和任务重试。
 * 这些场景要求 lease fact 能跨进程保存，并且领取过程必须具备数据库级原子性。</p>
 *
 * <p>本实现采用“按 lease_identity_key 行锁定 + 单调 leaseVersion + fencingToken”的模式：
 * 当前持有者未过期时，同 worker 重试按幂等处理，不同 worker 会被拒绝且拿不到 token；
 * 当前记录过期或不存在时，新 worker 才能写入新版本。receipt service 后续会用 token/version/expiresAt
 * 再次校验，从而阻止旧 worker 在过期后写回副作用结果。</p>
 */
@Component
@ConditionalOnExpression(
        "'${datasmart.agent-runtime.command-worker-leases.store:memory}'.equalsIgnoreCase('mysql') "
                + "&& '${datasmart.agent-runtime.persistence.database-enabled:false}'.equalsIgnoreCase('true')"
)
public class JdbcAgentCommandWorkerLeaseStore implements AgentCommandWorkerLeaseStore {

    /**
     * agent-runtime 专用 JDBC 连接管理器。
     *
     * <p>这里不直接创建 DataSource，是为了复用 agent-runtime 已有的连接池、超时、账号和事务策略。
     * 只有显式开启 database-enabled 且本 Store 选择 mysql 时，连接池才会被条件化创建。</p>
     */
    private final AgentRuntimeJdbcConnectionManager connectionManager;

    public JdbcAgentCommandWorkerLeaseStore(AgentRuntimeJdbcConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    /**
     * 原子领取 command worker lease。
     *
     * <p>该方法必须放在事务里执行，因为它包含“查当前持有者 -> 判断是否有效 -> 决定是否插入/更新”的多步流程。
     * `SELECT ... FOR UPDATE` 会锁住命中的 lease 行；对不存在行的并发首次插入，还会由主键唯一约束兜底，
     * 避免两个 worker 同时成为同一 command 的有效持有者。</p>
     */
    @Override
    public AgentCommandWorkerLeaseClaimResult claim(AgentCommandWorkerLeaseRecord candidate, Instant now) {
        return connectionManager.executeInTransaction(connection -> claimInTransaction(connection, candidate, now));
    }

    /**
     * 查询当前 lease fact。
     *
     * <p>receipt 校验使用普通 SELECT 即可，因为写回时只需要读取“当前事实”并判断 token/version 是否一致；
     * 不需要在校验阶段占用长事务锁。真正的持有者切换只发生在 claim 路径。</p>
     */
    @Override
    public Optional<AgentCommandWorkerLeaseRecord> findByIdentity(String sessionId, String runId, String commandId) {
        String identityKey = AgentCommandWorkerLeaseService.leaseIdentityKey(sessionId, runId, commandId);
        return connectionManager.executeWithConnection(connection -> selectByIdentity(connection, identityKey, false));
    }

    private AgentCommandWorkerLeaseClaimResult claimInTransaction(Connection connection,
                                                                  AgentCommandWorkerLeaseRecord candidate,
                                                                  Instant now) throws SQLException {
        Optional<AgentCommandWorkerLeaseRecord> existingOptional =
                selectByIdentity(connection, candidate.leaseIdentityKey(), true);
        if (existingOptional.isPresent()) {
            AgentCommandWorkerLeaseRecord existing = existingOptional.get();
            if (existing.activeAt(now)) {
                if (existing.heldBy(candidate.executorId())) {
                    return AgentCommandWorkerLeaseClaimResult.of(true,
                            AgentCommandWorkerLeaseState.ALREADY_HELD_BY_CALLER,
                            existing,
                            true,
                            "同一 worker 已持有 command lease，本次按幂等领取处理。");
                }
                return AgentCommandWorkerLeaseClaimResult.of(false,
                        AgentCommandWorkerLeaseState.ALREADY_HELD_BY_OTHER,
                        existing,
                        false,
                        "command lease 正由其他 worker 持有，当前 worker 必须停止处理。");
            }
        }

        long nextVersion = existingOptional.map(AgentCommandWorkerLeaseRecord::leaseVersion)
                .map(version -> version + 1)
                .orElse(candidate.leaseVersion());
        AgentCommandWorkerLeaseRecord record = new AgentCommandWorkerLeaseRecord(
                candidate.leaseIdentityKey(),
                candidate.sessionId(),
                candidate.runId(),
                candidate.commandId(),
                candidate.executorId(),
                candidate.tenantId(),
                candidate.projectId(),
                candidate.actorId(),
                AgentCommandWorkerLeaseService.newFencingToken(candidate.leaseIdentityKey(),
                        candidate.executorId(), nextVersion, now),
                nextVersion,
                candidate.leaseExpiresAt(),
                now,
                now
        );
        if (existingOptional.isPresent()) {
            update(connection, record);
        } else {
            insert(connection, record);
        }
        return AgentCommandWorkerLeaseClaimResult.of(true,
                AgentCommandWorkerLeaseState.ACQUIRED,
                record,
                true,
                "command lease 领取成功。");
    }

    private Optional<AgentCommandWorkerLeaseRecord> selectByIdentity(Connection connection,
                                                                     String identityKey,
                                                                     boolean forUpdate) throws SQLException {
        String sql = "SELECT " + JdbcAgentCommandWorkerLeaseRecordMapper.SELECT_COLUMNS
                + " FROM agent_command_worker_lease WHERE lease_identity_key = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, identityKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(JdbcAgentCommandWorkerLeaseRecordMapper.toRecord(resultSet));
            }
        }
    }

    private void insert(Connection connection, AgentCommandWorkerLeaseRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                JdbcAgentCommandWorkerLeaseRecordMapper.INSERT_SQL)) {
            JdbcAgentCommandWorkerLeaseRecordMapper.bindInsert(statement, record);
            statement.executeUpdate();
        }
    }

    private void update(Connection connection, AgentCommandWorkerLeaseRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                JdbcAgentCommandWorkerLeaseRecordMapper.UPDATE_SQL)) {
            JdbcAgentCommandWorkerLeaseRecordMapper.bindUpdate(statement, record);
            statement.executeUpdate();
        }
    }
}

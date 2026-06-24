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
     * 原子续租 command worker lease。
     *
     * <p>续租必须和领取一样进入事务，并锁住当前 command 的 lease 行。否则可能出现这样的竞态：
     * 旧 worker 看到自己仍持有 token，准备续租；与此同时 lease 过期并被新 worker 领取；如果续租不加锁，
     * 旧 worker 可能把新 worker 的过期时间覆盖掉，造成两个 worker 都以为自己合法。</p>
     */
    @Override
    public AgentCommandWorkerLeaseClaimResult renew(AgentCommandWorkerLeaseRecord candidate, Instant now) {
        return connectionManager.executeInTransaction(connection -> renewInTransaction(connection, candidate, now));
    }

    /**
     * 原子释放 command worker lease。
     *
     * <p>释放也需要锁住当前行，并且只允许当前 token/version 的持有者释放。释放成功后记录仍保留，
     * 只是 leaseExpiresAt 更新为 now，下一次 claim 会基于旧版本递增，避免版本回退。</p>
     */
    @Override
    public AgentCommandWorkerLeaseClaimResult release(AgentCommandWorkerLeaseRecord candidate, Instant now) {
        return connectionManager.executeInTransaction(connection -> releaseInTransaction(connection, candidate, now));
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

    private AgentCommandWorkerLeaseClaimResult renewInTransaction(Connection connection,
                                                                  AgentCommandWorkerLeaseRecord candidate,
                                                                  Instant now) throws SQLException {
        Optional<AgentCommandWorkerLeaseRecord> existingOptional =
                selectByIdentity(connection, candidate.leaseIdentityKey(), true);
        AgentCommandWorkerLeaseClaimResult rejection = validateCurrentHolder(existingOptional, candidate, now);
        if (rejection != null) {
            return rejection;
        }
        AgentCommandWorkerLeaseRecord existing = existingOptional.get();
        AgentCommandWorkerLeaseRecord renewed = new AgentCommandWorkerLeaseRecord(
                existing.leaseIdentityKey(),
                existing.sessionId(),
                existing.runId(),
                existing.commandId(),
                existing.executorId(),
                prefer(candidate.tenantId(), existing.tenantId()),
                prefer(candidate.projectId(), existing.projectId()),
                prefer(candidate.actorId(), existing.actorId()),
                existing.fencingToken(),
                existing.leaseVersion(),
                candidate.leaseExpiresAt(),
                existing.acquiredAt(),
                now
        );
        update(connection, renewed);
        return AgentCommandWorkerLeaseClaimResult.of(true,
                AgentCommandWorkerLeaseState.RENEWED,
                renewed,
                true,
                "command lease 续租成功。");
    }

    private AgentCommandWorkerLeaseClaimResult releaseInTransaction(Connection connection,
                                                                    AgentCommandWorkerLeaseRecord candidate,
                                                                    Instant now) throws SQLException {
        Optional<AgentCommandWorkerLeaseRecord> existingOptional =
                selectByIdentity(connection, candidate.leaseIdentityKey(), true);
        AgentCommandWorkerLeaseClaimResult rejection = validateCurrentHolder(existingOptional, candidate, now);
        if (rejection != null) {
            return rejection;
        }
        AgentCommandWorkerLeaseRecord existing = existingOptional.get();
        AgentCommandWorkerLeaseRecord released = new AgentCommandWorkerLeaseRecord(
                existing.leaseIdentityKey(),
                existing.sessionId(),
                existing.runId(),
                existing.commandId(),
                existing.executorId(),
                prefer(candidate.tenantId(), existing.tenantId()),
                prefer(candidate.projectId(), existing.projectId()),
                prefer(candidate.actorId(), existing.actorId()),
                existing.fencingToken(),
                existing.leaseVersion(),
                now,
                existing.acquiredAt(),
                now
        );
        update(connection, released);
        return AgentCommandWorkerLeaseClaimResult.of(true,
                AgentCommandWorkerLeaseState.RELEASED,
                released,
                false,
                "command lease 已释放，后续 worker 可重新领取新版本。");
    }

    private AgentCommandWorkerLeaseClaimResult validateCurrentHolder(
            Optional<AgentCommandWorkerLeaseRecord> existingOptional,
            AgentCommandWorkerLeaseRecord candidate,
            Instant now) {
        if (existingOptional.isEmpty()) {
            return AgentCommandWorkerLeaseClaimResult.of(false,
                    AgentCommandWorkerLeaseState.NOT_FOUND,
                    null,
                    false,
                    "未找到 command lease fact，拒绝变更。");
        }
        AgentCommandWorkerLeaseRecord existing = existingOptional.get();
        if (!existing.activeAt(now)) {
            return AgentCommandWorkerLeaseClaimResult.of(false,
                    AgentCommandWorkerLeaseState.EXPIRED,
                    existing,
                    false,
                    "command lease 已过期，旧 worker 不能续租或释放。");
        }
        if (!existing.heldBy(candidate.executorId())) {
            return AgentCommandWorkerLeaseClaimResult.of(false,
                    AgentCommandWorkerLeaseState.ALREADY_HELD_BY_OTHER,
                    existing,
                    false,
                    "command lease 当前由其他 worker 持有。");
        }
        if (!existing.fencingToken().equals(candidate.fencingToken())) {
            return AgentCommandWorkerLeaseClaimResult.of(false,
                    AgentCommandWorkerLeaseState.TOKEN_MISMATCH,
                    existing,
                    false,
                    "fencingToken 与当前 command lease fact 不一致。");
        }
        if (existing.leaseVersion() != candidate.leaseVersion()) {
            return AgentCommandWorkerLeaseClaimResult.of(false,
                    AgentCommandWorkerLeaseState.VERSION_MISMATCH,
                    existing,
                    false,
                    "workerLeaseVersion 与当前 command lease fact 不一致。");
        }
        return null;
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

    private String prefer(String requestedValue, String storedValue) {
        return requestedValue == null ? storedValue : requestedValue;
    }
}

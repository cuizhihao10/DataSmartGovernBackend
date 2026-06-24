/**
 * @Author : Cui
 * @Date: 2026/06/24 01:40
 * @Description DataSmart Govern Backend - InMemoryAgentCommandWorkerLeaseStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认内存版 command worker lease 仓储。
 *
 * <p>它用于本地学习、单元测试和未启用 MySQL/Redis 的开发环境。它能完整表达 claim/fencing 语义，
 * 但不能跨 JVM、跨实例或服务重启保留 lease；生产环境应切换到 MySQL/Redis store。</p>
 */
@Component
@ConditionalOnExpression(
        "'${datasmart.agent-runtime.command-worker-leases.store:memory}'.equalsIgnoreCase('memory')"
)
public class InMemoryAgentCommandWorkerLeaseStore implements AgentCommandWorkerLeaseStore {

    private final Map<String, AgentCommandWorkerLeaseRecord> records = new ConcurrentHashMap<>();

    /**
     * 使用 synchronized 保证“读取旧记录 -> 判断是否过期 -> 写入新记录”的原子性。
     *
     * <p>ConcurrentHashMap 只能保证单次 put/get 线程安全，不能自动保护跨多步的 lease 抢占逻辑。
     * 这里用同步块保持语义清晰；如果未来改成 Redis/MySQL，原子性应由 SET NX PX、Lua、行锁或唯一索引保证。</p>
     */
    @Override
    public synchronized AgentCommandWorkerLeaseClaimResult claim(AgentCommandWorkerLeaseRecord candidate, Instant now) {
        AgentCommandWorkerLeaseRecord existing = records.get(candidate.leaseIdentityKey());
        if (existing != null && existing.activeAt(now)) {
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

        long nextVersion = existing == null ? candidate.leaseVersion() : existing.leaseVersion() + 1;
        AgentCommandWorkerLeaseRecord record = new AgentCommandWorkerLeaseRecord(
                candidate.leaseIdentityKey(),
                candidate.sessionId(),
                candidate.runId(),
                candidate.commandId(),
                candidate.executorId(),
                candidate.tenantId(),
                candidate.projectId(),
                candidate.actorId(),
                AgentCommandWorkerLeaseService.newFencingToken(candidate.leaseIdentityKey(), candidate.executorId(), nextVersion, now),
                nextVersion,
                candidate.leaseExpiresAt(),
                now,
                now
        );
        records.put(record.leaseIdentityKey(), record);
        return AgentCommandWorkerLeaseClaimResult.of(true,
                AgentCommandWorkerLeaseState.ACQUIRED,
                record,
                true,
                "command lease 领取成功。");
    }

    /**
     * 当前持有者续租。
     *
     * <p>内存版虽然只用于本地和单测，也要完整表达生产语义：续租必须先校验当前 lease 仍活跃，
     * 且 executor、fencingToken、leaseVersion 都与 Store 里的事实一致。这样未来切换到 JDBC/Redis 时，
     * service 和 controller 不需要重新学习另一套状态机。</p>
     */
    @Override
    public synchronized AgentCommandWorkerLeaseClaimResult renew(AgentCommandWorkerLeaseRecord candidate, Instant now) {
        AgentCommandWorkerLeaseRecord existing = records.get(candidate.leaseIdentityKey());
        AgentCommandWorkerLeaseClaimResult rejection = validateCurrentHolder(existing, candidate, now);
        if (rejection != null) {
            return rejection;
        }
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
        records.put(renewed.leaseIdentityKey(), renewed);
        return AgentCommandWorkerLeaseClaimResult.of(true,
                AgentCommandWorkerLeaseState.RENEWED,
                renewed,
                true,
                "command lease 续租成功。");
    }

    /**
     * 当前持有者释放。
     *
     * <p>释放时把 leaseExpiresAt 写成 now，而不是从 Map 中删除记录。这样后续 claim 会看到一个已过期旧版本，
     * 进而生成 version+1 的新 fencingToken，保持排障和防旧写回所需的单调版本语义。</p>
     */
    @Override
    public synchronized AgentCommandWorkerLeaseClaimResult release(AgentCommandWorkerLeaseRecord candidate, Instant now) {
        AgentCommandWorkerLeaseRecord existing = records.get(candidate.leaseIdentityKey());
        AgentCommandWorkerLeaseClaimResult rejection = validateCurrentHolder(existing, candidate, now);
        if (rejection != null) {
            return rejection;
        }
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
        records.put(released.leaseIdentityKey(), released);
        return AgentCommandWorkerLeaseClaimResult.of(true,
                AgentCommandWorkerLeaseState.RELEASED,
                released,
                false,
                "command lease 已释放，后续 worker 可重新领取新版本。");
    }

    @Override
    public Optional<AgentCommandWorkerLeaseRecord> findByIdentity(String sessionId, String runId, String commandId) {
        return Optional.ofNullable(records.get(AgentCommandWorkerLeaseService.leaseIdentityKey(sessionId, runId, commandId)));
    }

    private AgentCommandWorkerLeaseClaimResult validateCurrentHolder(AgentCommandWorkerLeaseRecord existing,
                                                                     AgentCommandWorkerLeaseRecord candidate,
                                                                     Instant now) {
        if (existing == null) {
            return AgentCommandWorkerLeaseClaimResult.of(false,
                    AgentCommandWorkerLeaseState.NOT_FOUND,
                    null,
                    false,
                    "未找到 command lease fact，拒绝变更。");
        }
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

    private String prefer(String requestedValue, String storedValue) {
        return requestedValue == null ? storedValue : requestedValue;
    }
}

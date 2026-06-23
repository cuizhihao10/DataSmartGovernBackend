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

    @Override
    public Optional<AgentCommandWorkerLeaseRecord> findByIdentity(String sessionId, String runId, String commandId) {
        return Optional.ofNullable(records.get(AgentCommandWorkerLeaseService.leaseIdentityKey(sessionId, runId, commandId)));
    }
}

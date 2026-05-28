/**
 * @Author : Cui
 * @Date: 2026/05/28 18:00
 * @Description DataSmart Govern Backend - InMemoryAgentToolExecutionEventOutboxStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.outbox;

import com.czh.datasmart.govern.agent.config.AgentToolExecutionEventOutboxProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 内存版 Agent 工具执行事件 outbox 仓储。
 *
 * <p>该实现用于当前 agent-runtime 的过渡阶段：它能让发布链路拥有 outbox 语义、诊断入口和单元测试保护，
 * 但它不是生产级持久化。JVM 重启会丢失记录，多实例之间也不会共享状态。
 * 真正生产化时，应使用本阶段新增的 MySQL outbox 表迁移脚本实现数据库 store，并把状态变更与 outbox 写入放进同一事务。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.persistence",
        name = "outbox-store",
        havingValue = "memory",
        matchIfMissing = true
)
public class InMemoryAgentToolExecutionEventOutboxStore implements AgentToolExecutionEventOutboxStore {

    /**
     * outboxId -> 记录。
     *
     * <p>LinkedHashMap 保留插入顺序，超过 maxTotalRecords 时可以稳定裁剪最早写入的记录。
     * 所有访问都通过读写锁保护，因为 LinkedHashMap 本身不是线程安全容器。</p>
     */
    private final Map<String, AgentToolExecutionEventOutboxRecord> recordsByOutboxId = new LinkedHashMap<>();

    /**
     * runId -> outboxId 队列。
     *
     * <p>按 run 建索引是为了支撑前端 Run 详情页、Python replay、Gateway 断线重连诊断等核心场景。
     * 这里只保存 ID，避免同一条 payload 在多个集合中重复占用内存。</p>
     */
    private final Map<String, Deque<String>> outboxIdsByRunId = new LinkedHashMap<>();

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final int maxEventsPerRun;
    private final int maxTotalRecords;

    public InMemoryAgentToolExecutionEventOutboxStore(AgentToolExecutionEventOutboxProperties properties) {
        this.maxEventsPerRun = Math.max(1, properties.getMaxEventsPerRun());
        this.maxTotalRecords = Math.max(1, properties.getMaxTotalRecords());
    }

    public InMemoryAgentToolExecutionEventOutboxStore(int maxEventsPerRun, int maxTotalRecords) {
        this.maxEventsPerRun = Math.max(1, maxEventsPerRun);
        this.maxTotalRecords = Math.max(1, maxTotalRecords);
    }

    @Override
    public boolean append(AgentToolExecutionEventOutboxRecord record) {
        lock.writeLock().lock();
        try {
            if (recordsByOutboxId.containsKey(record.outboxId())) {
                return false;
            }
            recordsByOutboxId.put(record.outboxId(), record);
            appendRunIndex(record);
            trimGlobalWindow();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<AgentToolExecutionEventOutboxRecord> findByOutboxId(String outboxId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(recordsByOutboxId.get(outboxId));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<AgentToolExecutionEventOutboxRecord> list(String runId,
                                                          AgentToolExecutionEventOutboxStatus status,
                                                          int limit) {
        int normalizedLimit = normalizeLimit(limit);
        lock.readLock().lock();
        try {
            if (hasText(runId)) {
                return listByRunId(runId, status, normalizedLimit);
            }
            return recordsByOutboxId.values().stream()
                    .filter(record -> status == null || record.status() == status)
                    .limit(normalizedLimit)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<AgentToolExecutionEventOutboxRecord> listPublishable(int limit, Instant now) {
        int normalizedLimit = normalizeLimit(limit);
        Instant referenceTime = now == null ? Instant.now() : now;
        lock.readLock().lock();
        try {
            return recordsByOutboxId.values().stream()
                    .filter(record -> record.status() == AgentToolExecutionEventOutboxStatus.PENDING
                            || record.status() == AgentToolExecutionEventOutboxStatus.FAILED)
                    .filter(record -> record.nextRetryAt() == null || !record.nextRetryAt().isAfter(referenceTime))
                    .limit(normalizedLimit)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<AgentToolExecutionEventOutboxRecord> markPublishing(String outboxId, Instant now) {
        Instant referenceTime = now == null ? Instant.now() : now;
        return replaceIf(outboxId,
                record -> isPublishable(record, referenceTime),
                record -> record.markPublishing(referenceTime));
    }

    @Override
    public Optional<AgentToolExecutionEventOutboxRecord> markPublished(String outboxId, Instant now) {
        return replace(outboxId, record -> record.markPublished(now == null ? Instant.now() : now));
    }

    @Override
    public Optional<AgentToolExecutionEventOutboxRecord> markFailed(String outboxId,
                                                                   String error,
                                                                   Instant now,
                                                                   Instant nextRetryAt) {
        Instant referenceTime = now == null ? Instant.now() : now;
        return replace(outboxId, record -> record.markFailed(error, referenceTime, nextRetryAt));
    }

    @Override
    public Optional<AgentToolExecutionEventOutboxRecord> markBlocked(String outboxId, String error, Instant now) {
        Instant referenceTime = now == null ? Instant.now() : now;
        return replace(outboxId, record -> record.markBlocked(error, referenceTime));
    }

    @Override
    public int recoverStalePublishing(Instant staleBefore, Instant now, String error) {
        Instant cutoff = staleBefore == null ? Instant.now() : staleBefore;
        Instant referenceTime = now == null ? Instant.now() : now;
        lock.writeLock().lock();
        try {
            int recovered = 0;
            for (Map.Entry<String, AgentToolExecutionEventOutboxRecord> entry : recordsByOutboxId.entrySet()) {
                AgentToolExecutionEventOutboxRecord record = entry.getValue();
                if (!isStalePublishing(record, cutoff)) {
                    continue;
                }
                /*
                 * stale PUBLISHING 恢复不是把事件当作“首次待投递”处理，而是转回 FAILED。
                 * 这样可以保留 attemptCount，运维侧能看到该事件曾经被 worker 领取过；
                 * nextRetryAt 设置为当前时间，表示它已经具备再次领取条件，下一轮 dispatcher 可以马上补偿重试。
                 */
                entry.setValue(record.markFailed(error, referenceTime, referenceTime));
                recovered++;
            }
            return recovered;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public AgentToolExecutionEventOutboxDiagnostics diagnostics() {
        lock.readLock().lock();
        try {
            return new AgentToolExecutionEventOutboxDiagnostics(
                    true,
                    recordsByOutboxId.size(),
                    count(AgentToolExecutionEventOutboxStatus.PENDING),
                    count(AgentToolExecutionEventOutboxStatus.PUBLISHING),
                    count(AgentToolExecutionEventOutboxStatus.PUBLISHED),
                    count(AgentToolExecutionEventOutboxStatus.FAILED),
                    count(AgentToolExecutionEventOutboxStatus.BLOCKED),
                    maxEventsPerRun,
                    maxTotalRecords,
                    Instant.now()
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    private List<AgentToolExecutionEventOutboxRecord> listByRunId(String runId,
                                                                  AgentToolExecutionEventOutboxStatus status,
                                                                  int limit) {
        Deque<String> outboxIds = outboxIdsByRunId.get(runId);
        if (outboxIds == null || outboxIds.isEmpty()) {
            return List.of();
        }
        List<AgentToolExecutionEventOutboxRecord> result = new ArrayList<>();
        for (String outboxId : outboxIds) {
            AgentToolExecutionEventOutboxRecord record = recordsByOutboxId.get(outboxId);
            if (record == null || (status != null && record.status() != status)) {
                continue;
            }
            result.add(record);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    private Optional<AgentToolExecutionEventOutboxRecord> replace(String outboxId,
                                                                  RecordMutation mutation) {
        return replaceIf(outboxId, ignored -> true, mutation);
    }

    /**
     * 带条件的记录替换。
     *
     * <p>dispatcher 4.21 开始需要模拟数据库的“条件更新领取”语义：只有仍处于 PENDING/FAILED 且到达 retry 时间的记录，
     * 才能被本轮 worker 领取为 PUBLISHING。这个判断放在写锁内完成，可以让内存实现也覆盖“两个 dispatcher 同时领取同一条记录”
     * 的竞态测试场景，而不是只在 MySQL 实现里保证。</p>
     */
    private Optional<AgentToolExecutionEventOutboxRecord> replaceIf(String outboxId,
                                                                    RecordPredicate predicate,
                                                                    RecordMutation mutation) {
        lock.writeLock().lock();
        try {
            AgentToolExecutionEventOutboxRecord current = recordsByOutboxId.get(outboxId);
            if (current == null || !predicate.test(current)) {
                return Optional.empty();
            }
            AgentToolExecutionEventOutboxRecord updated = mutation.apply(current);
            recordsByOutboxId.put(outboxId, updated);
            return Optional.of(updated);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean isPublishable(AgentToolExecutionEventOutboxRecord record, Instant referenceTime) {
        boolean statusAllowsClaim = record.status() == AgentToolExecutionEventOutboxStatus.PENDING
                || record.status() == AgentToolExecutionEventOutboxStatus.FAILED;
        return statusAllowsClaim && (record.nextRetryAt() == null || !record.nextRetryAt().isAfter(referenceTime));
    }

    private boolean isStalePublishing(AgentToolExecutionEventOutboxRecord record, Instant cutoff) {
        return record.status() == AgentToolExecutionEventOutboxStatus.PUBLISHING
                && record.updatedAt() != null
                && !record.updatedAt().isAfter(cutoff);
    }

    private void appendRunIndex(AgentToolExecutionEventOutboxRecord record) {
        if (!hasText(record.runId())) {
            return;
        }
        Deque<String> outboxIds = outboxIdsByRunId.computeIfAbsent(record.runId(), ignored -> new ArrayDeque<>());
        outboxIds.addLast(record.outboxId());
        while (outboxIds.size() > maxEventsPerRun) {
            String removedOutboxId = outboxIds.removeFirst();
            recordsByOutboxId.remove(removedOutboxId);
        }
    }

    private void trimGlobalWindow() {
        while (recordsByOutboxId.size() > maxTotalRecords) {
            String oldestOutboxId = recordsByOutboxId.keySet().iterator().next();
            AgentToolExecutionEventOutboxRecord removed = recordsByOutboxId.remove(oldestOutboxId);
            removeFromRunIndex(removed, oldestOutboxId);
        }
    }

    private void removeFromRunIndex(AgentToolExecutionEventOutboxRecord removed, String removedOutboxId) {
        if (removed == null || !hasText(removed.runId())) {
            return;
        }
        Deque<String> outboxIds = outboxIdsByRunId.get(removed.runId());
        if (outboxIds == null) {
            return;
        }
        outboxIds.remove(removedOutboxId);
        if (outboxIds.isEmpty()) {
            outboxIdsByRunId.remove(removed.runId());
        }
    }

    private int count(AgentToolExecutionEventOutboxStatus status) {
        return (int) recordsByOutboxId.values().stream()
                .filter(record -> record.status() == status)
                .count();
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 100;
        }
        return Math.min(limit, 1000);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @FunctionalInterface
    private interface RecordMutation {
        AgentToolExecutionEventOutboxRecord apply(AgentToolExecutionEventOutboxRecord record);
    }

    @FunctionalInterface
    private interface RecordPredicate {
        boolean test(AgentToolExecutionEventOutboxRecord record);
    }
}

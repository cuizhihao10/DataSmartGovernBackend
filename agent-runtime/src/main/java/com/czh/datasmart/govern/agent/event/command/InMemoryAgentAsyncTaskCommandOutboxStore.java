/**
 * @Author : Cui
 * @Date: 2026/05/31 17:10
 * @Description DataSmart Govern Backend - InMemoryAgentAsyncTaskCommandOutboxStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import com.czh.datasmart.govern.agent.config.AgentAsyncTaskCommandOutboxProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 内存版 Agent 异步命令 outbox 仓储。
 *
 * <p>该实现用于本地学习、单元测试和早期联调。它能验证 outbox 状态机、dispatcher 和查询能力，
 * 但不是生产级持久化：JVM 重启会丢记录，多实例也不会共享状态。生产环境后续应使用 MySQL store，
 * 并把“工具审计状态 + command outbox INSERT”放进同一个事务边界。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.async-task-commands.outbox",
        name = "store",
        havingValue = "memory",
        matchIfMissing = true
)
public class InMemoryAgentAsyncTaskCommandOutboxStore
        implements AgentAsyncTaskCommandOutboxStore, AgentAsyncTaskCommandOutboxOperationStore {

    private final Map<String, AgentAsyncTaskCommandOutboxRecord> recordsByOutboxId = new LinkedHashMap<>();
    private final Map<String, String> outboxIdByCommandId = new LinkedHashMap<>();
    private final Map<String, Deque<String>> outboxIdsByRunId = new LinkedHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final int maxCommandsPerRun;
    private final int maxTotalRecords;

    public InMemoryAgentAsyncTaskCommandOutboxStore(AgentAsyncTaskCommandOutboxProperties properties) {
        this.maxCommandsPerRun = Math.max(1, properties.getMaxCommandsPerRun());
        this.maxTotalRecords = Math.max(1, properties.getMaxTotalRecords());
    }

    public InMemoryAgentAsyncTaskCommandOutboxStore(int maxCommandsPerRun, int maxTotalRecords) {
        this.maxCommandsPerRun = Math.max(1, maxCommandsPerRun);
        this.maxTotalRecords = Math.max(1, maxTotalRecords);
    }

    @Override
    public boolean append(AgentAsyncTaskCommandOutboxRecord record) {
        lock.writeLock().lock();
        try {
            if (recordsByOutboxId.containsKey(record.outboxId())
                    || outboxIdByCommandId.containsKey(record.commandId())) {
                return false;
            }
            recordsByOutboxId.put(record.outboxId(), record);
            outboxIdByCommandId.put(record.commandId(), record.outboxId());
            appendRunIndex(record);
            trimGlobalWindow();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Optional<AgentAsyncTaskCommandOutboxRecord> findByOutboxId(String outboxId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(recordsByOutboxId.get(outboxId));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<AgentAsyncTaskCommandOutboxRecord> findByCommandId(String commandId) {
        lock.readLock().lock();
        try {
            String outboxId = outboxIdByCommandId.get(commandId);
            return outboxId == null ? Optional.empty() : Optional.ofNullable(recordsByOutboxId.get(outboxId));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<AgentAsyncTaskCommandOutboxRecord> list(String runId,
                                                        AgentAsyncTaskCommandOutboxStatus status,
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
    public long countByRunAndStatuses(String runId, Collection<AgentAsyncTaskCommandOutboxStatus> statuses) {
        lock.readLock().lock();
        try {
            if (!hasText(runId)) {
                return 0L;
            }
            return listByRunId(runId, null, Integer.MAX_VALUE).stream()
                    .filter(record -> statusMatches(record, statuses))
                    .count();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public long countByTenantAndStatuses(Long tenantId, Collection<AgentAsyncTaskCommandOutboxStatus> statuses) {
        lock.readLock().lock();
        try {
            if (tenantId == null) {
                return 0L;
            }
            return recordsByOutboxId.values().stream()
                    .filter(record -> tenantId.equals(record.tenantId()))
                    .filter(record -> statusMatches(record, statuses))
                    .count();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<AgentAsyncTaskCommandOutboxRecord> listPublishable(int limit, Instant now) {
        int normalizedLimit = normalizeLimit(limit);
        Instant referenceTime = now == null ? Instant.now() : now;
        lock.readLock().lock();
        try {
            return recordsByOutboxId.values().stream()
                    .filter(record -> record.status() == AgentAsyncTaskCommandOutboxStatus.PENDING
                            || record.status() == AgentAsyncTaskCommandOutboxStatus.FAILED)
                    .filter(record -> record.nextRetryAt() == null || !record.nextRetryAt().isAfter(referenceTime))
                    .limit(normalizedLimit)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<AgentAsyncTaskCommandOutboxRecord> markPublishing(String outboxId, Instant now) {
        Instant referenceTime = now == null ? Instant.now() : now;
        return replaceIf(outboxId,
                record -> isPublishable(record, referenceTime),
                record -> record.markPublishing(referenceTime));
    }

    @Override
    public Optional<AgentAsyncTaskCommandOutboxRecord> markPublished(String outboxId, Instant now) {
        return replace(outboxId, record -> record.markPublished(now == null ? Instant.now() : now));
    }

    @Override
    public Optional<AgentAsyncTaskCommandOutboxRecord> markFailed(String outboxId,
                                                                 String error,
                                                                 Instant now,
                                                                 Instant nextRetryAt) {
        Instant referenceTime = now == null ? Instant.now() : now;
        return replace(outboxId, record -> record.markFailed(error, referenceTime, nextRetryAt));
    }

    @Override
    public Optional<AgentAsyncTaskCommandOutboxRecord> markBlocked(String outboxId,
                                                                  String error,
                                                                  Instant now) {
        Instant referenceTime = now == null ? Instant.now() : now;
        return replace(outboxId, record -> record.markBlocked(error, referenceTime));
    }

    /**
     * 人工重新入队。
     *
     * <p>这里只允许 FAILED/BLOCKED/DEAD_LETTER 重新进入 PENDING。PUBLISHING 可能仍被某个 dispatcher 持有，
     * PUBLISHED 已经成功投递，IGNORED 是管理员明确归档；这些状态都不能被悄悄重排。</p>
     */
    @Override
    public Optional<AgentAsyncTaskCommandOutboxRecord> markRequeued(String outboxId,
                                                                    String reason,
                                                                    Instant now,
                                                                    Instant nextRetryAt) {
        Instant referenceTime = now == null ? Instant.now() : now;
        return replaceIf(outboxId,
                this::canRequeue,
                record -> record.markRequeued(reason, referenceTime, nextRetryAt));
    }

    /**
     * 人工转死信。
     *
     * <p>DEAD_LETTER 用来阻断自动重试热循环，通常由运维在确认失败原因短期无法自动恢复后执行。</p>
     */
    @Override
    public Optional<AgentAsyncTaskCommandOutboxRecord> markDeadLetter(String outboxId,
                                                                      String reason,
                                                                      Instant now) {
        Instant referenceTime = now == null ? Instant.now() : now;
        return replaceIf(outboxId,
                this::canDeadLetter,
                record -> record.markDeadLetter(reason, referenceTime));
    }

    /**
     * 人工忽略。
     *
     * <p>忽略只允许从失败、阻断或死信进入，避免把仍在等待投递或已经成功投递的命令误归档。</p>
     */
    @Override
    public Optional<AgentAsyncTaskCommandOutboxRecord> markIgnored(String outboxId,
                                                                   String reason,
                                                                   Instant now) {
        Instant referenceTime = now == null ? Instant.now() : now;
        return replaceIf(outboxId,
                this::canIgnore,
                record -> record.markIgnored(reason, referenceTime));
    }

    /**
     * 追加人工备注。
     */
    @Override
    public Optional<AgentAsyncTaskCommandOutboxRecord> appendOperationNote(String outboxId,
                                                                           String reason,
                                                                           Instant now) {
        Instant referenceTime = now == null ? Instant.now() : now;
        return replaceIf(outboxId,
                record -> record.status() != AgentAsyncTaskCommandOutboxStatus.PUBLISHED,
                record -> record.appendOperationNote(reason, referenceTime));
    }

    @Override
    public int recoverStalePublishing(Instant staleBefore, Instant now, String error) {
        Instant cutoff = staleBefore == null ? Instant.now() : staleBefore;
        Instant referenceTime = now == null ? Instant.now() : now;
        lock.writeLock().lock();
        try {
            int recovered = 0;
            for (Map.Entry<String, AgentAsyncTaskCommandOutboxRecord> entry : recordsByOutboxId.entrySet()) {
                AgentAsyncTaskCommandOutboxRecord record = entry.getValue();
                if (record.status() == AgentAsyncTaskCommandOutboxStatus.PUBLISHING
                        && record.updatedAt() != null
                        && !record.updatedAt().isAfter(cutoff)) {
                    entry.setValue(record.markFailed(error, referenceTime, referenceTime));
                    recovered++;
                }
            }
            return recovered;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public AgentAsyncTaskCommandOutboxDiagnostics diagnostics() {
        lock.readLock().lock();
        try {
            return new AgentAsyncTaskCommandOutboxDiagnostics(
                    true,
                    recordsByOutboxId.size(),
                    count(AgentAsyncTaskCommandOutboxStatus.PENDING),
                    count(AgentAsyncTaskCommandOutboxStatus.PUBLISHING),
                    count(AgentAsyncTaskCommandOutboxStatus.PUBLISHED),
                    count(AgentAsyncTaskCommandOutboxStatus.FAILED),
                    count(AgentAsyncTaskCommandOutboxStatus.BLOCKED),
                    count(AgentAsyncTaskCommandOutboxStatus.DEAD_LETTER),
                    count(AgentAsyncTaskCommandOutboxStatus.IGNORED),
                    maxCommandsPerRun,
                    maxTotalRecords,
                    Instant.now()
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    private List<AgentAsyncTaskCommandOutboxRecord> listByRunId(String runId,
                                                                AgentAsyncTaskCommandOutboxStatus status,
                                                                int limit) {
        Deque<String> outboxIds = outboxIdsByRunId.get(runId);
        if (outboxIds == null || outboxIds.isEmpty()) {
            return List.of();
        }
        List<AgentAsyncTaskCommandOutboxRecord> result = new ArrayList<>();
        for (String outboxId : outboxIds) {
            AgentAsyncTaskCommandOutboxRecord record = recordsByOutboxId.get(outboxId);
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

    private Optional<AgentAsyncTaskCommandOutboxRecord> replace(String outboxId,
                                                                RecordMutation mutation) {
        return replaceIf(outboxId, ignored -> true, mutation);
    }

    private Optional<AgentAsyncTaskCommandOutboxRecord> replaceIf(String outboxId,
                                                                  RecordPredicate predicate,
                                                                  RecordMutation mutation) {
        lock.writeLock().lock();
        try {
            AgentAsyncTaskCommandOutboxRecord current = recordsByOutboxId.get(outboxId);
            if (current == null || !predicate.test(current)) {
                return Optional.empty();
            }
            AgentAsyncTaskCommandOutboxRecord updated = mutation.apply(current);
            recordsByOutboxId.put(outboxId, updated);
            return Optional.of(updated);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private boolean isPublishable(AgentAsyncTaskCommandOutboxRecord record, Instant referenceTime) {
        boolean statusAllowsClaim = record.status() == AgentAsyncTaskCommandOutboxStatus.PENDING
                || record.status() == AgentAsyncTaskCommandOutboxStatus.FAILED;
        return statusAllowsClaim && (record.nextRetryAt() == null || !record.nextRetryAt().isAfter(referenceTime));
    }

    private boolean canRequeue(AgentAsyncTaskCommandOutboxRecord record) {
        return record.status() == AgentAsyncTaskCommandOutboxStatus.FAILED
                || record.status() == AgentAsyncTaskCommandOutboxStatus.BLOCKED
                || record.status() == AgentAsyncTaskCommandOutboxStatus.DEAD_LETTER;
    }

    private boolean canDeadLetter(AgentAsyncTaskCommandOutboxRecord record) {
        return record.status() == AgentAsyncTaskCommandOutboxStatus.FAILED
                || record.status() == AgentAsyncTaskCommandOutboxStatus.BLOCKED;
    }

    private boolean canIgnore(AgentAsyncTaskCommandOutboxRecord record) {
        return record.status() == AgentAsyncTaskCommandOutboxStatus.FAILED
                || record.status() == AgentAsyncTaskCommandOutboxStatus.BLOCKED
                || record.status() == AgentAsyncTaskCommandOutboxStatus.DEAD_LETTER;
    }

    private void appendRunIndex(AgentAsyncTaskCommandOutboxRecord record) {
        if (!hasText(record.runId())) {
            return;
        }
        Deque<String> outboxIds = outboxIdsByRunId.computeIfAbsent(record.runId(), ignored -> new ArrayDeque<>());
        outboxIds.addLast(record.outboxId());
        while (outboxIds.size() > maxCommandsPerRun) {
            String removedOutboxId = outboxIds.removeFirst();
            AgentAsyncTaskCommandOutboxRecord removed = recordsByOutboxId.remove(removedOutboxId);
            if (removed != null) {
                outboxIdByCommandId.remove(removed.commandId());
            }
        }
    }

    private void trimGlobalWindow() {
        while (recordsByOutboxId.size() > maxTotalRecords) {
            String oldestOutboxId = recordsByOutboxId.keySet().iterator().next();
            AgentAsyncTaskCommandOutboxRecord removed = recordsByOutboxId.remove(oldestOutboxId);
            if (removed != null) {
                outboxIdByCommandId.remove(removed.commandId());
                removeFromRunIndex(removed, oldestOutboxId);
            }
        }
    }

    private void removeFromRunIndex(AgentAsyncTaskCommandOutboxRecord removed, String removedOutboxId) {
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

    private int count(AgentAsyncTaskCommandOutboxStatus status) {
        return (int) recordsByOutboxId.values().stream()
                .filter(record -> record.status() == status)
                .count();
    }

    private boolean statusMatches(AgentAsyncTaskCommandOutboxRecord record,
                                  Collection<AgentAsyncTaskCommandOutboxStatus> statuses) {
        return statuses == null || statuses.isEmpty() || statuses.contains(record.status());
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
        AgentAsyncTaskCommandOutboxRecord apply(AgentAsyncTaskCommandOutboxRecord record);
    }

    @FunctionalInterface
    private interface RecordPredicate {
        boolean test(AgentAsyncTaskCommandOutboxRecord record);
    }
}

/**
 * @Author : Cui
 * @Date: 2026/08/19 00:00
 * @Description DataSmart Govern Backend - InMemoryAgentCommandTaskFinalStateCallbackJobStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 仅供单元测试使用的最终态 callback job 内存仓储。
 *
 * <p>该实现完整表达 source receipt 幂等、visibility lease、退避、死信和历史语义，便于无需 PostgreSQL 的单测
 * 验证状态机；它没有 Spring {@code @Component}，也不能跨 JVM/重启保存，因此自动 worker 的生产条件只装配
 * JDBC 实现，绝不能把本类用于无人值守实际回调。</p>
 */
public class InMemoryAgentCommandTaskFinalStateCallbackJobStore
        implements AgentCommandTaskFinalStateCallbackJobStore {

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, AgentToolActionWorkerReceiptIndexRecord> candidatesByIdentity = new LinkedHashMap<>();
    private final Map<String, AgentCommandTaskFinalStateCallbackJob> jobsById = new LinkedHashMap<>();
    private final Map<String, String> jobIdBySourceReceiptIdentity = new LinkedHashMap<>();
    private final List<AgentCommandTaskFinalStateCallbackHistoryRecord> histories = new ArrayList<>();

    /**
     * 向测试候选源加入一条 Java receipt 事实。
     *
     * <p>生产 JDBC 版本直接从 durable receipt index 查询；测试版本把候选显式注入，避免把测试数据构造
     * 与 worker 状态机耦合到数据库实现。</p>
     *
     * @param receipt 已完成白名单清洗的 Java receipt 索引记录。
     */
    void addCandidate(AgentToolActionWorkerReceiptIndexRecord receipt) {
        if (receipt == null || !receipt.indexable()) {
            return;
        }
        lock.lock();
        try {
            candidatesByIdentity.put(receipt.eventIdentityKey(), receipt);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回当前还没有 durable job 的候选，模拟 JDBC 的 source receipt anti-join。
     */
    @Override
    public List<AgentToolActionWorkerReceiptIndexRecord> listUnregisteredTerminalReceiptCandidates(int limit) {
        int appliedLimit = Math.max(1, limit);
        lock.lock();
        try {
            return candidatesByIdentity.values().stream()
                    .filter(receipt -> !jobIdBySourceReceiptIdentity.containsKey(receipt.eventIdentityKey()))
                    .sorted(Comparator
                            .comparing(this::replaySequence, Comparator.naturalOrder())
                            .thenComparing(AgentToolActionWorkerReceiptIndexRecord::consumedAt))
                    .limit(appliedLimit)
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 按 source receipt 唯一键创建 job 和第一条历史记录。
     */
    @Override
    public boolean append(AgentCommandTaskFinalStateCallbackJob job,
                          String eventType,
                          String reasonCode,
                          Instant now) {
        if (job == null || !hasText(job.jobId()) || !hasText(job.sourceReceiptIdentityKey())) {
            return false;
        }
        lock.lock();
        try {
            if (jobIdBySourceReceiptIdentity.containsKey(job.sourceReceiptIdentityKey())
                    || jobsById.containsKey(job.jobId())) {
                return false;
            }
            jobsById.put(job.jobId(), job);
            jobIdBySourceReceiptIdentity.put(job.sourceReceiptIdentityKey(), job.jobId());
            appendHistory(job, eventType, reasonCode, null, now);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 用单锁模拟数据库的条件领取：只有待处理、退避到期或可见性超时的 job 能被当前 worker 抢到。
     */
    @Override
    public List<AgentCommandTaskFinalStateCallbackJob> claimDue(String workerId,
                                                                String leaseToken,
                                                                Instant now,
                                                                Instant leaseExpiresAt,
                                                                int limit) {
        int appliedLimit = Math.max(1, limit);
        lock.lock();
        try {
            List<AgentCommandTaskFinalStateCallbackJob> claimed = new ArrayList<>();
            for (AgentCommandTaskFinalStateCallbackJob job : new ArrayList<>(jobsById.values())) {
                if (claimed.size() >= appliedLimit || !claimable(job, now)) {
                    continue;
                }
                AgentCommandTaskFinalStateCallbackJob updated = job.withClaim(workerId, leaseToken, leaseExpiresAt, now);
                jobsById.put(updated.jobId(), updated);
                appendHistory(updated, "CALLBACK_CLAIMED",
                        job.status() == AgentCommandTaskFinalStateCallbackJobStatus.DISPATCHING
                                ? "STALE_VISIBILITY_RECLAIMED" : null,
                        workerId, now);
                claimed.add(updated);
            }
            return List.copyOf(claimed);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 只有仍持有相同 owner/token 的实例可以延长可见性，模拟 JDBC 条件 UPDATE 的 fencing 语义。
     */
    @Override
    public boolean heartbeat(String jobId,
                             String workerId,
                             String leaseToken,
                             Instant leaseExpiresAt,
                             Instant now) {
        lock.lock();
        try {
            AgentCommandTaskFinalStateCallbackJob job = jobsById.get(jobId);
            if (job == null || !job.matchesLease(workerId, leaseToken, now)) {
                return false;
            }
            AgentCommandTaskFinalStateCallbackJob updated = job.withHeartbeat(leaseExpiresAt, now);
            jobsById.put(jobId, updated);
            appendHistory(updated, "CALLBACK_HEARTBEAT", null, workerId, now);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 保存下游已接受 callback 的最终记录；涉及副作用失败时仍显式保留 COMPENSATION_REQUIRED。
     */
    @Override
    public boolean markDelivered(String jobId,
                                 String workerId,
                                 String leaseToken,
                                 boolean requiresManualCompensation,
                                 Instant now) {
        lock.lock();
        try {
            AgentCommandTaskFinalStateCallbackJob job = jobsById.get(jobId);
            if (job == null || !job.matchesLease(workerId, leaseToken, now)) {
                return false;
            }
            AgentCommandTaskFinalStateCallbackJobStatus target = requiresManualCompensation
                    ? AgentCommandTaskFinalStateCallbackJobStatus.COMPENSATION_REQUIRED
                    : AgentCommandTaskFinalStateCallbackJobStatus.DELIVERED;
            AgentCommandTaskFinalStateCallbackJob updated = job.withTerminal(target,
                    requiresManualCompensation ? "POST_CALLBACK_MANUAL_COMPENSATION_REQUIRED" : null,
                    now, now);
            jobsById.put(jobId, updated);
            appendHistory(updated,
                    requiresManualCompensation ? "CALLBACK_DELIVERED_COMPENSATION_REQUIRED" : "CALLBACK_DELIVERED",
                    updated.failureCode(), workerId, now);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 将被更高 Java replay sequence 覆盖的旧 job 停止，避免过期事实再次触发 callback。
     */
    @Override
    public boolean markSuperseded(String jobId,
                                  String workerId,
                                  String leaseToken,
                                  String reasonCode,
                                  Instant now) {
        lock.lock();
        try {
            AgentCommandTaskFinalStateCallbackJob job = jobsById.get(jobId);
            if (job == null || !job.matchesLease(workerId, leaseToken, now)) {
                return false;
            }
            AgentCommandTaskFinalStateCallbackJob updated = job.withTerminal(
                    AgentCommandTaskFinalStateCallbackJobStatus.SUPERSEDED, reasonCode, null, now);
            jobsById.put(jobId, updated);
            appendHistory(updated, "CALLBACK_SUPERSEDED", reasonCode, workerId, now);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 把暂时失败的 job 变为 RETRY_WAIT，并保存下一次可见时间。
     */
    @Override
    public boolean markRetry(String jobId,
                             String workerId,
                             String leaseToken,
                             String failureCode,
                             Instant nextAttemptAt,
                             Instant now) {
        lock.lock();
        try {
            AgentCommandTaskFinalStateCallbackJob job = jobsById.get(jobId);
            if (job == null || !job.matchesLease(workerId, leaseToken, now)) {
                return false;
            }
            AgentCommandTaskFinalStateCallbackJob updated = job.withRetry(nextAttemptAt, failureCode, now);
            jobsById.put(jobId, updated);
            appendHistory(updated, "CALLBACK_RETRY_SCHEDULED", failureCode, workerId, now);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 将不可安全自动恢复的问题固定为人工补偿待办。
     */
    @Override
    public boolean markCompensationRequired(String jobId,
                                            String workerId,
                                            String leaseToken,
                                            String reasonCode,
                                            boolean callbackDelivered,
                                            Instant now) {
        lock.lock();
        try {
            AgentCommandTaskFinalStateCallbackJob job = jobsById.get(jobId);
            if (job == null || !job.matchesLease(workerId, leaseToken, now)) {
                return false;
            }
            AgentCommandTaskFinalStateCallbackJob updated = job.withTerminal(
                    AgentCommandTaskFinalStateCallbackJobStatus.COMPENSATION_REQUIRED,
                    reasonCode, callbackDelivered ? now : null, now);
            jobsById.put(jobId, updated);
            appendHistory(updated, "CALLBACK_COMPENSATION_REQUIRED", reasonCode, workerId, now);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 将耗尽自动重试次数的 job 写成死信，保证后续补偿台仍能看到来源与失败码。
     */
    @Override
    public boolean markDeadLetter(String jobId,
                                  String workerId,
                                  String leaseToken,
                                  String reasonCode,
                                  Instant now) {
        lock.lock();
        try {
            AgentCommandTaskFinalStateCallbackJob job = jobsById.get(jobId);
            if (job == null || !job.matchesLease(workerId, leaseToken, now)) {
                return false;
            }
            AgentCommandTaskFinalStateCallbackJob updated = job.withTerminal(
                    AgentCommandTaskFinalStateCallbackJobStatus.DEAD_LETTER, reasonCode, null, now);
            jobsById.put(jobId, updated);
            appendHistory(updated, "CALLBACK_DEAD_LETTERED", reasonCode, workerId, now);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 按 source receipt identity 查询当前 job。
     */
    @Override
    public Optional<AgentCommandTaskFinalStateCallbackJob> findBySourceReceiptIdentityKey(String sourceReceiptIdentityKey) {
        lock.lock();
        try {
            String jobId = jobIdBySourceReceiptIdentity.get(sourceReceiptIdentityKey);
            return jobId == null ? Optional.empty() : Optional.ofNullable(jobsById.get(jobId));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回指定 source receipt 的历史快照，避免调用方获得内部可变列表。
     */
    @Override
    public List<AgentCommandTaskFinalStateCallbackHistoryRecord> historyFor(String sourceReceiptIdentityKey) {
        lock.lock();
        try {
            return histories.stream()
                    .filter(history -> sourceReceiptIdentityKey != null
                            && sourceReceiptIdentityKey.equals(history.sourceReceiptIdentityKey()))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 判断一个 job 是否可由本轮重新领取。
     */
    private boolean claimable(AgentCommandTaskFinalStateCallbackJob job, Instant now) {
        if (job.status() == AgentCommandTaskFinalStateCallbackJobStatus.PENDING) {
            return job.nextAttemptAt() == null || !job.nextAttemptAt().isAfter(now);
        }
        if (job.status() == AgentCommandTaskFinalStateCallbackJobStatus.RETRY_WAIT) {
            return job.nextAttemptAt() != null && !job.nextAttemptAt().isAfter(now);
        }
        return job.status() == AgentCommandTaskFinalStateCallbackJobStatus.DISPATCHING
                && job.leaseExpiresAt() != null
                && !job.leaseExpiresAt().isAfter(now);
    }

    /**
     * 追加低敏历史事件；historyId 只用于表内定位，不参与下游 callback 幂等。
     */
    private void appendHistory(AgentCommandTaskFinalStateCallbackJob job,
                               String eventType,
                               String reasonCode,
                               String workerId,
                               Instant now) {
        histories.add(new AgentCommandTaskFinalStateCallbackHistoryRecord(
                "callback-history:" + UUID.randomUUID(),
                job.jobId(),
                job.sourceReceiptIdentityKey(),
                eventType == null ? "CALLBACK_STATE_CHANGED" : eventType,
                job.status(),
                reasonCode,
                job.attemptCount(),
                workerId,
                now == null ? Instant.now() : now
        ));
    }

    /**
     * 将空 replaySequence 统一排在有序扫描的最前面，保持测试和 JDBC 回退语义一致。
     */
    private Long replaySequence(AgentToolActionWorkerReceiptIndexRecord record) {
        return record.replaySequence() == null ? -1L : record.replaySequence();
    }

    /**
     * 判断低敏标识是否可用。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

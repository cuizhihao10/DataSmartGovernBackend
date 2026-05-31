/**
 * @Author : Cui
 * @Date: 2026/05/31 17:12
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxDispatcher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import com.czh.datasmart.govern.agent.config.AgentAsyncTaskCommandOutboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 异步任务命令 outbox dispatcher。
 *
 * <p>dispatcher 的职责是把 command outbox 中的 PENDING/FAILED 记录可靠投递到下游目标，
 * 成功后标记 PUBLISHED，失败后按退避策略写回 FAILED，连续失败后转入 BLOCKED。
 * 它不重新生成命令、不修改工具审计状态，也不直接创建 task-management 任务。</p>
 *
 * <p>这样拆分后，链路职责更清晰：</p>
 * <p>1. PlanningService：判断哪些 ASYNC_TASK 可以下发；</p>
 * <p>2. OutboxService：把可下发命令写入本地 outbox；</p>
 * <p>3. Dispatcher：可靠投递 command；</p>
 * <p>4. Task-management Inbox：消费去重并创建任务。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentAsyncTaskCommandOutboxDispatcher {

    private final AgentAsyncTaskCommandOutboxProperties properties;
    private final AgentAsyncTaskCommandOutboxStore outboxStore;
    private final List<AgentAsyncTaskCommandDispatchTarget> dispatchTargets;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 执行一轮投递。
     *
     * <p>该方法保持 public，方便运维接口、单元测试和未来手工补偿入口复用。</p>
     */
    public AgentAsyncTaskCommandOutboxDispatchSummary dispatchOnce() {
        if (!running.compareAndSet(false, true)) {
            return AgentAsyncTaskCommandOutboxDispatchSummary.skipped("ALREADY_RUNNING");
        }
        try {
            return doDispatchOnce();
        } finally {
            running.set(false);
        }
    }

    private AgentAsyncTaskCommandOutboxDispatchSummary doDispatchOnce() {
        Instant now = Instant.now();
        int recovered = recoverStalePublishing(now);
        List<AgentAsyncTaskCommandOutboxRecord> candidates = outboxStore.listPublishable(normalizeBatchSize(), now);
        int published = 0;
        int failed = 0;
        int blocked = 0;
        int skipped = 0;
        for (AgentAsyncTaskCommandOutboxRecord candidate : candidates) {
            DispatchOutcome outcome = dispatchRecord(candidate, now);
            published += outcome.published();
            failed += outcome.failed();
            blocked += outcome.blocked();
            skipped += outcome.skipped();
        }
        if (!candidates.isEmpty() || recovered > 0) {
            log.info("Agent 异步命令 outbox dispatcher 本轮完成，recovered={}, scanned={}, published={}, failed={}, blocked={}, skipped={}",
                    recovered, candidates.size(), published, failed, blocked, skipped);
        }
        return new AgentAsyncTaskCommandOutboxDispatchSummary(
                candidates.size(),
                published,
                failed,
                blocked,
                skipped,
                recovered,
                ""
        );
    }

    private int recoverStalePublishing(Instant now) {
        if (!properties.isDispatcherRecoverStalePublishingEnabled()) {
            return 0;
        }
        long timeoutSeconds = Math.max(1, properties.getDispatcherPublishingTimeoutSeconds());
        Instant staleBefore = now.minusSeconds(timeoutSeconds);
        return outboxStore.recoverStalePublishing(
                staleBefore,
                now,
                "异步命令 PUBLISHING 超过 " + timeoutSeconds + " 秒仍未完成，已恢复为 FAILED 等待补偿重试。"
        );
    }

    private DispatchOutcome dispatchRecord(AgentAsyncTaskCommandOutboxRecord candidate, Instant now) {
        return outboxStore.markPublishing(candidate.outboxId(), now)
                .map(record -> dispatchPublishingRecord(record, now))
                .orElseGet(DispatchOutcome::skippedOutcome);
    }

    private DispatchOutcome dispatchPublishingRecord(AgentAsyncTaskCommandOutboxRecord record, Instant now) {
        if (record.attemptCount() > Math.max(1, properties.getDispatcherMaxAttempts())) {
            outboxStore.markBlocked(record.outboxId(),
                    "超过异步命令 dispatcher 最大投递尝试次数，等待人工补偿或死信治理。",
                    now);
            return DispatchOutcome.blockedOutcome();
        }
        if (dispatchTargets.isEmpty() && !properties.isDispatcherAllowNoTargetsAsPublished()) {
            markFailed(record, "未配置异步命令投递目标，不能标记为已发布。", now);
            return DispatchOutcome.failedOutcome();
        }
        try {
            for (AgentAsyncTaskCommandDispatchTarget target : dispatchTargets) {
                target.dispatch(record);
            }
            outboxStore.markPublished(record.outboxId(), Instant.now());
            return DispatchOutcome.publishedOutcome();
        } catch (RuntimeException exception) {
            markFailed(record, exception.getMessage(), Instant.now());
            return DispatchOutcome.failedOutcome();
        }
    }

    private void markFailed(AgentAsyncTaskCommandOutboxRecord record, String error, Instant now) {
        Instant nextRetryAt = now.plusSeconds(calculateBackoffSeconds(record.attemptCount()));
        outboxStore.markFailed(record.outboxId(), error, now, nextRetryAt);
        log.warn("Agent 异步命令 outbox 投递失败，outboxId={}, commandId={}, attempt={}, nextRetryAt={}, error={}",
                record.outboxId(), record.commandId(), record.attemptCount(), nextRetryAt, error);
    }

    private long calculateBackoffSeconds(int attemptCount) {
        long base = Math.max(1, properties.getRetryBackoffSeconds());
        int exponent = Math.max(0, Math.min(attemptCount - 1, 10));
        long candidate = base * (1L << exponent);
        return Math.min(candidate, Math.max(base, properties.getDispatcherMaxRetryBackoffSeconds()));
    }

    private int normalizeBatchSize() {
        return Math.max(1, Math.min(properties.getDispatcherBatchSize(), 1000));
    }

    /**
     * 单轮投递摘要。
     */
    public record AgentAsyncTaskCommandOutboxDispatchSummary(
            int scanned,
            int published,
            int failed,
            int blocked,
            int skipped,
            int recovered,
            String skippedReason
    ) {

        public static AgentAsyncTaskCommandOutboxDispatchSummary skipped(String reason) {
            return new AgentAsyncTaskCommandOutboxDispatchSummary(0, 0, 0, 0, 1, 0, reason);
        }
    }

    private record DispatchOutcome(int published, int failed, int blocked, int skipped) {

        private static DispatchOutcome publishedOutcome() {
            return new DispatchOutcome(1, 0, 0, 0);
        }

        private static DispatchOutcome failedOutcome() {
            return new DispatchOutcome(0, 1, 0, 0);
        }

        private static DispatchOutcome blockedOutcome() {
            return new DispatchOutcome(0, 0, 1, 0);
        }

        private static DispatchOutcome skippedOutcome() {
            return new DispatchOutcome(0, 0, 0, 1);
        }
    }
}

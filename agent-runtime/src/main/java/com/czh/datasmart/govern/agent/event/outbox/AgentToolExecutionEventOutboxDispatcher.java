/**
 * @Author : Cui
 * @Date: 2026/05/28 20:10
 * @Description DataSmart Govern Backend - AgentToolExecutionEventOutboxDispatcher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.outbox;

import com.czh.datasmart.govern.agent.config.AgentToolExecutionEventOutboxProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 工具执行事件 outbox 后台投递器。
 *
 * <p>4.15 到 4.19 已经完成了 outbox 领域模型、内存/MySQL store、审计状态与 outbox 同事务写入。
 * 本类负责补齐下一段闭环：周期性扫描 PENDING/FAILED 记录，领取为 PUBLISHING，投递到下游目标，
 * 成功后标记 PUBLISHED，失败后标记 FAILED 并写入下一次重试时间。</p>
 *
 * <p>为什么 dispatcher 不调用 {@code DefaultAgentToolExecutionEventPublisher}：
 * publisher 属于“状态刚变化时”的同步发布链，其中包含 outbox sink。如果 dispatcher 从 outbox 取出事件后再调用 publisher，
 * 会把同一事件再次写回 outbox，形成递归或重复记录。因此 dispatcher 使用独立的
 * {@link AgentToolExecutionEventOutboxDispatchTarget}，直接把 outbox payload 投递到 Kafka、WebSocket、审计中心等目标。</p>
 *
 * <p>当前实现是单 JVM 内的最小可靠投递器，不做分布式锁。MySQL store 依赖 outboxId 状态更新和唯一键降低重复投递风险；
 * 多实例生产环境后续应继续增强“按状态条件更新领取”“stale PUBLISHING 恢复”“死信/人工补偿”和统一 sequence。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.tool-execution-events.outbox",
        name = "dispatcher-enabled",
        havingValue = "true"
)
public class AgentToolExecutionEventOutboxDispatcher {

    private final AgentToolExecutionEventOutboxProperties properties;
    private final AgentToolExecutionEventOutboxStore outboxStore;
    private final List<AgentToolExecutionEventOutboxDispatchTarget> dispatchTargets;

    /**
     * 当前 JVM 内的执行互斥标记。
     *
     * <p>Spring 的 fixedDelay 通常不会在同一个调度线程内重叠执行，但测试、手工调用或未来多线程调度器可能造成并发进入。
     * 这里用 AtomicBoolean 做本地防重入，避免同一 JVM 内两轮 dispatcher 同时领取同一批记录。</p>
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 周期性投递 outbox。
     *
     * <p>fixedDelay 表示上一轮执行结束后再等待配置时间进入下一轮。Spring 官方调度文档也建议用 fixedDelay
     * 表达“前一轮完成后再启动下一轮”的轮询型任务语义，这比 fixedRate 更适合 outbox 投递。</p>
     */
    @Scheduled(
            fixedDelayString = "${datasmart.agent-runtime.tool-execution-events.outbox.dispatcher-fixed-delay-ms:5000}",
            initialDelayString = "${datasmart.agent-runtime.tool-execution-events.outbox.dispatcher-initial-delay-ms:10000}"
    )
    public void dispatchScheduled() {
        dispatchOnce();
    }

    /**
     * 执行一轮 outbox 投递。
     *
     * <p>该方法保持 public，是为了让单元测试、运维诊断入口或未来手动补偿 API 可以复用同一套逻辑。
     * 返回 summary 方便测试和后续指标系统记录本轮扫描、成功、失败和跳过数量。</p>
     */
    public AgentToolExecutionEventOutboxDispatchSummary dispatchOnce() {
        if (!running.compareAndSet(false, true)) {
            log.debug("Agent 工具 outbox dispatcher 上一轮仍在执行，跳过本轮。");
            return AgentToolExecutionEventOutboxDispatchSummary.skipped("ALREADY_RUNNING");
        }
        try {
            return doDispatchOnce();
        } finally {
            running.set(false);
        }
    }

    private AgentToolExecutionEventOutboxDispatchSummary doDispatchOnce() {
        Instant now = Instant.now();
        int batchSize = normalizeBatchSize();
        List<AgentToolExecutionEventOutboxRecord> candidates = outboxStore.listPublishable(batchSize, now);
        int published = 0;
        int failed = 0;
        int blocked = 0;
        int skipped = 0;
        for (AgentToolExecutionEventOutboxRecord candidate : candidates) {
            DispatchOutcome outcome = dispatchRecord(candidate, now);
            published += outcome.published();
            failed += outcome.failed();
            blocked += outcome.blocked();
            skipped += outcome.skipped();
        }
        if (!candidates.isEmpty()) {
            log.info("Agent 工具 outbox dispatcher 本轮完成，scanned={}, published={}, failed={}, blocked={}, skipped={}",
                    candidates.size(), published, failed, blocked, skipped);
        }
        return new AgentToolExecutionEventOutboxDispatchSummary(candidates.size(), published, failed, blocked, skipped, "");
    }

    private DispatchOutcome dispatchRecord(AgentToolExecutionEventOutboxRecord candidate, Instant now) {
        return outboxStore.markPublishing(candidate.outboxId(), now)
                .map(record -> dispatchPublishingRecord(record, now))
                .orElseGet(() -> {
                    log.debug("Agent 工具 outbox 记录领取失败，可能已被其他 dispatcher 处理，outboxId={}", candidate.outboxId());
            return DispatchOutcome.skippedOutcome();
                });
    }

    private DispatchOutcome dispatchPublishingRecord(AgentToolExecutionEventOutboxRecord record, Instant now) {
        if (record.attemptCount() > Math.max(1, properties.getDispatcherMaxAttempts())) {
            markBlocked(record, "超过 dispatcher 最大投递尝试次数，已停止自动重试，等待人工补偿或死信治理。", now);
            return DispatchOutcome.blockedOutcome();
        }
        if (dispatchTargets.isEmpty() && !properties.isDispatcherAllowNoTargetsAsPublished()) {
            markFailed(record, "dispatcher 未配置任何投递目标，不能标记为已发布。", now);
            return DispatchOutcome.failedOutcome();
        }
        try {
            for (AgentToolExecutionEventOutboxDispatchTarget target : dispatchTargets) {
                target.dispatch(record);
            }
            outboxStore.markPublished(record.outboxId(), Instant.now());
            return DispatchOutcome.publishedOutcome();
        } catch (RuntimeException exception) {
            markFailed(record, exception.getMessage(), Instant.now());
            return DispatchOutcome.failedOutcome();
        }
    }

    private void markFailed(AgentToolExecutionEventOutboxRecord record, String error, Instant now) {
        Instant nextRetryAt = now.plusSeconds(calculateBackoffSeconds(record.attemptCount()));
        outboxStore.markFailed(record.outboxId(), error, now, nextRetryAt);
        log.warn("Agent 工具 outbox 事件投递失败，outboxId={}, eventId={}, attempt={}, nextRetryAt={}, error={}",
                record.outboxId(), record.eventId(), record.attemptCount(), nextRetryAt, error);
    }

    private void markBlocked(AgentToolExecutionEventOutboxRecord record, String error, Instant now) {
        outboxStore.markBlocked(record.outboxId(), error, now);
        log.error("Agent 工具 outbox 事件已进入 BLOCKED，outboxId={}, eventId={}, attempt={}, error={}",
                record.outboxId(), record.eventId(), record.attemptCount(), error);
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

    public record AgentToolExecutionEventOutboxDispatchSummary(
            int scanned,
            int published,
            int failed,
            int blocked,
            int skipped,
            String skippedReason
    ) {

        public static AgentToolExecutionEventOutboxDispatchSummary skipped(String reason) {
            return new AgentToolExecutionEventOutboxDispatchSummary(0, 0, 0, 0, 1, reason);
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

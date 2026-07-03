/**
 * @Author : Cui
 * @Date: 2026/05/31 17:12
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxDispatcher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import com.czh.datasmart.govern.agent.config.AgentAsyncTaskCommandOutboxProperties;
import com.czh.datasmart.govern.agent.service.execution.AgentAsyncTaskCommandPreCheckService;
import com.czh.datasmart.govern.agent.service.execution.AgentAsyncTaskCommandPreCheckVerdict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
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
public class AgentAsyncTaskCommandOutboxDispatcher {

    private final AgentAsyncTaskCommandOutboxProperties properties;
    private final AgentAsyncTaskCommandOutboxStore outboxStore;
    private final List<AgentAsyncTaskCommandDispatchTarget> dispatchTargets;
    private final AgentAsyncTaskCommandPreCheckService preCheckService;
    private final AgentAsyncTaskCommandPreCheckRuntimeEventPublisher preCheckRuntimeEventPublisher;
    private final AgentAsyncTaskCommandPreCheckMetricsService preCheckMetricsService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Spring 生产路径构造函数。
     *
     * <p>生产路径注入 preCheckService、preCheckRuntimeEventPublisher 和 preCheckMetricsService，但是否执行 pre-check 仍由
     * {@code datasmart.agent-runtime.async-task-commands.outbox.dispatcher-pre-check-enabled} 控制。
     * 这样可以在不破坏本地学习和历史 command 的前提下，把集成/生产环境逐步切到 fail-closed worker 前置复核；
     * 一旦复核阻断或暂缓，运行时事件会进入投影层，指标会进入 Prometheus 聚合层：
     * 前者解释单次 run，后者支撑阻断率、暂缓率、确认过期率和容量暂缓率告警。</p>
     */
    @Autowired
    public AgentAsyncTaskCommandOutboxDispatcher(AgentAsyncTaskCommandOutboxProperties properties,
                                                 AgentAsyncTaskCommandOutboxStore outboxStore,
                                                 List<AgentAsyncTaskCommandDispatchTarget> dispatchTargets,
                                                 AgentAsyncTaskCommandPreCheckService preCheckService,
                                                 AgentAsyncTaskCommandPreCheckRuntimeEventPublisher preCheckRuntimeEventPublisher,
                                                 AgentAsyncTaskCommandPreCheckMetricsService preCheckMetricsService) {
        this.properties = properties;
        this.outboxStore = outboxStore;
        this.dispatchTargets = dispatchTargets;
        this.preCheckService = preCheckService;
        this.preCheckRuntimeEventPublisher = preCheckRuntimeEventPublisher;
        this.preCheckMetricsService = preCheckMetricsService;
    }

    public AgentAsyncTaskCommandOutboxDispatcher(AgentAsyncTaskCommandOutboxProperties properties,
                                                 AgentAsyncTaskCommandOutboxStore outboxStore,
                                                 List<AgentAsyncTaskCommandDispatchTarget> dispatchTargets,
                                                 AgentAsyncTaskCommandPreCheckService preCheckService,
                                                 AgentAsyncTaskCommandPreCheckRuntimeEventPublisher preCheckRuntimeEventPublisher) {
        this(properties, outboxStore, dispatchTargets, preCheckService, preCheckRuntimeEventPublisher, null);
    }

    public AgentAsyncTaskCommandOutboxDispatcher(AgentAsyncTaskCommandOutboxProperties properties,
                                                 AgentAsyncTaskCommandOutboxStore outboxStore,
                                                 List<AgentAsyncTaskCommandDispatchTarget> dispatchTargets,
                                                 AgentAsyncTaskCommandPreCheckService preCheckService) {
        this(properties, outboxStore, dispatchTargets, preCheckService, null, null);
    }

    /**
     * 旧单元测试兼容构造函数。
     *
     * <p>已有 dispatcher 测试只验证 outbox 投递状态机，并不需要完整构造 session/audit/confirmation。
     * 因此三参数构造函数保留为 pre-check 关闭路径；新增 pre-check 测试如果要验证事件投影/指标，应使用五参数或六参数构造函数。</p>
     */
    public AgentAsyncTaskCommandOutboxDispatcher(AgentAsyncTaskCommandOutboxProperties properties,
                                                 AgentAsyncTaskCommandOutboxStore outboxStore,
                                                 List<AgentAsyncTaskCommandDispatchTarget> dispatchTargets) {
        this(properties, outboxStore, dispatchTargets, null);
    }

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
        DispatchOutcome preCheckOutcome = inspectBeforeDispatch(record, now);
        if (preCheckOutcome != null) {
            return preCheckOutcome;
        }
        List<AgentAsyncTaskCommandDispatchTarget> applicableTargets = dispatchTargets.stream()
                .filter(target -> target.supports(record))
                .toList();
        if (applicableTargets.isEmpty() && !properties.isDispatcherAllowNoTargetsAsPublished()) {
            markFailed(record, "未配置异步命令投递目标，不能标记为已发布。", now);
            return DispatchOutcome.failedOutcome();
        }
        try {
            for (AgentAsyncTaskCommandDispatchTarget target : applicableTargets) {
                target.dispatch(record);
            }
            outboxStore.markPublished(record.outboxId(), Instant.now());
            return DispatchOutcome.publishedOutcome();
        } catch (RuntimeException exception) {
            markFailed(record, exception.getMessage(), Instant.now());
            return DispatchOutcome.failedOutcome();
        }
    }

    private DispatchOutcome inspectBeforeDispatch(AgentAsyncTaskCommandOutboxRecord record, Instant now) {
        if (!properties.isDispatcherPreCheckEnabled()) {
            return null;
        }
        if (preCheckService == null) {
            markFailed(record, "异步命令 dispatcher 已启用 pre-check，但 AgentAsyncTaskCommandPreCheckService 未注入。", now);
            return DispatchOutcome.failedOutcome();
        }
        AgentAsyncTaskCommandPreCheckVerdict verdict = preCheckService.inspect(record);
        recordPreCheckMetrics(record, verdict);
        if (Boolean.TRUE.equals(verdict.allowed())) {
            return null;
        }
        String message = preCheckMessage(verdict);
        publishPreCheckRuntimeEvent(record, verdict);
        if ("DEFERRED".equals(verdict.decision())) {
            markFailed(record, message, now);
            return DispatchOutcome.failedOutcome();
        }
        outboxStore.markBlocked(record.outboxId(), message, now);
        log.warn("Agent 异步命令 pre-check 阻断投递，outboxId={}, commandId={}, decision={}, issueCodes={}",
                record.outboxId(), record.commandId(), verdict.decision(), verdict.issueCodes());
        return DispatchOutcome.blockedOutcome();
    }

    private void publishPreCheckRuntimeEvent(AgentAsyncTaskCommandOutboxRecord record,
                                             AgentAsyncTaskCommandPreCheckVerdict verdict) {
        if (preCheckRuntimeEventPublisher != null) {
            preCheckRuntimeEventPublisher.publish(record, verdict);
        }
    }

    private void recordPreCheckMetrics(AgentAsyncTaskCommandOutboxRecord record,
                                       AgentAsyncTaskCommandPreCheckVerdict verdict) {
        if (preCheckMetricsService != null) {
            preCheckMetricsService.recordVerdict(record, verdict);
        }
    }

    private String preCheckMessage(AgentAsyncTaskCommandPreCheckVerdict verdict) {
        return "Agent 异步命令执行前复核未通过，decision=" + Objects.toString(verdict.decision(), "UNKNOWN")
                + "，issueCodes=" + verdict.issueCodes()
                + "，confirmationId=" + Objects.toString(verdict.confirmationId(), "")
                + "。";
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

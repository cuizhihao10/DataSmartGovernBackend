/**
 * @Author : Cui
 * @Date: 2026/08/19 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateCallbackWorker.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentCommandTaskFinalStateCallbackWorkerProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateCallbackDispatchRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateCallbackDispatchResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateLatestReceiptView;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandTaskFinalStateReconciliationResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 异步任务最终态的无人值守 callback 收敛 worker。
 *
 * <p>它只接受 Java 已物化的真实 worker receipt，先持久化 callback job，再用 visibility lease 领取，
 * 每次写下游前重新调用 Java reconciliation。AUTO_APPROVED 只代表审批，PUBLISHED 只代表 outbox 投递，
 * 两者都不在 job factory 白名单内，因此无法被当作执行成功。</p>
 *
 * <p>副作用顺序固定为：Java receipt 事实 -> durable job/历史 -> 条件领取/heartbeat -> 重新对账 ->
 * 既有 dispatch service -> DELIVERED、RETRY_WAIT、COMPENSATION_REQUIRED 或 DEAD_LETTER。这样即使进程
 * 在 HTTP 成功后崩溃，租约超时后仍会带同一个下游幂等键重试，而不会丢失最终态。</p>
 */
@Slf4j
@Component
@DependsOn("agentCommandTaskFinalStateCallbackWorkerPrerequisiteValidator")
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.async-task-final-state-callback-worker",
        name = "enabled",
        havingValue = "true"
)
public class AgentCommandTaskFinalStateCallbackWorker {

    private static final String METRIC_NAME = "agent_command_final_state_callback_worker_total";
    private static final int RECONCILIATION_LIMIT = 20;

    private final AgentCommandTaskFinalStateCallbackWorkerProperties properties;
    private final AgentCommandTaskFinalStateCallbackJobStore jobStore;
    private final AgentCommandTaskFinalStateReconciliationService reconciliationService;
    private final AgentCommandTaskFinalStateCallbackDispatcher dispatcher;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Spring 生产构造器。
     *
     * <p>组件只会在 {@code enabled=true} 时创建，而 durable job store 也只在 PostgreSQL/JDBC 配置完成时提供。
     * 因此误把开关打开但没有数据库持久化时，应用会在启动期明确失败，而不是悄悄使用内存 job 后产生不可恢复副作用。</p>
     */
    @Autowired
    public AgentCommandTaskFinalStateCallbackWorker(
            AgentCommandTaskFinalStateCallbackWorkerProperties properties,
            AgentCommandTaskFinalStateCallbackJobStore jobStore,
            AgentCommandTaskFinalStateReconciliationService reconciliationService,
            AgentCommandTaskFinalStateCallbackDispatcher dispatcher,
            MeterRegistry meterRegistry) {
        this(properties, jobStore, reconciliationService, dispatcher, Clock.systemUTC(), meterRegistry);
    }

    /**
     * 面向单元测试的构造器，允许注入固定 Clock 和轻量 MeterRegistry，避免 retry 测试依赖真实时间。
     */
    AgentCommandTaskFinalStateCallbackWorker(
            AgentCommandTaskFinalStateCallbackWorkerProperties properties,
            AgentCommandTaskFinalStateCallbackJobStore jobStore,
            AgentCommandTaskFinalStateReconciliationService reconciliationService,
            AgentCommandTaskFinalStateCallbackDispatcher dispatcher,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.jobStore = jobStore;
        this.reconciliationService = reconciliationService;
        this.dispatcher = dispatcher;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 执行一轮“发现 -> 持久化 -> 领取 -> 对账 -> callback”流程。
     *
     * <p>AtomicBoolean 防止同 JVM 的 scheduler、运维触发或测试并发进入；跨实例互斥由数据库的 visibility lease
     * 条件更新保证。返回摘要只包含计数和低敏跳过码，便于 Micrometer 与日志共同排障。</p>
     *
     * @return 本轮各类 job 的低敏统计。
     */
    public RunSummary runOnce() {
        if (!running.compareAndSet(false, true)) {
            recordMetric("skipped", "already_running");
            return RunSummary.skipped("ALREADY_RUNNING");
        }
        try {
            Instant now = clock.instant();
            int registered = discoverAndPersist(now);
            int claimedCount = 0;
            int delivered = 0;
            int retried = 0;
            int compensationRequired = 0;
            int deadLettered = 0;
            int skipped = 0;
            /*
             * 单线程 worker 不预先锁住整批 job。每次只在即将处理时领取一条，避免前一条 HTTP 调用
             * 消耗时间后，后续 job 尚未开始处理就已经失去 visibility lease。
             */
            for (int index = 0; index < batchSize(); index++) {
                Instant claimNow = clock.instant();
                String leaseToken = newLeaseToken();
                List<AgentCommandTaskFinalStateCallbackJob> claimed = jobStore.claimDue(
                        workerId(), leaseToken, claimNow,
                        claimNow.plusSeconds(visibilityTimeoutSeconds()), 1);
                if (claimed.isEmpty()) {
                    break;
                }
                claimedCount++;
                AgentCommandTaskFinalStateCallbackJob job = claimed.getFirst();
                ProcessingOutcome outcome = processClaimed(job, leaseToken);
                delivered += outcome.delivered();
                retried += outcome.retried();
                compensationRequired += outcome.compensationRequired();
                deadLettered += outcome.deadLettered();
                skipped += outcome.skipped();
            }
            RunSummary summary = new RunSummary(registered, claimedCount, delivered, retried,
                    compensationRequired, deadLettered, skipped, null);
            recordSummary(summary);
            return summary;
        } finally {
            running.set(false);
        }
    }

    /**
     * 从 durable Java receipt index 发现候选并按 source receipt identity 幂等写入 job。
     */
    private int discoverAndPersist(Instant now) {
        int registered = 0;
        for (AgentToolActionWorkerReceiptIndexRecord receipt
                : jobStore.listUnregisteredTerminalReceiptCandidates(batchSize())) {
            var job = AgentCommandTaskFinalStateCallbackJobFactory.create(receipt, now);
            if (job.isPresent()
                    && jobStore.append(job.get(), "CALLBACK_DISCOVERED", "JAVA_TERMINAL_WORKER_RECEIPT", now)) {
                registered++;
                recordMetric("job", "discovered");
            }
        }
        return registered;
    }

    /**
     * 处理已领取的 job：先验证 Java 最新事实，再续租并调用既有 dispatch service。
     */
    private ProcessingOutcome processClaimed(AgentCommandTaskFinalStateCallbackJob job, String leaseToken) {
        AgentCommandTaskFinalStateReconciliationResponse reconciliation;
        try {
            reconciliation = reconcile(job);
        } catch (RuntimeException exception) {
            return retryOrDeadLetter(job, leaseToken, "JAVA_RECONCILIATION_UNAVAILABLE", clock.instant());
        }
        AgentCommandTaskFinalStateLatestReceiptView latest = reconciliation.latestReceipt();
        if (latest == null) {
            return compensationRequired(job, leaseToken, "JAVA_RECEIPT_FACT_NOT_FOUND", false, clock.instant());
        }
        if (job.sourceReplaySequence() == null || latest.replaySequence() == null) {
            return compensationRequired(job, leaseToken, "JAVA_RECEIPT_REPLAY_SEQUENCE_MISSING", false, clock.instant());
        }
        if (!sameReplaySequence(job, latest)) {
            boolean changed = jobStore.markSuperseded(job.jobId(), workerId(), leaseToken,
                    "JAVA_RECEIPT_SUPERSEDED_BY_NEWER_REPLAY_SEQUENCE", clock.instant());
            return changed ? ProcessingOutcome.supersededOutcome() : ProcessingOutcome.skippedOutcome();
        }
        if (!isTerminalDispatchable(reconciliation, latest)) {
            return compensationRequired(job, leaseToken, "JAVA_RECONCILIATION_NOT_TERMINAL", false, clock.instant());
        }
        if (!Objects.equals(job.callbackIdempotencyKey(), reconciliation.callbackSuggestion().idempotencyKeyHint())) {
            return compensationRequired(job, leaseToken, "JAVA_CALLBACK_IDEMPOTENCY_KEY_MISMATCH", false, clock.instant());
        }
        if (!hasCompleteCallbackLink(latest)) {
            return compensationRequired(job, leaseToken, "TASK_CALLBACK_LINK_INCOMPLETE", false, clock.instant());
        }
        Instant heartbeatNow = clock.instant();
        if (!jobStore.heartbeat(job.jobId(), workerId(), leaseToken,
                heartbeatNow.plusSeconds(visibilityTimeoutSeconds()), heartbeatNow)) {
            recordMetric("skipped", "lease_lost_before_dispatch");
            return ProcessingOutcome.skippedOutcome();
        }
        try {
            AgentCommandTaskFinalStateCallbackDispatchResponse response = dispatcher.dispatch(
                    dispatchRequest(latest), trustedWorkerAccess(), traceId(job),
                    AgentCommandTaskFinalStateCallbackDispatchExpectation.fromJob(job));
            if (response == null) {
                return retryOrDeadLetter(job, leaseToken, "TASK_MANAGEMENT_CALLBACK_EMPTY_RESPONSE", clock.instant());
            }
            if (!Objects.equals(job.callbackIdempotencyKey(), response.idempotencyKey())) {
                return compensationRequired(job, leaseToken, "JAVA_CALLBACK_IDEMPOTENCY_KEY_MISMATCH", false,
                        clock.instant());
            }
            if (Boolean.TRUE.equals(response.dispatched()) && Boolean.TRUE.equals(response.downstreamAccepted())) {
                boolean updated = jobStore.markDelivered(job.jobId(), workerId(), leaseToken,
                        Boolean.TRUE.equals(reconciliation.requiresManualCompensation()), clock.instant());
                return updated
                        ? (Boolean.TRUE.equals(reconciliation.requiresManualCompensation())
                        ? ProcessingOutcome.compensationRequiredOutcome() : ProcessingOutcome.deliveredOutcome())
                        : ProcessingOutcome.skippedOutcome();
            }
            if (retryableDownstreamFailure(response)) {
                return retryOrDeadLetter(job, leaseToken, safeFailureCode(response.deliveryStatus()), clock.instant());
            }
            return compensationRequired(job, leaseToken,
                    "FINAL_STATE_CALLBACK_" + safeFailureCode(response.deliveryStatus()), false, clock.instant());
        } catch (RuntimeException exception) {
            return retryOrDeadLetter(job, leaseToken, "TASK_MANAGEMENT_CALLBACK_EXCEPTION", clock.instant());
        }
    }

    /**
     * 在 Java 服务账号范围内重新对账，查询条件完全来自已持久化 receipt job，不接收外部用户参数。
     */
    private AgentCommandTaskFinalStateReconciliationResponse reconcile(AgentCommandTaskFinalStateCallbackJob job) {
        return reconciliationService.reconcile(
                job.commandId(), job.toolCode(), job.tenantId(), job.projectId(), job.actorId(),
                job.runId(), job.sessionId(), RECONCILIATION_LIMIT, trustedWorkerAccess());
    }

    /**
     * 将可恢复下游故障写为 RETRY_WAIT；最后一次失败直接进入 DEAD_LETTER，避免无限重试。
     */
    private ProcessingOutcome retryOrDeadLetter(AgentCommandTaskFinalStateCallbackJob job,
                                                String leaseToken,
                                                String failureCode,
                                                Instant now) {
        if (job.attemptCount() >= maxAttempts()) {
            boolean changed = jobStore.markDeadLetter(job.jobId(), workerId(), leaseToken,
                    failureCode + "_MAX_ATTEMPTS_REACHED", now);
            return changed ? ProcessingOutcome.deadLetteredOutcome() : ProcessingOutcome.skippedOutcome();
        }
        Instant nextAttemptAt = now.plusSeconds(backoffSeconds(job.attemptCount()));
        boolean changed = jobStore.markRetry(job.jobId(), workerId(), leaseToken, failureCode, nextAttemptAt, now);
        return changed ? ProcessingOutcome.retriedOutcome() : ProcessingOutcome.skippedOutcome();
    }

    /**
     * 将当前事实无法安全自动处理的场景送入人工补偿队列，并保留是否已经成功发出失败 callback 的事实。
     */
    private ProcessingOutcome compensationRequired(AgentCommandTaskFinalStateCallbackJob job,
                                                    String leaseToken,
                                                    String reasonCode,
                                                    boolean callbackDelivered,
                                                    Instant now) {
        boolean changed = jobStore.markCompensationRequired(job.jobId(), workerId(), leaseToken,
                reasonCode, callbackDelivered, now);
        return changed ? ProcessingOutcome.compensationRequiredOutcome() : ProcessingOutcome.skippedOutcome();
    }

    /**
     * 校验当前 latest receipt 与 job 创建时的 Java replay sequence 相同。
     *
     * <p>自动 callback 必须带 sequence 才能防止“旧失败 receipt 在新成功/重试 receipt 到达后仍被投递”。
     * 缺 sequence 的历史事实会转人工补偿，不靠时间戳猜测顺序。</p>
     */
    private boolean sameReplaySequence(AgentCommandTaskFinalStateCallbackJob job,
                                       AgentCommandTaskFinalStateLatestReceiptView latest) {
        return job.sourceReplaySequence() != null
                && latest.replaySequence() != null
                && job.sourceReplaySequence().equals(latest.replaySequence());
    }

    /**
     * 判断 reconciliation 是否明确给出了可自动写入的 SUCCEEDED 或 FAILED 最终态。
     */
    private boolean isTerminalDispatchable(AgentCommandTaskFinalStateReconciliationResponse reconciliation,
                                           AgentCommandTaskFinalStateLatestReceiptView latest) {
        if (!Boolean.TRUE.equals(reconciliation.terminal())
                || !Boolean.TRUE.equals(reconciliation.callbackRecommended())
                || reconciliation.callbackSuggestion() == null) {
            return false;
        }
        String callbackStatus = normalizedCode(reconciliation.callbackSuggestion().callbackStatus());
        if ("SUCCEEDED".equals(callbackStatus)) {
            return Boolean.TRUE.equals(latest.preCheckPassed())
                    && Boolean.TRUE.equals(latest.sideEffectExecuted());
        }
        return "FAILED".equals(callbackStatus);
    }

    /**
     * 缺少任务、运行或执行器关联键时 fail-closed，避免 worker 根据 commandId 猜测下游租约。
     */
    private boolean hasCompleteCallbackLink(AgentCommandTaskFinalStateLatestReceiptView latest) {
        return latest.taskId() != null && latest.taskRunId() != null && hasText(latest.executorId());
    }

    /**
     * 从最新 receipt 组装既有 dispatch service 的请求，并明确关闭 dry-run 才允许产生真实 callback。
     */
    private AgentCommandTaskFinalStateCallbackDispatchRequest dispatchRequest(
            AgentCommandTaskFinalStateLatestReceiptView latest) {
        AgentCommandTaskFinalStateCallbackDispatchRequest request = new AgentCommandTaskFinalStateCallbackDispatchRequest();
        request.setCommandId(latest.commandId());
        request.setToolCode(latest.toolCode());
        request.setTenantId(latest.tenantId());
        request.setProjectId(latest.projectId());
        request.setActorId(latest.actorId());
        request.setRunId(latest.runId());
        request.setSessionId(latest.sessionId());
        request.setLimit(RECONCILIATION_LIMIT);
        request.setDryRun(false);
        request.setIncludeNonTerminalProgressCallback(false);
        return request;
    }

    /**
     * 提供内部服务账号查询上下文；实际范围仍由 job 中持久化的 Java receipt 边界限制。
     */
    private AgentRuntimeEventQueryAccessContext trustedWorkerAccess() {
        return new AgentRuntimeEventQueryAccessContext(
                0L, 0L, "SERVICE_ACCOUNT", "agent-final-state-callback-worker", "PLATFORM", List.of());
    }

    /**
     * 判断 dispatch service 给出的状态是否可以按幂等键重试。
     */
    private boolean retryableDownstreamFailure(AgentCommandTaskFinalStateCallbackDispatchResponse response) {
        String status = response == null ? "" : normalizedCode(response.deliveryStatus());
        return "FAILED_DOWNSTREAM_UNAVAILABLE".equals(status)
                || "FAILED_DOWNSTREAM_RATE_LIMITED".equals(status);
    }

    /**
     * 计算有上限的指数退避秒数。
     */
    private long backoffSeconds(int attemptCount) {
        long base = Math.max(1L, properties.getInitialBackoffSeconds());
        int exponent = Math.max(0, Math.min(attemptCount - 1, 10));
        long candidate = base * (1L << exponent);
        return Math.min(candidate, Math.max(base, properties.getMaxBackoffSeconds()));
    }

    /** 返回单轮允许领取的上限，避免异常配置形成无界扫描。 */
    private int batchSize() {
        return Math.max(1, Math.min(properties.getBatchSize(), 500));
    }

    /** 返回最大自动尝试次数，至少允许一次真实 callback。 */
    private int maxAttempts() {
        return Math.max(1, Math.min(properties.getMaxAttempts(), 20));
    }

    /** 返回 visibility timeout，确保同步 HTTP 超时后仍能通过租约恢复。 */
    private long visibilityTimeoutSeconds() {
        long configured = Math.max(5L, Math.min(properties.getVisibilityTimeoutSeconds(), 3600L));
        return Math.max(configured, properties.minimumVisibilityTimeoutSeconds());
    }

    /** 返回固定且低敏的 worker 标识，空配置时保留默认名称。 */
    private String workerId() {
        return hasText(properties.getWorkerId())
                ? properties.getWorkerId().trim()
                : "agent-runtime-final-state-callback-worker";
    }

    /** 为本轮领取生成仅供数据库 fence 比较的随机 token。 */
    private String newLeaseToken() {
        return "callback-lease:" + UUID.randomUUID();
    }

    /** 生成不暴露下游地址的低敏 trace ID。 */
    private String traceId(AgentCommandTaskFinalStateCallbackJob job) {
        return "agent-final-state-callback:" + job.jobId();
    }

    /** 对下游状态码做保守归一，避免将响应文本写进 durable job。 */
    private String safeFailureCode(String value) {
        String normalized = normalizedCode(value);
        return normalized.isEmpty() ? "UNKNOWN_CALLBACK_FAILURE" : normalized;
    }

    /** 将状态码标准化为只含大写、数字和下划线的低基数形式。 */
    private String normalizedCode(String value) {
        if (!hasText(value)) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    }

    /** 判断文本是否存在，用于 worker ID、executor ID 等低敏字段。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 把摘要写成固定标签的 Micrometer Counter，禁止使用 commandId、tenantId 或错误文本做 label。 */
    private void recordSummary(RunSummary summary) {
        recordMetric("job", "registered", summary.registered);
        recordMetric("job", "claimed", summary.claimed);
        recordMetric("callback", "delivered", summary.delivered);
        recordMetric("callback", "retry", summary.retried);
        recordMetric("callback", "compensation_required", summary.compensationRequired);
        recordMetric("callback", "dead_letter", summary.deadLettered);
        recordMetric("job", "skipped", summary.skipped);
    }

    /** 记录单个固定标签指标；MeterRegistry 缺失时保持 worker 核心收敛可用。 */
    private void recordMetric(String phase, String outcome) {
        recordMetric(phase, outcome, 1);
    }

    /** 按计数写入固定标签指标，避免每条 job 逐条注册高基数监控。 */
    private void recordMetric(String phase, String outcome, int count) {
        if (meterRegistry == null || count <= 0) {
            return;
        }
        Counter.builder(METRIC_NAME)
                .tag("phase", phase)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment(count);
    }

    /**
     * 一轮 worker 的低敏统计结果。
     */
    public record RunSummary(int registered,
                             int claimed,
                             int delivered,
                             int retried,
                             int compensationRequired,
                             int deadLettered,
                             int skipped,
                             String skippedReason) {

        /**
         * 构造本 JVM 已有另一轮在执行时的跳过摘要。
         */
        static RunSummary skipped(String reason) {
            return new RunSummary(0, 0, 0, 0, 0, 0, 1, reason);
        }
    }

    /**
     * 单条 job 处理结果，用于汇总而不让循环分支散落多个可变计数器。
     */
    private record ProcessingOutcome(int delivered,
                                     int retried,
                                     int compensationRequired,
                                     int deadLettered,
                                     int skipped) {

        /** 返回下游已接受 callback 的结果。 */
        private static ProcessingOutcome deliveredOutcome() {
            return new ProcessingOutcome(1, 0, 0, 0, 0);
        }

        /** 返回已安排退避重试的结果。 */
        private static ProcessingOutcome retriedOutcome() {
            return new ProcessingOutcome(0, 1, 0, 0, 0);
        }

        /** 返回需要人工补偿的结果。 */
        private static ProcessingOutcome compensationRequiredOutcome() {
            return new ProcessingOutcome(0, 0, 1, 0, 0);
        }

        /** 返回已转死信的结果。 */
        private static ProcessingOutcome deadLetteredOutcome() {
            return new ProcessingOutcome(0, 0, 0, 1, 0);
        }

        /** 返回 lease 已被接管或条件更新失败时的无副作用跳过结果。 */
        private static ProcessingOutcome skippedOutcome() {
            return new ProcessingOutcome(0, 0, 0, 0, 1);
        }

        /** 返回旧 receipt 被新 Java fact 覆盖后的跳过结果，不误记为人工补偿。 */
        private static ProcessingOutcome supersededOutcome() {
            return skippedOutcome();
        }
    }
}

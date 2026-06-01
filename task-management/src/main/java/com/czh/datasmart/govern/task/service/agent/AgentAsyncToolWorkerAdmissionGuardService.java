/**
 * @Author : Cui
 * @Date: 2026/06/01 22:12
 * @Description DataSmart Govern Backend - AgentAsyncToolWorkerAdmissionGuardService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 异步工具 worker 入场保护服务。
 *
 * <p>本服务位于 task-management worker 的最前置位置：在 claimNextTask 之前执行。
 * 这样做的核心原因是“没有执行能力就不要先认领任务”。如果 worker 已经本地满载，却仍然把任务认领为 RUNNING，
 * 后续只能再 defer 或等待租约过期，会让任务状态产生抖动，也会增加数据库写入和排障复杂度。</p>
 *
 * <p>当前实现是单实例内存级保护，主要覆盖两个生产底线：</p>
 * <p>1. 本地最大并发：防止多个手动 dispatch、后台 scheduler 或未来多线程 worker 在同一个 task-management 实例内同时执行过多副作用；</p>
 * <p>2. 最小调度间隔：防止空转轮询、异常重试或外部触发过快导致数据库 claim、agent-runtime 回查、permission-admin evaluate 被打爆。</p>
 *
 * <p>为什么暂时不直接做 Redis/数据库级全局配额：
 * 当前 Agent worker 仍处于“真实副作用链路安全闭环”的早期阶段，先把本地保护抽象出来，可以马上降低误触发风险；
 * 等工具矩阵、租户模型和运营指标稳定后，再把该服务扩展为 Redis Lua 令牌桶、租户级 quota、工具级 circuit breaker，
 * 而不需要重写 dispatch-once 的主流程。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentAsyncToolWorkerAdmissionGuardService {

    private static final String REASON_LOCAL_CONCURRENCY_LIMIT = "LOCAL_CONCURRENCY_LIMIT";
    private static final String REASON_LOCAL_RATE_LIMIT = "LOCAL_RATE_LIMIT";

    private final AgentAsyncToolWorkerProperties properties;

    /**
     * 当前 JVM 内正在执行或准备执行的 Agent 异步工具 dispatch 数量。
     *
     * <p>这里统计的是通过入场保护、尚未释放 lease 的 dispatch-once 调用。
     * 它不等同于全局任务数，因为多实例部署时每个实例都有自己的本地计数。
     * 后续如需全局并发，需要在该服务内部叠加 Redis/DB 分布式租约，而不是让业务编排层感知实现细节。</p>
     */
    private final AtomicInteger localInFlight = new AtomicInteger(0);

    /**
     * 下一次允许进入 claim 阶段的本地时间戳，单位毫秒。
     *
     * <p>该字段实现最小调度间隔。它不是严格的高精度限流器，而是 worker 侧的保护阀：
     * 当调度频率配置过高、外部连续调用 dispatch-once，或者前一轮刚刚结束时，可以阻止下一次立即打到数据库和控制面。</p>
     */
    private final AtomicLong nextAllowedDispatchEpochMs = new AtomicLong(0L);

    /**
     * 申请一次 worker 入场许可。
     *
     * @param actorContext 当前触发 dispatch 的服务账号或调用者上下文，仅用于低敏诊断，不参与权限判断。
     * @return 入场许可。acquired=false 时，调用方必须停止本次 dispatch，不能继续认领任务。
     */
    public AgentAsyncToolWorkerAdmissionLease tryAcquire(TaskActorContext actorContext) {
        if (!properties.isCapacityGuardEnabled()) {
            return AgentAsyncToolWorkerAdmissionLease.acquired(
                    "Agent 异步工具 worker 本地容量保护已关闭，本次 dispatch 直接进入任务认领阶段。",
                    diagnostics(actorContext, "disabled", 0L),
                    () -> {
                    }
            );
        }

        int maxConcurrent = Math.max(1, properties.getMaxLocalConcurrentExecutions());
        int inFlightAfterAcquire = tryAcquireLocalConcurrency(maxConcurrent);
        if (inFlightAfterAcquire < 0) {
            Map<String, Object> diagnostics = diagnostics(actorContext, REASON_LOCAL_CONCURRENCY_LIMIT, 0L);
            diagnostics.put("localInFlight", localInFlight.get());
            diagnostics.put("maxLocalConcurrentExecutions", maxConcurrent);
            return AgentAsyncToolWorkerAdmissionLease.rejected(
                    REASON_LOCAL_CONCURRENCY_LIMIT,
                    "Agent 异步工具 worker 本地并发已达到上限，本次不认领新任务，等待已有执行释放容量。",
                    diagnostics
            );
        }

        RateReservation rateReservation = reserveLocalRateWindow();
        if (!rateReservation.allowed()) {
            localInFlight.decrementAndGet();
            Map<String, Object> diagnostics = diagnostics(actorContext, REASON_LOCAL_RATE_LIMIT, rateReservation.waitMillis());
            diagnostics.put("localInFlight", localInFlight.get());
            diagnostics.put("minDispatchIntervalMs", Math.max(0L, properties.getMinDispatchIntervalMs()));
            diagnostics.put("nextAllowedDispatchEpochMs", rateReservation.nextAllowedEpochMs());
            return AgentAsyncToolWorkerAdmissionLease.rejected(
                    REASON_LOCAL_RATE_LIMIT,
                    "Agent 异步工具 worker 本地调度间隔尚未到达，本次不认领新任务，避免控制面被过快轮询。",
                    diagnostics
            );
        }

        Map<String, Object> diagnostics = diagnostics(actorContext, "allowed", 0L);
        diagnostics.put("localInFlight", inFlightAfterAcquire);
        diagnostics.put("maxLocalConcurrentExecutions", maxConcurrent);
        diagnostics.put("minDispatchIntervalMs", Math.max(0L, properties.getMinDispatchIntervalMs()));
        return AgentAsyncToolWorkerAdmissionLease.acquired(
                "Agent 异步工具 worker 本地容量保护通过，可以进入任务认领阶段。",
                diagnostics,
                localInFlight::decrementAndGet
        );
    }

    private int tryAcquireLocalConcurrency(int maxConcurrent) {
        while (true) {
            int current = localInFlight.get();
            if (current >= maxConcurrent) {
                return -1;
            }
            int next = current + 1;
            if (localInFlight.compareAndSet(current, next)) {
                return next;
            }
        }
    }

    private RateReservation reserveLocalRateWindow() {
        long minIntervalMs = Math.max(0L, properties.getMinDispatchIntervalMs());
        if (minIntervalMs == 0L) {
            return RateReservation.allowed(0L);
        }
        long now = System.currentTimeMillis();
        while (true) {
            long nextAllowed = nextAllowedDispatchEpochMs.get();
            if (now < nextAllowed) {
                return RateReservation.rejected(nextAllowed - now, nextAllowed);
            }
            long nextWindow = now + minIntervalMs;
            if (nextAllowedDispatchEpochMs.compareAndSet(nextAllowed, nextWindow)) {
                return RateReservation.allowed(nextWindow);
            }
        }
    }

    private Map<String, Object> diagnostics(TaskActorContext actorContext, String decision, long waitMillis) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("capacityGuardDecision", decision);
        diagnostics.put("executorId", properties.getExecutorId());
        diagnostics.put("traceId", actorContext == null ? null : actorContext.traceId());
        diagnostics.put("waitMillis", waitMillis);
        diagnostics.put("guardScope", "LOCAL_JVM");
        return diagnostics;
    }

    private record RateReservation(boolean allowed, long waitMillis, long nextAllowedEpochMs) {

        static RateReservation allowed(long nextAllowedEpochMs) {
            return new RateReservation(true, 0L, nextAllowedEpochMs);
        }

        static RateReservation rejected(long waitMillis, long nextAllowedEpochMs) {
            return new RateReservation(false, Math.max(0L, waitMillis), nextAllowedEpochMs);
        }
    }
}

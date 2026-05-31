/**
 * @Author : Cui
 * @Date: 2026/05/31 20:18
 * @Description DataSmart Govern Backend - AgentAsyncToolWorkerScheduler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 异步工具后台 worker 调度器。
 *
 * <p>该调度器是 4.54 的“后台自动执行骨架”，默认关闭，不会影响本地学习环境。
 * 它复用 4.52/4.53 已经落地的 dispatch-once 链路，因此不会产生第二套执行逻辑：
 * 认领任务、payloadReference 解析、白名单工具适配、下游幂等执行、task complete/defer/fail、agent-runtime 状态回写，
 * 全部仍然由已有 Service 完成。</p>
 *
 * <p>为什么需要默认关闭：
 * Agent 工具可能触发数据同步、扫描、导出、质量检测等真实副作用。后台 worker 一旦自动循环，就会从“人工验证链路”
 * 进入“系统主动消费队列”的阶段。生产开启前必须确认服务账号鉴权、工具白名单、下游幂等、状态回写、队列容量和告警策略。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentAsyncToolWorkerScheduler {

    private final AgentAsyncToolWorkerProperties properties;
    private final AgentAsyncToolWorkerBatchService batchService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 定时触发一轮 Agent 异步工具批处理。
     *
     * <p>`fixedDelayString` 从配置读取，表示上一轮结束后等待多久再开始下一轮。
     * 方法内部仍会检查 `enabled`、`dryRunOnly` 和 `schedulerEnabled`，因此即使 Spring Scheduling 已启用，
     * 默认配置下也不会实际认领或执行任何任务。</p>
     */
    @Scheduled(fixedDelayString = "${datasmart.task-management.agent-async-worker.scheduler-fixed-delay-ms:5000}")
    public void dispatchScheduledBatch() {
        if (!shouldRunScheduler()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.warn("Agent 异步工具后台 worker 上一轮尚未结束，本轮跳过，executorId={}", properties.getExecutorId());
            return;
        }
        try {
            AgentAsyncToolWorkerBatchResult result = batchService.dispatchBatch(workerActorContext());
            if (result.attempted() > 0 && (result.claimed() > 0 || result.noTask() > 0)) {
                log.info("Agent 异步工具后台 worker 单轮完成，attempted={}, claimed={}, completed={}, deferred={}, failed={}, noTask={}, stoppedByNoTask={}",
                        result.attempted(), result.claimed(), result.completed(), result.deferred(),
                        result.failed(), result.noTask(), result.stoppedByNoTask());
            }
        } catch (RuntimeException exception) {
            log.error("Agent 异步工具后台 worker 单轮执行失败，executorId={}, error={}",
                    properties.getExecutorId(), exception.getMessage(), exception);
        } finally {
            running.set(false);
        }
    }

    private boolean shouldRunScheduler() {
        return properties.isEnabled()
                && !properties.isDryRunOnly()
                && properties.isSchedulerEnabled();
    }

    private TaskActorContext workerActorContext() {
        return new TaskActorContext(
                null,
                null,
                "SERVICE_ACCOUNT",
                "agent-async-worker-" + UUID.randomUUID(),
                "PLATFORM",
                List.of()
        );
    }
}

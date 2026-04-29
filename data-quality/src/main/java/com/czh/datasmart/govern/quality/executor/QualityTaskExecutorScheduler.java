/**
 * @Author : Cui
 * @Date: 2026/04/29 00:28
 * @Description DataSmart Govern Backend - QualityTaskExecutorScheduler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.executor;

import com.czh.datasmart.govern.quality.config.TaskManagementIntegrationProperties;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutorRunResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * data-quality 内置质量任务后台调度器。
 *
 * <p>这个类负责把“手动 run-once 的质量执行器协调能力”推进为“可自动消费任务队列的后台 worker”。
 * 它并不直接实现扫描业务，而是定时调用 {@link QualityTaskExecutorCoordinator#runBatch(int)}，
 * 让 coordinator 继续负责任务认领、payload 校验、心跳、扫描、报告回写和任务终态推进。
 *
 * <p>为什么要单独拆出 scheduler：
 * 1. 职责更清楚：scheduler 只决定什么时候触发，coordinator 只决定一次任务怎么执行；
 * 2. 风险更可控：自动消费任务属于高风险后台行为，必须有独立配置开关；
 * 3. 后续更好扩展：如果未来改成线程池、K8s Job、外部 Worker 或 Python AI 执行器，
 *    可以替换 scheduler，而不用重写 coordinator 的执行闭环；
 * 4. 学习更直观：可以清楚看到 Spring @Scheduled 如何触发业务轮询，以及如何用 AtomicBoolean 防重入。
 *
 * <p>当前 scheduler 默认关闭。开启需要同时满足：
 * - datasmart.quality.task-management.enabled=true；
 * - datasmart.quality.task-management.executor-coordinator-enabled=true；
 * - datasmart.quality.task-management.executor-scheduler-enabled=true。
 *
 * <p>这种三层保护看起来啰嗦，但很适合真实商用系统：
 * task-management 集成、执行器能力、后台自动消费是三个不同风险等级的动作，不能被一个粗暴开关绑在一起。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QualityTaskExecutorScheduler {

    private final TaskManagementIntegrationProperties properties;
    private final QualityTaskExecutorCoordinator qualityTaskExecutorCoordinator;
    private final QualityExecutorMetrics qualityExecutorMetrics;

    /**
     * 防止同一个实例内定时任务重叠执行。
     *
     * <p>Spring 的 fixedDelay 通常会等上一轮结束后再启动下一轮，但在复杂环境里仍然可能因为手动触发、
     * 未来多线程调度器或错误配置导致重入风险。这里用 AtomicBoolean 做本实例内的轻量保护：
     * - compareAndSet(false, true) 成功，说明当前没有正在执行的调度轮次；
     * - 如果失败，说明上一轮还没结束，本轮直接跳过；
     * - finally 中恢复为 false，保证异常也不会永久卡住。
     *
     * <p>它不是分布式锁。多实例部署时，还需要依赖 task-management 的 claim 原子性和租约机制，
     * 后续如果要严格限制全局并发，可以再引入 Redis/Zookeeper/数据库租约级调度锁。
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 定时触发一轮质量任务认领与执行。
     *
     * <p>fixedDelayString 和 initialDelayString 都来自配置类的毫秒换算方法。
     * 这样 application.yml 保持“秒”这个更易理解的单位，而 @Scheduled 获得它需要的毫秒值。
     */
    @Scheduled(
            initialDelayString = "#{@taskManagementIntegrationProperties.getExecutorSchedulerInitialDelayMillis()}",
            fixedDelayString = "#{@taskManagementIntegrationProperties.getExecutorSchedulerFixedDelayMillis()}"
    )
    public void dispatchQualityTasks() {
        if (!shouldRunScheduler()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            qualityExecutorMetrics.recordSchedulerSkippedByReentry();
            log.info("质量执行器后台调度器上一轮仍在执行，本轮跳过，executorId={}", properties.getExecutorId());
            return;
        }

        long startNanos = System.nanoTime();
        try {
            int maxRuns = properties.getSafeExecutorSchedulerMaxRunsPerTick();
            List<QualityExecutorRunResult> results = qualityTaskExecutorCoordinator.runBatch(maxRuns, "SCHEDULER");
            qualityExecutorMetrics.recordSchedulerTick(results, Duration.ofNanos(System.nanoTime() - startNanos));
            logSchedulerSummary(results, maxRuns);
        } catch (Exception ex) {
            /*
             * scheduler 层只兜底记录异常，不吞掉 coordinator 对单个任务的失败回写。
             * 如果异常已经在 coordinator 内部处理，通常不会走到这里；
             * 这里主要防御配置、序列化、Spring 调度线程或未知运行时异常。
             */
            qualityExecutorMetrics.recordSchedulerFailure();
            log.warn("质量执行器后台调度器执行失败，executorId={}", properties.getExecutorId(), ex);
        } finally {
            running.set(false);
        }
    }

    /**
     * 判断后台调度器是否应该运行。
     *
     * <p>三个开关都满足才运行，便于把“任务提交集成”“执行器能力”“后台自动消费”分层管理。
     */
    private boolean shouldRunScheduler() {
        return properties.isEnabled()
                && properties.isExecutorCoordinatorEnabled()
                && properties.isExecutorSchedulerEnabled();
    }

    /**
     * 输出一轮调度摘要日志。
     *
     * <p>当前还没有独立 observability 指标，所以先通过结构化日志保留最低限度的运营可见性。
     * 后续接入 Micrometer 后，可以把 claimedCount、successCount、failedCount、noTaskCount 等指标输出为 Prometheus 指标。
     */
    private void logSchedulerSummary(List<QualityExecutorRunResult> results, int maxRuns) {
        if (results == null || results.isEmpty()) {
            log.info("质量执行器后台调度器本轮未产生执行结果，executorId={}, maxRuns={}",
                    properties.getExecutorId(), maxRuns);
            return;
        }

        long claimedCount = results.stream()
                .filter(result -> Boolean.TRUE.equals(result.getClaimed()))
                .count();
        long successCount = results.stream()
                .filter(result -> "RELATIONAL_SCAN_SUCCEEDED".equals(result.getOutcome()))
                .count();
        long failedCount = results.stream()
                .filter(result -> "UNSUPPORTED_SCAN".equals(result.getOutcome())
                        || "FAILED_TO_PROCESS".equals(result.getOutcome()))
                .count();
        long deferredCount = results.stream()
                .filter(result -> "THROTTLED_DEFERRED".equals(result.getOutcome()))
                .count();
        long deadLetterCount = results.stream()
                .filter(result -> "THROTTLED_DEAD_LETTER".equals(result.getOutcome()))
                .count();
        QualityExecutorRunResult lastResult = results.get(results.size() - 1);
        log.info("质量执行器后台调度器本轮完成，executorId={}, requestedMaxRuns={}, actualRuns={}, claimedCount={}, successCount={}, failedCount={}, deferredCount={}, deadLetterCount={}, lastOutcome={}, lastMessage={}",
                properties.getExecutorId(),
                maxRuns,
                results.size(),
                claimedCount,
                successCount,
                failedCount,
                deferredCount,
                deadLetterCount,
                lastResult.getOutcome(),
                lastResult.getMessage());
    }
}

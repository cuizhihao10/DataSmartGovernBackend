/**
 * @Author : Cui
 * @Date: 2026/05/05 23:18
 * @Description DataSmart Govern Backend - QualityExecutionFailureSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.executor;

import com.czh.datasmart.govern.quality.config.TaskManagementIntegrationProperties;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutionFailRequest;
import com.czh.datasmart.govern.quality.controller.dto.QualityExecutorRunResult;
import com.czh.datasmart.govern.quality.integration.task.TaskManagementClient;
import com.czh.datasmart.govern.quality.integration.task.TaskResponse;
import com.czh.datasmart.govern.quality.service.DataQualityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 质量执行器失败收口组件。
 *
 * <p>coordinator 负责“正常流程怎么走”，本组件负责“异常发生后如何把系统推回可解释状态”。
 * 这两个关注点拆开非常重要：
 * 1. 正常路径需要尽量清晰，方便学习者看懂 claim -> heartbeat -> scan -> report -> complete；
 * 2. 异常路径需要非常谨慎，避免任务停留在 RUNNING、质量 execution 长期不闭合、或容量背压被误判为业务失败；
 * 3. 失败收口未来还会继续接入告警、审计事件、重试策略、人工工单和死信处理，不应继续膨胀 coordinator。
 *
 * <p>这里同时处理两类失败：
 * 普通失败：payload、SQL、远程调用或扫描逻辑失败，应该标记 task-management FAILED；
 * 背压失败：本实例并发护栏拒绝，任务本身没有坏，应该 defer 回队列，等待稍后重新认领。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QualityExecutionFailureSupport {

    private final TaskManagementIntegrationProperties properties;
    private final TaskManagementClient taskManagementClient;
    private final DataQualityService dataQualityService;

    /**
     * 处理 coordinator 执行过程中抛出的异常。
     *
     * @param result 本次执行的返回摘要，会在这里补充 outcome、message、finalized 等字段。
     * @param task 已认领的 task-management 任务快照；如果认领阶段之前失败，可能为空。
     * @param qualityExecutionId data-quality execution ID；只有 start 成功后才会有值。
     * @param ex 原始异常，用于区分未支持策略、真实处理失败和并发背压。
     */
    public void handleFailure(QualityExecutorRunResult result, TaskResponse task,
                              Long qualityExecutionId, Exception ex) {
        if (ex instanceof QualityExecutorConcurrencyGuard.ConcurrencyRejectedException concurrencyRejectedException) {
            handleConcurrencyRejected(result, task, qualityExecutionId, concurrencyRejectedException);
            return;
        }

        boolean unsupportedScan = ex instanceof UnsupportedOperationException;
        String message = unsupportedScan
                ? ex.getMessage()
                : "质量执行器 coordinator 处理任务失败: " + ex.getMessage();
        result.setOutcome(unsupportedScan
                ? QualityExecutorOutcome.UNSUPPORTED_SCAN
                : QualityExecutorOutcome.FAILED_TO_PROCESS);
        result.setMessage(message);
        result.setErrorMessage(message);
        log.warn("质量执行器 coordinator 处理失败，taskId={}, taskRunId={}",
                result.getTaskId(), result.getTaskRunId(), ex);

        /*
         * 如果 data-quality 已经创建 RUNNING execution，就必须尽力把它收口为 FAILED。
         * 否则质量执行历史页面会看到一条永远 RUNNING 的记录，运维也无法判断它是卡住、失败还是仍在运行。
         */
        if (qualityExecutionId != null && !Boolean.TRUE.equals(result.getQualityExecutionFinalized())) {
            markQualityExecutionFailed(
                    result,
                    qualityExecutionId,
                    unsupportedScan ? "SCAN_STRATEGY_UNSUPPORTED" : "COORDINATOR_PROCESSING_FAILED",
                    message,
                    !unsupportedScan);
        }

        /*
         * task-management 是队列与生命周期的权威来源。
         * 只要任务已经被认领，就要尽力把任务主状态也推进到 FAILED，避免等待租约超时才能恢复。
         */
        if (task != null && task.getId() != null) {
            try {
                taskManagementClient.failTask(task.getId(), result.getTaskRunId(), properties.getExecutorId(), message);
                result.setTaskFinalized(true);
            } catch (Exception failTaskEx) {
                log.warn("质量执行器标记 task-management 任务失败时再次失败，taskId={}",
                        task.getId(), failTaskEx);
            }
        }
    }

    /**
     * 处理并发护栏拒绝。
     *
     * <p>并发护栏触发代表“当前实例暂时不适合继续执行”，不是业务规则错误，也不是源数据质量差。
     * 因此这里走 defer，而不是 fail。这样任务稍后可以重新排队，并且失败率指标不会被容量波动污染。
     */
    private void handleConcurrencyRejected(QualityExecutorRunResult result,
                                           TaskResponse task,
                                           Long qualityExecutionId,
                                           QualityExecutorConcurrencyGuard.ConcurrencyRejectedException ex) {
        String message = "质量执行器并发护栏触发，任务已延期回队列: scope="
                + ex.getScope() + ", reason=" + ex.getMessage();
        result.setOutcome(QualityExecutorOutcome.THROTTLED_DEFERRED);
        result.setMessage(message);
        result.setErrorMessage(message);
        log.info("质量执行器并发护栏触发，准备延期 task-management 任务，taskId={}, taskRunId={}, scope={}, deferSeconds={}",
                result.getTaskId(), result.getTaskRunId(), ex.getScope(), properties.getSafeExecutorThrottleDeferSeconds());

        if (qualityExecutionId != null && !Boolean.TRUE.equals(result.getQualityExecutionFinalized())) {
            markQualityExecutionFailed(result, qualityExecutionId, "CONCURRENCY_GUARD_REJECTED", message, true);
        }

        if (task != null && task.getId() != null) {
            try {
                TaskResponse deferredTask = taskManagementClient.deferTask(
                        task.getId(),
                        result.getTaskRunId(),
                        properties.getExecutorId(),
                        message,
                        properties.getSafeExecutorThrottleDeferSeconds());
                if (deferredTask != null && "DEAD_LETTER".equals(deferredTask.getStatus())) {
                    result.setOutcome(QualityExecutorOutcome.THROTTLED_DEAD_LETTER);
                    result.setMessage(message + "；任务已超过最大连续退避次数并进入 DEAD_LETTER，需要运维人员处理。");
                }
                result.setTaskFinalized(true);
            } catch (Exception deferTaskEx) {
                log.warn("质量执行器并发护栏触发后，延期 task-management 任务失败，taskId={}",
                        task.getId(), deferTaskEx);
            }
        }
    }

    /**
     * 把 data-quality execution 标记为失败。
     *
     * <p>这里不向外抛出二次失败，因为失败收口本身已经处于补偿阶段。
     * 如果补偿失败，记录日志并让 task-management 的状态推进继续尝试，至少保证队列侧不会无限 RUNNING。
     */
    private void markQualityExecutionFailed(QualityExecutorRunResult result, Long qualityExecutionId,
                                            String errorType, String message, boolean retryable) {
        try {
            QualityExecutionFailRequest request = new QualityExecutionFailRequest();
            request.setErrorType(errorType);
            request.setErrorMessage(message);
            request.setRetryable(retryable);
            dataQualityService.failTaskExecution(qualityExecutionId, request);
            result.setQualityExecutionFinalized(true);
        } catch (Exception failQualityEx) {
            log.warn("质量执行器标记 data-quality execution 失败时再次失败，executionId={}",
                    qualityExecutionId, failQualityEx);
        }
    }
}

/**
 * @Author : Cui
 * @Date: 2026/06/29 13:04
 * @Description DataSmart Govern Backend - DataSyncWorkerLoopServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.impl;

import com.czh.datasmart.govern.datasync.config.DataSyncWorkerLoopProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionClaimRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionClaimResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerLoopExecutionResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerLoopRunRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerLoopRunResult;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.mapper.SyncTemplateMapper;
import com.czh.datasmart.govern.datasync.service.DataSyncExecutorLeaseService;
import com.czh.datasmart.govern.datasync.service.DataSyncWorkerLoopService;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionLifecycleSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncOfflineRunnerDispatchResult;
import com.czh.datasmart.govern.datasync.service.support.SyncOfflineRunnerDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * data-sync 内嵌 worker loop 默认实现。
 *
 * <p>本类刻意只做编排，不把同步执行细节重新写一遍：</p>
 * <p>1. 认领和租约并发裁决交给 {@link DataSyncExecutorLeaseService}；</p>
 * <p>2. 模板读取只通过 {@link SyncTemplateMapper} 拿到当前 execution 所属配置；</p>
 * <p>3. 离线 Runner 合同裁决、最小 run-once 委托和 complete/fail 回写交给 {@link SyncOfflineRunnerDispatchService}；</p>
 * <p>4. 只有在模板缺失或编排异常时，本类才通过 {@link SyncExecutionLifecycleSupport} 主动 fail-closed，
 *    避免 execution 被 claim 后长期停在 RUNNING。</p>
 *
 * <p>这样拆分的好处是：worker loop 成为“调度胶水”，不会演化成又一个大而全的同步执行器。
 * 后续要支持多批循环、checkpoint handoff、分片并发或独立 worker 进程时，可以逐步替换 dispatch 层或外部调用方，
 * 而不用推翻 lease、生命周期和模板服务。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSyncWorkerLoopServiceImpl implements DataSyncWorkerLoopService {

    private static final String TEMPLATE_MISSING_ERROR = "SYNC_TEMPLATE_NOT_FOUND";
    private static final String TEMPLATE_ID_MISSING_ERROR = "SYNC_TEMPLATE_ID_MISSING";
    private static final String DISPATCH_EXCEPTION_ERROR = "WORKER_LOOP_DISPATCH_EXCEPTION";
    private static final String FAIL_CALLBACK_REJECTED_ERROR = "WORKER_LOOP_FAIL_CALLBACK_REJECTED";

    private final DataSyncExecutorLeaseService leaseService;
    private final SyncTemplateMapper templateMapper;
    private final SyncOfflineRunnerDispatchService dispatchService;
    private final SyncExecutionLifecycleSupport lifecycleSupport;
    private final DataSyncWorkerLoopProperties properties;

    /**
     * 执行一轮 claim -> dispatch -> complete/fail。
     *
     * <p>本方法按照“最多处理 N 条”的方式循环，而不是只处理一条，原因是生产环境中任务队列可能出现短时积压。
     * 但它也不会无限循环：单轮上限由配置和请求共同裁剪，避免一个 HTTP 请求或一次定时调度长时间占用线程。</p>
     */
    @Override
    public SyncWorkerLoopRunResult runOnce(SyncWorkerLoopRunRequest request, SyncActorContext actorContext) {
        SyncWorkerLoopRunRequest safeRequest = request == null ? new SyncWorkerLoopRunRequest() : request;
        WorkerLoopContext context = resolveContext(safeRequest, actorContext);
        List<SyncWorkerLoopExecutionResult> executions = new ArrayList<>();
        Set<String> issueCodes = new LinkedHashSet<>();
        int claimAttempts = 0;
        int dispatchedCount = 0;
        int completedCount = 0;
        int failedCount = 0;
        boolean queueDrained = false;

        for (int index = 0; index < context.maxExecutions(); index++) {
            claimAttempts++;
            SyncExecutionClaimResult claimResult = leaseService.claimNext(claimRequest(context), context.actorContext());
            if (claimResult == null || !claimResult.claimed()) {
                queueDrained = true;
                break;
            }

            SyncWorkerLoopExecutionResult executionResult = handleClaimedExecution(claimResult, context.actorContext());
            executions.add(executionResult);
            if (executionResult.dispatched()) {
                dispatchedCount++;
            }
            if (executionResult.completed()) {
                completedCount++;
            }
            if (executionResult.failed()) {
                failedCount++;
            }
            issueCodes.addAll(executionResult.issueCodes());
        }

        return new SyncWorkerLoopRunResult(
                context.workerId(),
                context.tenantId(),
                context.maxExecutions(),
                claimAttempts,
                executions.size(),
                dispatchedCount,
                completedCount,
                failedCount,
                queueDrained,
                executions,
                List.copyOf(issueCodes),
                buildMessage(executions.size(), dispatchedCount, completedCount, failedCount, queueDrained),
                SyncWorkerLoopRunResult.PAYLOAD_POLICY
        );
    }

    /**
     * 处理已经被 claim 成功的 execution。
     *
     * <p>claim 成功后，execution 状态通常已经从 QUEUED 变成 RUNNING，并写入 executorId 和 leaseExpireTime。
     * 因此从这里开始，任何结构性错误都不能简单抛出后结束方法，否则 execution 会悬挂到租约过期；
     * 能够明确归因的错误会被转换为 failExecution，无法安全回写的异常则只返回低敏问题码并交给租约恢复兜底。</p>
     */
    private SyncWorkerLoopExecutionResult handleClaimedExecution(SyncExecutionClaimResult claimResult,
                                                                SyncActorContext actorContext) {
        SyncExecution execution = claimResult.execution();
        SyncTask task = claimResult.task();
        if (execution == null || task == null) {
            return new SyncWorkerLoopExecutionResult(null, null, true, false, false, false,
                    null, null, "CLAIM_RESULT_INCOMPLETE", List.of("CLAIM_RESULT_INCOMPLETE"));
        }
        if (task.getTemplateId() == null) {
            return failClaimedExecution(task, execution, actorContext, TEMPLATE_ID_MISSING_ERROR,
                    "同步任务缺少 templateId，worker loop 无法生成受控执行计划");
        }

        SyncTemplate template = templateMapper.selectById(task.getTemplateId());
        if (template == null) {
            return failClaimedExecution(task, execution, actorContext, TEMPLATE_MISSING_ERROR,
                    "同步任务关联的模板不存在，worker loop 无法继续派发");
        }

        try {
            SyncOfflineRunnerDispatchResult dispatchResult = dispatchService.dispatchOffline(
                    execution, task, template, claimResult.workerPlan(), actorContext);
            return fromDispatchResult(task, execution, claimResult, dispatchResult);
        } catch (Exception exception) {
            log.warn("data-sync worker loop 派发 execution 发生异常，已尝试按低敏错误 fail-closed: taskId={}, executionId={}, exceptionType={}",
                    task.getId(), execution.getId(), exception.getClass().getSimpleName());
            return failClaimedExecution(task, execution, actorContext, DISPATCH_EXCEPTION_ERROR,
                    "worker loop 派发阶段出现内部异常，本次执行按 fail-closed 终止");
        }
    }

    /**
     * 将 run-once 派发结果转成 worker loop 单条摘要。
     */
    private SyncWorkerLoopExecutionResult fromDispatchResult(SyncTask task,
                                                            SyncExecution execution,
                                                            SyncExecutionClaimResult claimResult,
                                                            SyncOfflineRunnerDispatchResult dispatchResult) {
        boolean dispatched = dispatchResult != null && dispatchResult.dispatched();
        boolean completed = dispatchResult != null && dispatchResult.completed();
        boolean failed = dispatchResult != null && dispatchResult.failed();
        String outcome;
        if (completed) {
            outcome = "COMPLETED";
        } else if (failed) {
            outcome = "FAILED";
        } else if (dispatched) {
            outcome = "DISPATCHED_WITHOUT_FINAL_CALLBACK";
        } else {
            outcome = "BLOCKED_BEFORE_REMOTE_CALL";
        }
        return new SyncWorkerLoopExecutionResult(
                task.getId(),
                execution.getId(),
                true,
                dispatched,
                completed,
                failed,
                claimResult.workerPlan() == null ? null : claimResult.workerPlan().planStatus(),
                dispatchResult == null ? null : dispatchResult.dispatchStatus(),
                outcome,
                dispatchResult == null ? List.of("RUN_ONCE_DISPATCH_RESULT_EMPTY") : dispatchResult.issueCodes()
        );
    }

    /**
     * 对已经 claim 的 execution 做 fail-closed 回写。
     *
     * <p>这里生成的错误样本只包含低敏错误码和短说明，不保存模板正文、字段映射、SQL、连接地址、远端响应或异常消息。
     * 这样既能让任务状态进入 FAILED，避免 RUNNING 悬挂，又不会把内部执行细节泄露到运营台。</p>
     */
    private SyncWorkerLoopExecutionResult failClaimedExecution(SyncTask task,
                                                              SyncExecution execution,
                                                              SyncActorContext actorContext,
                                                              String errorCode,
                                                              String errorMessage) {
        try {
            SyncExecutionFailRequest failRequest = new SyncExecutionFailRequest();
            failRequest.setExecutorId(execution.getExecutorId());
            failRequest.setErrorType("WORKER_LOOP_FAIL_CLOSED");
            failRequest.setErrorCode(errorCode);
            failRequest.setErrorMessage(errorMessage);
            failRequest.setRetryable(false);
            failRequest.setSourceRecordKey(null);
            failRequest.setTargetRecordKey(null);
            failRequest.setSamplePayload(null);
            failRequest.setIdempotencyKey("worker-loop-fail-" + execution.getId() + "-" + errorCode);
            lifecycleSupport.failExecution(task, execution, failRequest, actorContext);
            return new SyncWorkerLoopExecutionResult(task.getId(), execution.getId(), true, false,
                    false, true, null, "FAILED_BEFORE_REMOTE_CALL", "FAILED", List.of(errorCode));
        } catch (Exception exception) {
            log.warn("data-sync worker loop 低敏 fail 回写被拒绝，等待租约恢复兜底: taskId={}, executionId={}, errorCode={}, exceptionType={}",
                    task.getId(), execution.getId(), errorCode, exception.getClass().getSimpleName());
            return new SyncWorkerLoopExecutionResult(task.getId(), execution.getId(), true, false,
                    false, false, null, "FAIL_CALLBACK_REJECTED", "FAIL_CALLBACK_REJECTED",
                    List.of(errorCode, FAIL_CALLBACK_REJECTED_ERROR));
        }
    }

    /**
     * 构造 claim 请求。
     */
    private SyncExecutionClaimRequest claimRequest(WorkerLoopContext context) {
        SyncExecutionClaimRequest claimRequest = new SyncExecutionClaimRequest();
        claimRequest.setExecutorId(context.workerId());
        claimRequest.setTenantId(context.tenantId());
        claimRequest.setLeaseSeconds(context.leaseSeconds());
        return claimRequest;
    }

    /**
     * 解析本轮运行上下文。
     */
    private WorkerLoopContext resolveContext(SyncWorkerLoopRunRequest request, SyncActorContext actorContext) {
        String workerId = StringUtils.hasText(request.getExecutorId())
                ? request.getExecutorId().trim()
                : properties.getExecutorId();
        Long tenantId = request.getTenantId() == null ? properties.getTenantId() : request.getTenantId();
        int maxExecutions = properties.effectiveMaxExecutionsPerRun(request.getMaxExecutions());
        long leaseSeconds = properties.effectiveLeaseSeconds(request.getLeaseSeconds());
        SyncActorContext safeActor = ensureActorContext(actorContext, tenantId);
        return new WorkerLoopContext(workerId, tenantId, maxExecutions, leaseSeconds, safeActor);
    }

    /**
     * 为后台 worker 补齐服务账号上下文。
     */
    private SyncActorContext ensureActorContext(SyncActorContext actorContext, Long tenantId) {
        Long actorTenantId = actorContext != null && actorContext.tenantId() != null
                ? actorContext.tenantId()
                : tenantId;
        Long actorId = actorContext != null && actorContext.actorId() != null
                ? actorContext.actorId()
                : properties.getSystemActorId();
        String actorRole = actorContext != null && StringUtils.hasText(actorContext.actorRole())
                ? actorContext.actorRole()
                : properties.getSystemActorRole();
        String traceId = actorContext != null && StringUtils.hasText(actorContext.traceId())
                ? actorContext.traceId()
                : properties.getTraceIdPrefix() + "-" + Instant.now().toEpochMilli();
        return new SyncActorContext(actorTenantId, actorId, actorRole, traceId,
                actorContext == null ? null : actorContext.dataScopeLevel(),
                actorContext == null ? null : actorContext.dataScopeExpression(),
                actorContext == null ? List.of() : actorContext.authorizedProjectIds(),
                actorContext == null || actorContext.approvalRequired() == null
                        ? Boolean.FALSE
                        : actorContext.approvalRequired());
    }

    private String buildMessage(int claimedCount,
                                int dispatchedCount,
                                int completedCount,
                                int failedCount,
                                boolean queueDrained) {
        if (claimedCount == 0 && queueDrained) {
            return "worker loop 已执行，本轮没有可认领的同步执行记录";
        }
        return "worker loop 已执行，claimed=" + claimedCount
                + ", dispatched=" + dispatchedCount
                + ", completed=" + completedCount
                + ", failed=" + failedCount
                + ", queueDrained=" + queueDrained;
    }

    /**
     * 单轮 worker loop 的内部上下文。
     */
    private record WorkerLoopContext(String workerId,
                                     Long tenantId,
                                     int maxExecutions,
                                     long leaseSeconds,
                                     SyncActorContext actorContext) {
    }
}

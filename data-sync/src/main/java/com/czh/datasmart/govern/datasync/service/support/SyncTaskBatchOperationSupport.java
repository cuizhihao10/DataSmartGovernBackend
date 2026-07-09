/**
 * @Author : Cui
 * @Date: 2026/07/07 20:00
 * @Description DataSmart Govern Backend - SyncTaskBatchOperationSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskBatchItemResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskBatchOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskBatchOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskLifecycleOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 同步任务批量运营动作支撑组件。
 *
 * <p>该组件只负责编排“多条任务逐条执行现有单任务动作”，不重新定义任务状态机。
 * 这样做有两个重要收益：</p>
 * <p>1. 单任务下线、回收站、彻底删除、手工调度的准入规则仍然只维护在 {@link SyncTaskManagementOperationSupport}；
 *    批量接口不会因为复制状态判断而逐渐和单任务接口产生规则漂移；</p>
 * <p>2. 每个 taskId 独立事务执行，某一条失败时只回滚这一条，已经成功的任务不会被后续失败项拖回滚，
 *    这更符合运营台“批量处理可处理项，失败项逐条提示”的产品语义。</p>
 *
 * <p>注意：批量删除仍然遵守两段式治理：</p>
 * <p>1. 批量下线：关闭自动调度并清空 nextFireTime；</p>
 * <p>2. 批量回收：只允许 OFFLINE 任务进入 RECYCLED；</p>
 * <p>3. 批量彻底删除：只允许 RECYCLED 任务进入 DELETED，且当前仍是逻辑删除，保留执行历史和审计证据。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncTaskBatchOperationSupport {

    /**
     * 控制面批量操作的同步上限。
     *
     * <p>批量下线/删除会写任务状态和审计表，如果一次处理过多 taskId，会拉长 HTTP 请求时间并增加数据库连接占用。
     * 更大规模的批量治理后续应升级为后台任务，由 task-management 提供进度、暂停、恢复和失败重试。</p>
     */
    private static final int MAX_BATCH_OPERATION_SIZE = 200;

    private final SyncTaskMapper taskMapper;
    private final SyncDataScopeSupport dataScopeSupport;
    private final SyncTaskManagementOperationSupport taskManagementOperationSupport;
    private final SyncAuditSupport auditSupport;
    private final TransactionTemplate transactionTemplate;

    public SyncTaskBatchOperationResult manualDispatchTasks(SyncTaskBatchOperationRequest request,
                                                            SyncActorContext actorContext) {
        return executeBatch(
                "MANUAL_DISPATCH",
                request,
                actorContext,
                SyncAuditActionType.BATCH_MANUAL_DISPATCH_TASKS,
                (task, ignoredRequest, context) -> taskManagementOperationSupport.manualDispatchTask(task, context));
    }

    public SyncTaskBatchOperationResult offlineTasks(SyncTaskBatchOperationRequest request,
                                                     SyncActorContext actorContext) {
        return executeBatch(
                "OFFLINE",
                request,
                actorContext,
                SyncAuditActionType.BATCH_OFFLINE_TASKS,
                taskManagementOperationSupport::offlineTask);
    }

    public SyncTaskBatchOperationResult recycleTasks(SyncTaskBatchOperationRequest request,
                                                     SyncActorContext actorContext) {
        return executeBatch(
                "RECYCLE",
                request,
                actorContext,
                SyncAuditActionType.BATCH_RECYCLE_TASKS,
                taskManagementOperationSupport::recycleTask);
    }

    public SyncTaskBatchOperationResult hardDeleteTasks(SyncTaskBatchOperationRequest request,
                                                        SyncActorContext actorContext) {
        return executeBatch(
                "HARD_DELETE",
                request,
                actorContext,
                SyncAuditActionType.BATCH_HARD_DELETE_TASKS,
                taskManagementOperationSupport::hardDeleteTask);
    }

    private SyncTaskBatchOperationResult executeBatch(String operationType,
                                                      SyncTaskBatchOperationRequest request,
                                                      SyncActorContext actorContext,
                                                      SyncAuditActionType batchAuditAction,
                                                      BatchTaskOperation operation) {
        SyncTaskBatchOperationRequest safeRequest = request == null ? new SyncTaskBatchOperationRequest() : request;
        List<Long> taskIds = normalizeTaskIds(safeRequest.getTaskIds());
        boolean continueOnError = safeRequest.shouldContinueOnError();
        SyncTaskLifecycleOperationRequest lifecycleRequest = toLifecycleRequest(safeRequest);
        SyncTaskBatchOperationResult result =
                SyncTaskBatchOperationResult.start(operationType, taskIds.size(), continueOnError);

        for (int index = 0; index < taskIds.size(); index++) {
            Long taskId = taskIds.get(index);
            SyncTaskBatchItemResult itemResult = executeOne(taskId, lifecycleRequest, actorContext, operation);
            if (Boolean.TRUE.equals(itemResult.getSuccess())) {
                result.addSuccess(itemResult);
                continue;
            }
            result.addFailure(itemResult);
            if (!continueOnError) {
                appendSkippedItems(result, taskIds, index + 1);
                break;
            }
        }
        result.finalizeStatus();
        auditBatchSummary(result, batchAuditAction, actorContext);
        return result;
    }

    /**
     * 执行单个任务动作。
     *
     * <p>这里使用 {@link TransactionTemplate} 而不是让整个批量请求共用一个事务。
     * 原因是批量操作天然需要“逐条可解释”：某个任务因为 RUNNING 无法下线，不应回滚其它已经成功下线的任务。
     * 同时，如果单条动作内部创建了 execution 或写了状态后又抛错，TransactionTemplate 会回滚这一条，避免留下半条失败副作用。</p>
     */
    private SyncTaskBatchItemResult executeOne(Long taskId,
                                               SyncTaskLifecycleOperationRequest lifecycleRequest,
                                               SyncActorContext actorContext,
                                               BatchTaskOperation operation) {
        try {
            SyncTaskOperationResult operationResult = transactionTemplate.execute(status -> {
                SyncTask task = loadTask(taskId, actorContext);
                return operation.apply(task, lifecycleRequest, actorContext);
            });
            return SyncTaskBatchItemResult.success(
                    taskId,
                    operationResult == null ? taskId : operationResult.taskId(),
                    operationResult == null ? null : operationResult.state(),
                    operationResult == null ? "批量任务操作已完成" : operationResult.message());
        } catch (PlatformBusinessException exception) {
            return SyncTaskBatchItemResult.failure(taskId, exception.getErrorCode().name(), exception.getMessage());
        } catch (RuntimeException exception) {
            return SyncTaskBatchItemResult.failure(taskId, PlatformErrorCode.INTERNAL_ERROR.name(),
                    "批量任务操作发生系统异常: " + exception.getMessage());
        }
    }

    private SyncTask loadTask(Long taskId, SyncActorContext actorContext) {
        SyncTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "同步任务不存在: " + taskId);
        }
        if (SyncTaskState.DELETED.name().equals(task.getCurrentState())) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "同步任务已彻底删除: " + taskId);
        }
        dataScopeSupport.validateOwnedReadable(task.getTenantId(), task.getProjectId(),
                task.getOwnerId(), actorContext, "同步任务");
        /*
         * 批量操作必须和单任务操作使用同一套项目内角色规则。
         * validateOwnedReadable 只保证当前账号能看到任务；但批量手工调度、批量下线、批量回收、
         * 批量彻底删除都会改变任务生命周期。显式 PROJECT 范围下，如果用户只是 READER，
         * 即使前端按钮被误放开，后端也必须逐条拒绝，避免一个批量请求跨多个项目造成越权变更。
         */
        dataScopeSupport.validateProjectManageable(task.getTenantId(), task.getProjectId(),
                task.getWorkspaceId(), actorContext, "批量同步任务操作");
        return task;
    }

    private List<Long> normalizeTaskIds(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "批量操作任务 ID 列表不能为空");
        }
        if (taskIds.size() > MAX_BATCH_OPERATION_SIZE) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "单次批量操作最多支持 " + MAX_BATCH_OPERATION_SIZE + " 个同步任务，当前数量=" + taskIds.size());
        }
        Set<Long> deduplicated = new LinkedHashSet<>();
        for (Long taskId : taskIds) {
            if (taskId == null || taskId <= 0) {
                throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                        "批量操作任务 ID 必须是正整数，当前值=" + taskId);
            }
            if (!deduplicated.add(taskId)) {
                throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                        "批量操作任务 ID 不能重复，重复值=" + taskId);
            }
        }
        return deduplicated.stream().toList();
    }

    private SyncTaskLifecycleOperationRequest toLifecycleRequest(SyncTaskBatchOperationRequest request) {
        SyncTaskLifecycleOperationRequest lifecycleRequest = new SyncTaskLifecycleOperationRequest();
        lifecycleRequest.setReason(request.getReason());
        return lifecycleRequest;
    }

    private void appendSkippedItems(SyncTaskBatchOperationResult result, List<Long> taskIds, int startIndex) {
        for (int i = startIndex; i < taskIds.size(); i++) {
            result.addSkipped(SyncTaskBatchItemResult.skipped(
                    taskIds.get(i),
                    "批量操作已按 continueOnError=false 策略在首个失败后停止，当前任务未执行"));
        }
    }

    private void auditBatchSummary(SyncTaskBatchOperationResult result,
                                   SyncAuditActionType actionType,
                                   SyncActorContext actorContext) {
        Long tenantId = actorContext == null || actorContext.tenantId() == null ? 0L : actorContext.tenantId();
        auditSupport.saveAudit(tenantId, null, null, actionType, actorContext,
                "operationType=" + result.getOperationType()
                        + ",status=" + result.getStatus()
                        + ",totalCount=" + result.getTotalCount()
                        + ",successCount=" + result.getSuccessCount()
                        + ",failedCount=" + result.getFailedCount()
                        + ",skippedCount=" + result.getSkippedCount()
                        + ",continueOnError=" + result.getContinueOnError());
    }

    @FunctionalInterface
    private interface BatchTaskOperation {

        SyncTaskOperationResult apply(SyncTask task,
                                      SyncTaskLifecycleOperationRequest request,
                                      SyncActorContext actorContext);
    }
}

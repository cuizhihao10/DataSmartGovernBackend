package com.czh.datasmart.govern.task.service.support;

import com.czh.datasmart.govern.task.controller.dto.TaskExecutionCallbackContext;
import com.czh.datasmart.govern.task.entity.Task;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/05/07 21:30
 * @Description DataSmart Govern Backend - TaskExecutorCallbackSupport.java
 * @Version:1.0.0
 *
 * 执行器回调安全校验与审计详情支持组件。
 *
 * <p>执行器回调和普通用户点击按钮不同，它会直接改写 RUNNING 任务的进度、终态或队列归属。
 * 如果这些校验散落在生命周期状态机里，TaskLifecycleSupport 会越来越像“所有细节都知道的胖类”。
 * 因此本组件把回调协议相关的校验集中起来：谁在回调、回调哪一次 run、是否仍持有租约、
 * 是否具备服务账号/运营/平台管理员角色、是否仍在任务数据范围内。</p>
 *
 * <p>这样拆分后，生命周期组件只负责状态流转，幂等组件只负责重复请求保护，
 * 本组件只负责回调安全协议，三条能力线可以独立演进。</p>
 */
@Component
@RequiredArgsConstructor
public class TaskExecutorCallbackSupport {

    private final TaskDataScopeSupport dataScopeSupport;
    private final TaskOperationPermissionSupport permissionSupport;
    private final TaskExecutionLogSupport logSupport;

    /**
     * 校验执行器回调是否允许推进当前任务。
     *
     * @param task 当前任务快照，必须是服务层已经查出的真实任务。
     * @param callbackContext 执行器回调上下文，包含 actor、runId、executorId、idempotencyKey。
     * @param actionName 面向错误信息和审计语义的动作名称，例如“标记任务完成”。
     */
    public void validateExecutorCallback(Task task,
                                         TaskExecutionCallbackContext callbackContext,
                                         String actionName) {
        /*
         * 执行器回调必须同时满足四类约束：
         * 1. 身份约束：调用方角色必须是 SERVICE_ACCOUNT/OPERATOR/PLATFORM_ADMINISTRATOR 之一；
         * 2. 数据范围约束：调用方 actorContext 不能越过任务的 tenant/owner 边界；
         * 3. 租约约束：executorId 必须等于当前任务租约持有人；
         * 4. run 约束：runId 必须等于当前任务的 currentExecutionRunId，防止旧 run 迟到污染新 run。
         */
        if (callbackContext == null) {
            throw new IllegalStateException(actionName + " 缺少执行器回调上下文");
        }
        permissionSupport.validateExecutorOperationPermission(callbackContext.actorContext());
        dataScopeSupport.validateTaskInActorScope(task, callbackContext.actorContext(), actionName);
        validateRequiredCallbackFields(callbackContext, actionName);
        validateTaskLeaseSnapshot(task, callbackContext, actionName);
    }

    /**
     * 生成执行器回调的日志详情。
     *
     * <p>该方法统一把 runId、executorId、idempotencyKey 和 actor 写入执行日志，
     * 方便排查“哪一个 worker、哪一次 run、哪一个幂等键”推动了任务状态变化。</p>
     */
    public String callbackDetails(TaskExecutionCallbackContext callbackContext, String extraDetails) {
        String details = "runId=" + callbackContext.runId()
                + ", executorId=" + callbackContext.executorId()
                + ", idempotencyKey=" + callbackContext.idempotencyKey()
                + ", actor=" + logSupport.actorLabel(callbackContext.actorContext());
        if (extraDetails == null || extraDetails.isBlank()) {
            return details;
        }
        return details + ", " + extraDetails;
    }

    private void validateRequiredCallbackFields(TaskExecutionCallbackContext callbackContext, String actionName) {
        if (callbackContext.executorId() == null || callbackContext.executorId().isBlank()) {
            throw new IllegalStateException(actionName + " 缺少 executorId");
        }
        if (callbackContext.runId() == null) {
            throw new IllegalStateException(actionName + " 缺少 runId");
        }
        if (callbackContext.idempotencyKey() == null || callbackContext.idempotencyKey().isBlank()) {
            throw new IllegalStateException(actionName + " 缺少 idempotencyKey");
        }
    }

    private void validateTaskLeaseSnapshot(Task task,
                                           TaskExecutionCallbackContext callbackContext,
                                           String actionName) {
        if (task.getCurrentExecutorId() == null) {
            throw new IllegalStateException(actionName + " 当前任务没有有效执行器租约，不能接受执行器回调");
        }
        if (!task.getCurrentExecutorId().equals(callbackContext.executorId())) {
            throw new IllegalStateException(actionName + " executorId 与当前任务租约不匹配，currentExecutorId="
                    + task.getCurrentExecutorId() + ", requestExecutorId=" + callbackContext.executorId());
        }
        if (task.getCurrentExecutionRunId() == null) {
            throw new IllegalStateException(actionName + " 当前任务没有有效执行 run，不能接受执行器回调");
        }
        if (!task.getCurrentExecutionRunId().equals(callbackContext.runId())) {
            throw new IllegalStateException(actionName + " runId 与当前任务租约不匹配，currentRunId="
                    + task.getCurrentExecutionRunId() + ", requestRunId=" + callbackContext.runId());
        }
        if (task.getLeaseExpireTime() != null && LocalDateTime.now().isAfter(task.getLeaseExpireTime())) {
            throw new IllegalStateException(actionName + " 当前任务租约已过期，请先续租或等待超时恢复流程处理，leaseExpireTime="
                    + task.getLeaseExpireTime());
        }
    }
}

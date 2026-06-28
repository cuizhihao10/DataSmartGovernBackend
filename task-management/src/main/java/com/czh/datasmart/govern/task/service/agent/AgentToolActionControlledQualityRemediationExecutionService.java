/**
 * @Author : Cui
 * @Date: 2026/06/28 18:50
 * @Description DataSmart Govern Backend - AgentToolActionControlledQualityRemediationExecutionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionCallbackContext;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质量治理受控动作真实提交服务。
 *
 * <p>该服务把 `AGENT_TOOL_ACTION_CONTROLLED` 从“dry-run 已通过、payload body 已物化、审批事实已通过”
 * 推进到“由 agent-runtime Host 真实提交质量治理任务”。它故意从 dispatcher 中拆出来，避免单个 Impl/Service
 * 文件无限增长，也让后续 PostgreSQL、数据同步、文件治理等工具可以各自实现独立执行服务。</p>
 *
 * <p>执行顺序非常重要：
 * 1. 先领取 agent-runtime command worker lease，证明当前 worker 有资格处理该 command；
 * 2. 再调用 agent-runtime 的质量治理 submit 入口，仍然只传 commandId，不传 payload body；
 * 3. 然后写 command worker receipt，把低敏执行结果进入 runtime event timeline；
 * 4. 最后根据 receipt 和 submit 结果更新 task-management 任务终态。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentToolActionControlledQualityRemediationExecutionService {

    public static final String QUALITY_REMEDIATION_TOOL_CODE = "quality.remediation.task.draft";

    private static final String OUTCOME_EXECUTION_SUCCEEDED = "EXECUTION_SUCCEEDED";
    private static final String OUTCOME_EXECUTION_SKIPPED = "EXECUTION_SKIPPED";
    private static final String OUTCOME_COMPENSATION_REQUIRED = "COMPENSATION_REQUIRED";
    private static final String OUTCOME_DEFERRED_WAITING_COMMAND_LEASE = "DEFERRED_WAITING_COMMAND_LEASE";
    private static final String ERROR_CODE_SUBMIT_UNKNOWN = "AGENT_TOOL_ACTION_CONTROLLED_SUBMIT_STATUS_UNKNOWN";

    private final TaskService taskService;
    private final AgentAsyncToolWorkerProperties properties;
    private final AgentRuntimeCommandWorkerLeaseClient leaseClient;
    private final AgentRuntimeQualityRemediationSubmitClient submitClient;
    private final AgentRuntimeToolActionCommandWorkerReceiptClient receiptClient;
    private final AgentAsyncToolWorkerMetricsService metricsService;

    /**
     * 执行质量治理受控提交。
     *
     * @param task task-management 当前已认领任务，仍处于 RUNNING。
     * @param taskRunId 当前 execution run ID。
     * @param payload 低敏 command payload。
     * @param actorContext 当前服务账号/操作者上下文。
     * @param baseDiagnostics dispatcher 已经收集的低敏诊断，用于合并本阶段结果。
     * @return 本轮调度结果；不会包含 payload body 或 data-quality preview。
     */
    public AgentToolActionControlledDryRunResult execute(Task task,
                                                         Long taskRunId,
                                                         AgentToolActionControlledTaskPayload payload,
                                                         TaskActorContext actorContext,
                                                         Map<String, Object> baseDiagnostics) {
        String executorId = AgentToolActionControlledWorkerIds.controlledExecutorId(properties);
        Map<String, Object> diagnostics = new LinkedHashMap<>(baseDiagnostics == null ? Map.of() : baseDiagnostics);
        AgentRuntimeCommandWorkerLeaseClaimResponse lease = null;
        String releaseReason = "NOT_ACQUIRED";
        try {
            lease = leaseClient.claim(payload, executorId, actorContext);
            diagnostics.put("commandWorkerLeaseAcquired", lease.acquired());
            putIfPresent(diagnostics, "commandWorkerLeaseState", lease.state());
            putIfPresent(diagnostics, "commandWorkerLeaseVersion", lease.workerLeaseVersion());
            if (!lease.acquired()) {
                String message = "agent-runtime command worker lease 当前不可领取，任务已延迟等待其它 worker 完成或 lease 过期。";
                taskService.deferTask(task.getId(), message, properties.getControlledActionDryRunDeferSeconds(),
                        callbackContext(taskRunId, payload.commandId(), OUTCOME_DEFERRED_WAITING_COMMAND_LEASE, actorContext));
                return result(task, taskRunId, payload, OUTCOME_DEFERRED_WAITING_COMMAND_LEASE,
                        true, false, message, diagnostics,
                        List.of("等待当前 command worker lease 释放或过期后重试。"),
                        AgentToolActionControlledReceiptDelivery.skipped("未获得 command worker lease，不写执行 receipt"));
            }
            releaseReason = "SUBMIT_STARTED";
            AgentToolActionQualityRemediationSubmitResponse submitResponse =
                    submitClient.submit(payload, taskRunId, executorId, actorContext);
            diagnostics.put("submitDuplicate", submitResponse.duplicate());
            diagnostics.put("submitSideEffectStarted", submitResponse.sideEffectStarted());
            diagnostics.put("submitSideEffectExecuted", submitResponse.sideEffectExecuted());
            putIfPresent(diagnostics, "downstreamTaskId", submitResponse.taskId());
            putIfPresent(diagnostics, "downstreamTaskStatus", submitResponse.taskStatus());
            AgentToolActionControlledReceiptDelivery receiptDelivery =
                    publishReceipt(payload, taskRunId, executorId, lease, submitResponse, actorContext);
            if (!receiptDelivery.accepted()) {
                releaseReason = "RECEIPT_DEFERRED";
                String message = "质量治理提交已返回结果，但 command worker receipt 未被 agent-runtime 接受，任务已退避等待幂等补偿。";
                taskService.deferTask(task.getId(), message, properties.getPreCheckUnavailableDeferSeconds(),
                        callbackContext(taskRunId, payload.commandId(), "RECEIPT_DEFERRED", actorContext));
                return result(task, taskRunId, payload, "RECEIPT_DEFERRED", true,
                        submitResponse.sideEffectExecuted(), message, diagnostics,
                        List.of("检查 agent-runtime command worker receipt 路由、lease fact 与 runtime event projection。"),
                        receiptDelivery);
            }
            if (submitResponse.sideEffectExecuted()) {
                releaseReason = "COMPLETED";
                taskService.completeTask(task.getId(), successResult(payload, submitResponse),
                        callbackContext(taskRunId, payload.commandId(), OUTCOME_EXECUTION_SUCCEEDED, actorContext));
                return result(task, taskRunId, payload, OUTCOME_EXECUTION_SUCCEEDED, true, true,
                        safeMessage(submitResponse.message(), "质量治理任务已受控提交。"), diagnostics,
                        submitResponse.recommendedActions(), receiptDelivery);
            }
            releaseReason = "FAILED";
            String message = safeMessage(submitResponse.message(), "质量治理受控提交未创建真实任务。");
            taskService.failTask(task.getId(), message,
                    callbackContext(taskRunId, payload.commandId(), OUTCOME_EXECUTION_SKIPPED, actorContext));
            return result(task, taskRunId, payload, OUTCOME_EXECUTION_SKIPPED, true, false,
                    message, diagnostics, submitResponse.recommendedActions(), receiptDelivery);
        } catch (RuntimeException exception) {
            releaseReason = "COMPENSATION_REQUIRED";
            String message = "质量治理受控提交状态未知，任务已延迟等待 commandId 幂等补偿: " + safeExceptionMessage(exception);
            diagnostics.put("submitErrorCode", ERROR_CODE_SUBMIT_UNKNOWN);
            diagnostics.put("submitErrorMessage", safeExceptionMessage(exception));
            if (lease != null && lease.acquired()) {
                publishCompensationReceipt(payload, taskRunId, executorId, lease, message, actorContext);
            }
            taskService.deferTask(task.getId(), message, properties.getPreCheckUnavailableDeferSeconds(),
                    callbackContext(taskRunId, payload.commandId(), OUTCOME_COMPENSATION_REQUIRED, actorContext));
            return result(task, taskRunId, payload, OUTCOME_COMPENSATION_REQUIRED, true, false,
                    message, diagnostics,
                    List.of("使用同一 commandId 和幂等键重试提交，必要时查询 agent-runtime/data-quality 下游任务是否已创建。"),
                    AgentToolActionControlledReceiptDelivery.skipped("提交状态未知，已尝试写入补偿 receipt"));
        } finally {
            releaseLeaseQuietly(payload, executorId, lease, releaseReason, actorContext);
        }
    }

    private AgentToolActionControlledReceiptDelivery publishReceipt(
            AgentToolActionControlledTaskPayload payload,
            Long taskRunId,
            String executorId,
            AgentRuntimeCommandWorkerLeaseClaimResponse lease,
            AgentToolActionQualityRemediationSubmitResponse submitResponse,
            TaskActorContext actorContext) {
        AgentToolActionCommandWorkerReceiptRequest request = receiptRequest(
                payload,
                taskRunId,
                executorId,
                lease,
                submitResponse.outcome(),
                true,
                submitResponse.sideEffectStarted(),
                submitResponse.sideEffectExecuted(),
                submitResponse.sideEffectExecuted() ? List.of() : submitResponse.issueCodes(),
                submitResponse.sideEffectExecuted() ? null : "DATA_QUALITY_REMEDIATION_SUBMIT_NOT_EXECUTED",
                submitResponse.message(),
                submitResponse.recommendedActions(),
                submitResponse.taskId()
        );
        return receiptClient.publish(payload, request, actorContext);
    }

    private void publishCompensationReceipt(AgentToolActionControlledTaskPayload payload,
                                            Long taskRunId,
                                            String executorId,
                                            AgentRuntimeCommandWorkerLeaseClaimResponse lease,
                                            String message,
                                            TaskActorContext actorContext) {
        AgentToolActionCommandWorkerReceiptRequest request = receiptRequest(
                payload,
                taskRunId,
                executorId,
                lease,
                OUTCOME_COMPENSATION_REQUIRED,
                true,
                false,
                false,
                List.of(ERROR_CODE_SUBMIT_UNKNOWN),
                ERROR_CODE_SUBMIT_UNKNOWN,
                message,
                List.of("等待幂等补偿或人工查询下游任务状态。"),
                null
        );
        receiptClient.publish(payload, request, actorContext);
    }

    private AgentToolActionCommandWorkerReceiptRequest receiptRequest(
            AgentToolActionControlledTaskPayload payload,
            Long taskRunId,
            String executorId,
            AgentRuntimeCommandWorkerLeaseClaimResponse lease,
            String outcome,
            boolean preCheckPassed,
            boolean sideEffectStarted,
            boolean sideEffectExecuted,
            List<String> issueCodes,
            String errorCode,
            String message,
            List<String> actions,
            Long downstreamTaskId) {
        return new AgentToolActionCommandWorkerReceiptRequest(
                payload.commandId(),
                payload.taskId(),
                taskRunId,
                executorId,
                payload.tenantId(),
                payload.projectId(),
                actorId(payload),
                payload.taskStatus(),
                outcome,
                preCheckPassed,
                sideEffectStarted,
                sideEffectExecuted,
                true,
                lease.fencingToken(),
                lease.workerLeaseVersion(),
                lease.workerLeaseExpiresAtMs(),
                "ALLOW_CONTROLLED_EXECUTION",
                firstText(payload.policyVersions()),
                issueCodes,
                (int) Math.max(1, properties.getControlledActionSubmitTimeoutMs() / 1000L),
                4096,
                downstreamTaskId == null ? null : "TASK_MANAGEMENT_TASK",
                downstreamTaskId == null ? null : "task-artifact:quality-remediation-task:" + downstreamTaskId,
                downstreamTaskId != null,
                errorCode,
                payload.auditId(),
                payload.toolCode(),
                "data-quality",
                sideEffectExecuted ? "EXECUTION_RESULT" : "COMPENSATION_REQUIRED",
                message,
                actions,
                "command-worker-receipt:" + payload.commandId() + ":" + outcome + ":" + taskRunId
        );
    }

    private void releaseLeaseQuietly(AgentToolActionControlledTaskPayload payload,
                                     String executorId,
                                     AgentRuntimeCommandWorkerLeaseClaimResponse lease,
                                     String releaseReason,
                                     TaskActorContext actorContext) {
        if (lease == null || !lease.acquired()) {
            return;
        }
        try {
            leaseClient.release(payload, executorId, lease, releaseReason, actorContext);
        } catch (RuntimeException exception) {
            log.warn("释放 quality remediation command worker lease 失败，commandId={}, reason={}, error={}",
                    payload.commandId(), releaseReason, exception.getMessage());
        }
    }

    private AgentToolActionControlledDryRunResult result(Task task,
                                                         Long taskRunId,
                                                         AgentToolActionControlledTaskPayload payload,
                                                         String outcome,
                                                         boolean preCheckPassed,
                                                         boolean sideEffectExecuted,
                                                         String message,
                                                         Map<String, Object> diagnostics,
                                                         List<String> actions,
                                                         AgentToolActionControlledReceiptDelivery receiptDelivery) {
        metricsService.recordDispatchOutcome(outcome);
        return new AgentToolActionControlledDryRunResult(
                true,
                task.getId(),
                taskRunId,
                payload.commandId(),
                payload.toolCode(),
                outcome,
                preCheckPassed,
                sideEffectExecuted,
                message,
                diagnostics,
                actions,
                receiptDelivery
        );
    }

    private TaskExecutionCallbackContext callbackContext(Long taskRunId,
                                                         String commandId,
                                                         String outcome,
                                                         TaskActorContext actorContext) {
        return new TaskExecutionCallbackContext(
                taskRunId,
                AgentToolActionControlledWorkerIds.controlledExecutorId(properties),
                "agent-tool-action-controlled:" + commandId + ":" + outcome,
                actorContext
        );
    }

    private String successResult(AgentToolActionControlledTaskPayload payload,
                                 AgentToolActionQualityRemediationSubmitResponse response) {
        return "质量治理任务已通过 Agent Host 受控提交，commandId=" + payload.commandId()
                + ", downstreamTaskId=" + response.taskId()
                + ", downstreamTaskStatus=" + safeMessage(response.taskStatus(), "UNKNOWN")
                + ", duplicate=" + response.duplicate();
    }

    private Long actorId(AgentToolActionControlledTaskPayload payload) {
        if (payload.actorId() == null || payload.actorId().isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(payload.actorId().trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String firstText(List<String> values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String safeMessage(String value, String fallback) {
        String text = value == null || value.isBlank() ? fallback : value.trim();
        return text.length() <= 300 ? text : text.substring(0, 300);
    }

    private String safeExceptionMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 240 ? message : message.substring(0, 240);
    }

    private void putIfPresent(Map<String, Object> diagnostics, String key, Object value) {
        if (value != null) {
            diagnostics.put(key, value);
        }
    }
}

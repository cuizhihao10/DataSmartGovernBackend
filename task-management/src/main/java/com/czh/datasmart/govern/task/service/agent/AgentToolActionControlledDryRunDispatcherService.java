/**
 * @Author : Cui
 * @Date: 2026/06/11 22:00
 * @Description DataSmart Govern Backend - AgentToolActionControlledDryRunDispatcherService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionCallbackContext;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionClaimRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionClaimResult;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.service.TaskService;
import com.czh.datasmart.govern.task.support.AgentAsyncTaskCommandContractSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * `AGENT_TOOL_ACTION_CONTROLLED` 专用 dry-run 调度器。
 *
 * <p>这是新工具动作执行链路的第一步。5.115 之前它只做 dry-run/pre-check，不读取 payload body，也不产生真实副作用。
 * 5.115 之后它增加了一条非常窄的真实提交出口：当 payload body 已物化、审批事实通过、工具命中白名单且配置显式开启时，
 * 它会把质量治理任务提交动作委派给 {@link AgentToolActionControlledQualityRemediationExecutionService}，
 * 由后者完成 command lease、Host submit、command worker receipt 和任务终态写回。</p>
 *
 * <p>1. 从 task-management 队列中认领 `AGENT_TOOL_ACTION_CONTROLLED` 任务；</p>
 * <p>2. 解析 task.params 中的低敏命令信封，确认 `agent-payload:` 已有服务端登记证据；</p>
 * <p>3. 根据当前缺口把任务 fail-closed 或 defer，给后续 payload body、permission-admin 审批事实和 worker receipt 留出口。</p>
 *
 * <p>为什么不复用 {@link AgentAsyncToolDispatchOnceService}：历史 worker 会读取 `agent-tool-audit://.../plan-arguments`
 * 并调用白名单工具适配器，已经具备真实副作用能力；新工具动作仍处于控制面阶段，必须独立调度，防止误执行。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionControlledDryRunDispatcherService {

    private static final String OUTCOME_NO_TASK = "NO_TASK";
    private static final String OUTCOME_CAPACITY_LIMITED = "CAPACITY_LIMITED";
    private static final String OUTCOME_DEFERRED_WAITING_APPROVAL_FACT = "DEFERRED_WAITING_APPROVAL_FACT";
    private static final String OUTCOME_DEFERRED_WAITING_PAYLOAD_BODY = "DEFERRED_WAITING_PAYLOAD_BODY";
    private static final String OUTCOME_DEFERRED_READY_FOR_EXECUTOR = "DEFERRED_READY_FOR_EXECUTOR";
    private static final String OUTCOME_FAILED_PRECHECK = "FAILED_PRECHECK";
    private static final String ERROR_CODE_PRECHECK_REJECTED = "AGENT_TOOL_ACTION_CONTROLLED_PRECHECK_REJECTED";

    private final TaskService taskService;
    private final AgentToolActionControlledPayloadResolver payloadResolver;
    private final AgentAsyncToolWorkerProperties properties;
    private final AgentAsyncToolWorkerAdmissionGuardService admissionGuardService;
    private final AgentAsyncToolWorkerMetricsService metricsService;
    private final AgentRuntimeToolActionControlledReceiptClient receiptClient;
    private final PermissionAdminAgentToolActionApprovalClient approvalClient;
    private final AgentToolActionControlledQualityRemediationExecutionService qualityRemediationExecutionService;

    /**
     * 手动执行一轮受控工具动作 dry-run。
     *
     * <p>该方法会修改任务状态：认领后会把合格但尚不可执行的任务 defer，把契约缺失任务 fail。
     * 因此必须由内部服务账号、运维人员或受控调度器触发，不能暴露给普通用户。</p>
     */
    public AgentToolActionControlledDryRunResult dispatchDryRunOnce(TaskActorContext actorContext) {
        if (!properties.isControlledActionDryRunEnabled()) {
            throw new IllegalStateException(
                    "AGENT_TOOL_ACTION_CONTROLLED dry-run 当前未启用，请先配置 "
                            + "datasmart.task-management.agent-async-worker.controlled-action-dry-run-enabled=true"
            );
        }
        AgentAsyncToolWorkerAdmissionLease admission = admissionGuardService.tryAcquire(actorContext);
        if (!admission.acquired()) {
            metricsService.recordAdmissionRejected(admission.reasonCode());
            metricsService.recordDispatchOutcome(OUTCOME_CAPACITY_LIMITED);
            return result(false, null, null, null, null, OUTCOME_CAPACITY_LIMITED,
                    false, admission.message(), admission.diagnostics(), List.of("等待本地 worker 容量释放后再重试 dry-run。"),
                    AgentToolActionControlledReceiptDelivery.skipped("未认领具体任务，不写入 Agent timeline"));
        }
        try (AgentAsyncToolWorkerAdmissionLease ignored = admission) {
            TaskExecutionClaimResult claim = taskService.claimNextTask(claimRequest(), actorContext);
            if (!claim.claimed()) {
                metricsService.recordDispatchOutcome(OUTCOME_NO_TASK);
                return result(false, null, null, null, null, OUTCOME_NO_TASK,
                        false, claim.message(), Map.of(), List.of("当前没有待 dry-run 的受控工具动作任务。"),
                        AgentToolActionControlledReceiptDelivery.skipped("队列为空，不写入 Agent timeline"));
            }
            Task task = claim.task();
            Long runId = claim.executionRun() == null ? null : claim.executionRun().getId();
            if (task == null) {
                metricsService.recordDispatchOutcome(OUTCOME_FAILED_PRECHECK);
                return result(true, null, runId, null, null, OUTCOME_FAILED_PRECHECK,
                        false, "任务中心返回 claimed=true 但 task 为空，dry-run 已停止以避免误写状态。",
                        Map.of("errorCode", ERROR_CODE_PRECHECK_REJECTED),
                        List.of("请检查 TaskExecutionRunSupport.claimNextTask 的返回契约和数据库任务记录。"),
                        AgentToolActionControlledReceiptDelivery.skipped("任务为空，无法定位 session/run，不写入 Agent timeline"));
            }
            AgentToolActionControlledTaskPayload payload = null;
            try {
                payload = payloadResolver.resolve(task);
                DryRunDecision decision = decide(payload, actorContext);
                if (decision.submitQualityRemediation()) {
                    return qualityRemediationExecutionService.execute(
                            task,
                            runId,
                            payload,
                            actorContext,
                            decision.diagnostics()
                    );
                }
                TaskExecutionCallbackContext callbackContext = callbackContext(
                        runId,
                        payload.commandId(),
                        decision.outcome(),
                        actorContext
                );
                taskService.deferTask(task.getId(), decision.message(),
                        properties.getControlledActionDryRunDeferSeconds(), callbackContext);
                metricsService.recordDispatchOutcome(decision.outcome());
                AgentToolActionControlledReceiptDelivery receiptDelivery = publishReceipt(
                        payload,
                        runId,
                        decision.outcome(),
                        decision.preCheckPassed(),
                        decision.message(),
                        null,
                        decision.actions(),
                        actorContext
                );
                return result(true, task.getId(), runId, payload.commandId(), payload.toolCode(),
                        decision.outcome(), decision.preCheckPassed(), decision.message(), decision.diagnostics(), decision.actions(),
                        receiptDelivery);
            } catch (RuntimeException exception) {
                TaskExecutionCallbackContext callbackContext = callbackContext(
                        runId,
                        task == null ? "unknown-command" : "task-" + task.getId(),
                        OUTCOME_FAILED_PRECHECK,
                        actorContext
                );
                String message = "受控工具动作 dry-run 前置复核失败：" + safeMessage(exception);
                taskService.failTask(task.getId(), message, callbackContext);
                metricsService.recordDispatchOutcome(OUTCOME_FAILED_PRECHECK);
                Map<String, Object> diagnostics = new LinkedHashMap<>();
                diagnostics.put("errorCode", ERROR_CODE_PRECHECK_REJECTED);
                diagnostics.put("errorMessage", safeMessage(exception));
                AgentToolActionControlledReceiptDelivery receiptDelivery = publishReceipt(
                        payload,
                        runId,
                        OUTCOME_FAILED_PRECHECK,
                        false,
                        message,
                        ERROR_CODE_PRECHECK_REJECTED,
                        List.of("请重新生成符合 5.57 payload store 证据要求的工具动作命令。"),
                        actorContext
                );
                return result(true, task.getId(), runId, null, null, OUTCOME_FAILED_PRECHECK,
                        false, message, diagnostics, List.of("请重新生成符合 5.57 payload store 证据要求的工具动作命令。"),
                        receiptDelivery);
            }
        }
    }

    /**
     * 根据低敏 payload 证据决定当前 dry-run 处置。
     *
     * <p>如果 payload store 证据缺失，说明命令不可信，应 fail-closed；
     * 如果 payload body 尚未物化，则 defer 等待 agent-runtime payload store 生产实现；
     * 如果 payload body 已可用，则继续检查 submit 开关、dryRunOnly 保护和工具白名单。只有全部满足时才进入
     * 质量治理 Host submit；否则仍然 defer，避免把“某个字段可用”误解成“所有工具都可以执行”。</p>
     */
    private DryRunDecision decide(AgentToolActionControlledTaskPayload payload, TaskActorContext actorContext) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("taskId", payload.taskId());
        diagnostics.put("commandId", payload.commandId());
        diagnostics.put("toolCode", payload.toolCode());
        diagnostics.put("runId", payload.runId());
        diagnostics.put("approvalFactId", payload.confirmationId());
        diagnostics.put("payloadReference", payload.payloadReference());
        diagnostics.put("payloadKey", payload.payloadKey());
        diagnostics.put("tenantId", payload.tenantId());
        diagnostics.put("projectId", payload.projectId());
        diagnostics.put("actorId", payload.actorId());
        diagnostics.put("policyVersionCount", payload.policyVersions().size());
        diagnostics.put("delegationEvidenceCount", payload.delegationEvidence().size());
        diagnostics.put("payloadStoreEvidence", payload.hasPayloadStoreEvidence());
        diagnostics.put("payloadBodyAvailable", payload.payloadBodyAvailable());
        diagnostics.put("sideEffectExecuted", false);
        if (!payload.hasPayloadStoreEvidence()) {
            throw new IllegalStateException("缺少 AGENT_PAYLOAD_RECORD_FOUND 或 AGENT_PAYLOAD_METADATA_SCOPE_VERIFIED 证据");
        }
        if (payload.policyVersions().isEmpty()) {
            throw new IllegalStateException("缺少 policyVersions，无法把 dry-run 与控制面策略版本关联");
        }
        ApprovalGate approvalGate = validateApprovalFact(payload, diagnostics, actorContext);
        if (!approvalGate.approved()) {
            return new DryRunDecision(
                    OUTCOME_DEFERRED_WAITING_APPROVAL_FACT,
                    approvalGate.message(),
                    false,
                    false,
                    diagnostics,
                    approvalGate.actions()
            );
        }
        if (!payload.payloadBodyAvailable()) {
            return new DryRunDecision(
                    OUTCOME_DEFERRED_WAITING_PAYLOAD_BODY,
                    "受控工具动作 dry-run 通过低敏证据复核，但 payload body 尚未物化，任务已延迟等待 payload store 生产实现。",
                    true,
                    false,
                    diagnostics,
                    List.of(
                            "下一步在 agent-runtime payload store 中物化真实参数 body，并保持参数值不进入 task.params。",
                            "物化后仍需接入专用 executor、permission-admin 审批事实和 worker receipt。"
                    )
            );
        }
        if (canSubmitQualityRemediation(payload)) {
            diagnostics.put("qualityRemediationSubmitCandidate", true);
            return new DryRunDecision(
                    OUTCOME_DEFERRED_READY_FOR_EXECUTOR,
                    "受控工具动作 dry-run 通过，质量治理 Host 提交条件已满足，准备领取 command worker lease 并执行受控提交。",
                    true,
                    true,
                    diagnostics,
                    List.of("进入 agent-runtime Host 质量治理提交入口，提交后必须写 command worker receipt。")
            );
        }
        diagnostics.put("qualityRemediationSubmitCandidate", false);
        diagnostics.put("controlledActionSubmitEnabled", properties.isControlledActionSubmitEnabled());
        diagnostics.put("controlledActionDryRunOnly", properties.isDryRunOnly());
        diagnostics.put("submitToolAllowlistHit", submitToolAllowed(payload.toolCode()));
        return new DryRunDecision(
                OUTCOME_DEFERRED_READY_FOR_EXECUTOR,
                "受控工具动作 dry-run 通过，payload body 已声明可用，但真实提交开关、工具白名单或 dryRunOnly 保护尚未放行，任务已延迟等待执行器接入。",
                true,
                false,
                diagnostics,
                List.of(
                        "确认是否要开启 controlled-action-submit-enabled，并保持 dry-run-only=false。",
                        "仅将已具备 Host submit、command lease、worker receipt 和测试覆盖的工具加入提交白名单。"
                )
        );
    }

    private boolean canSubmitQualityRemediation(AgentToolActionControlledTaskPayload payload) {
        return properties.isControlledActionSubmitEnabled()
                && !properties.isDryRunOnly()
                && AgentToolActionControlledQualityRemediationExecutionService.QUALITY_REMEDIATION_TOOL_CODE
                .equals(payload.toolCode())
                && submitToolAllowed(payload.toolCode());
    }

    private boolean submitToolAllowed(String toolCode) {
        if (properties.getControlledActionSubmitToolAllowlist() == null) {
            return false;
        }
        return properties.getControlledActionSubmitToolAllowlist().stream()
                .anyMatch(allowed -> allowed != null && allowed.equals(toolCode));
    }

    /**
     * 回查 permission-admin 中的受控工具动作审批事实。
     *
     * <p>这里故意不把 `confirmationId` 当成“已经批准”的证明。confirmationId 只是低敏事实 ID，
     * 它必须能被 permission-admin 回查为 APPROVED，且绑定当前 tenant/project/actor/session/run/command/tool
     * 与策略版本。这样可以防止模型、外部协议、人工 SQL 修复或旧消息重放通过伪造 `approval:xxx`
     * 绕过人工审批。</p>
     *
     * <p>等待与失败的区别：
     * 1. 缺少 ID、事实未找到、仍 PENDING、permission-admin 暂不可用：返回未批准但可重试，调用方 defer；
     * 2. REJECTED、EXPIRED、SCOPE_MISMATCH、POLICY_VERSION_MISMATCH：抛异常，调用方 fail-closed；
     * 3. 配置关闭或 fail-open：记录诊断后继续，但生产环境不建议长期使用。</p>
     */
    private ApprovalGate validateApprovalFact(AgentToolActionControlledTaskPayload payload,
                                              Map<String, Object> diagnostics,
                                              TaskActorContext actorContext) {
        diagnostics.put("approvalCheckEnabled", properties.isControlledActionApprovalCheckEnabled());
        if (!properties.isControlledActionApprovalCheckEnabled()) {
            diagnostics.put("approvalCheck", "disabled_by_config");
            return ApprovalGate.approved(List.of("当前配置关闭审批事实回查，仅适合本地联调；生产环境应开启。"));
        }
        AgentToolActionControlledApprovalEvaluationRequest request = new AgentToolActionControlledApprovalEvaluationRequest(
                payload.confirmationId(),
                payload.tenantId(),
                payload.projectId(),
                payload.actorId(),
                payload.sessionId(),
                payload.runId(),
                payload.commandId(),
                payload.toolCode(),
                firstText(payload.policyVersions()),
                actorContext == null ? null : actorContext.traceId()
        );
        try {
            AgentToolActionControlledApprovalEvaluationResult result = approvalClient.evaluate(request);
            diagnostics.put("approvalCheck", "evaluated");
            diagnostics.put("approvalDecision", result.decision());
            diagnostics.put("approvalApproved", Boolean.TRUE.equals(result.approved()));
            diagnostics.put("approvalRetryable", Boolean.TRUE.equals(result.retryable()));
            diagnostics.put("approvalPolicyVersion", result.policyVersion());
            diagnostics.put("approvalEvidenceCount", result.evidenceCodes().size());
            diagnostics.put("approvalIssueCount", result.issueCodes().size());
            if (Boolean.TRUE.equals(result.approved())) {
                return ApprovalGate.approved(List.of("permission-admin 审批事实回查通过，受控工具动作可继续执行前治理。"));
            }
            if (Boolean.TRUE.equals(result.retryable())) {
                return ApprovalGate.waiting(
                        "受控工具动作等待 permission-admin 审批事实，decision=" + result.decision()
                                + "，reason=" + safeText(result.reason(), "等待审批事实生成或完成。"),
                        List.of(
                                "请在 permission-admin 中登记或完成该 approvalFactId 对应的审批事实。",
                                "审批事实必须绑定当前 tenant/project/actor/session/run/command/tool 和策略版本。"
                        )
                );
            }
            throw new IllegalStateException("permission-admin 审批事实拒绝受控工具动作，decision="
                    + result.decision() + "，reason=" + safeText(result.reason(), "审批事实未批准"));
        } catch (RuntimeException exception) {
            diagnostics.put("approvalCheck", "temporarily_unavailable_or_rejected");
            diagnostics.put("approvalCheckError", safeMessage(exception));
            if (properties.isControlledActionApprovalCheckFailOpenOnError()) {
                return ApprovalGate.approved(List.of("permission-admin 审批事实评估失败但配置为 fail-open，dry-run 继续推进；生产环境应谨慎使用。"));
            }
            if (exception instanceof IllegalStateException
                    && safeMessage(exception).contains("审批事实拒绝")) {
                throw exception;
            }
            return ApprovalGate.waiting(
                    "permission-admin 审批事实评估暂时不可用，任务已延迟等待权限中心恢复。",
                    List.of("检查 permission-admin 可用性、审批事实登记状态和服务账号调用链路。")
            );
        }
    }

    private TaskExecutionClaimRequest claimRequest() {
        TaskExecutionClaimRequest request = new TaskExecutionClaimRequest();
        request.setExecutorId(AgentToolActionControlledWorkerIds.controlledExecutorId(properties));
        request.setTaskType(AgentAsyncTaskCommandContractSupport.TASK_TYPE_AGENT_TOOL_ACTION_CONTROLLED);
        request.setLeaseSeconds(properties.getClaimLeaseSeconds());
        return request;
    }

    private TaskExecutionCallbackContext callbackContext(Long runId,
                                                         String commandId,
                                                         String outcome,
                                                         TaskActorContext actorContext) {
        return new TaskExecutionCallbackContext(
                runId,
                AgentToolActionControlledWorkerIds.controlledExecutorId(properties),
                "agent-tool-action-controlled:" + commandId + ":" + outcome,
                actorContext
        );
    }

    private AgentToolActionControlledDryRunResult result(boolean claimed,
                                                         Long taskId,
                                                         Long runId,
                                                         String commandId,
                                                         String toolCode,
                                                         String outcome,
                                                         boolean preCheckPassed,
                                                         String message,
                                                         Map<String, Object> diagnostics,
                                                         List<String> actions,
                                                         AgentToolActionControlledReceiptDelivery receiptDelivery) {
        return new AgentToolActionControlledDryRunResult(
                claimed,
                taskId,
                runId,
                commandId,
                toolCode,
                outcome,
                preCheckPassed,
                false,
                message,
                diagnostics,
                actions,
                receiptDelivery
        );
    }

    /**
     * 发布 dry-run receipt 到 agent-runtime timeline。
     *
     * <p>receipt 是“可见性事实”，不是当前 task 状态机的唯一事实源。这里放在 defer/fail 写回之后，
     * 是为了保证事件描述的状态已经真实落在 task-management 中；如果 receipt 回写失败，默认由 client
     * 按 fail-open 策略返回失败投递摘要，后续强审计阶段再升级为 outbox 重试。</p>
     */
    private AgentToolActionControlledReceiptDelivery publishReceipt(AgentToolActionControlledTaskPayload payload,
                                                                    Long taskRunId,
                                                                    String outcome,
                                                                    boolean preCheckPassed,
                                                                    String message,
                                                                    String errorCode,
                                                                    List<String> actions,
                                                                    TaskActorContext actorContext) {
        return receiptClient.publishDryRunReceipt(
                payload,
                taskRunId,
                outcome,
                preCheckPassed,
                message,
                errorCode,
                actions,
                actorContext
        );
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        String trimmed = message.trim();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300);
    }

    private String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
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

    private record DryRunDecision(
            String outcome,
            String message,
            boolean preCheckPassed,
            boolean submitQualityRemediation,
            Map<String, Object> diagnostics,
            List<String> actions
    ) {
    }

    private record ApprovalGate(boolean approved, String message, List<String> actions) {

        private static ApprovalGate approved(List<String> actions) {
            return new ApprovalGate(true, "审批事实已通过", actions);
        }

        private static ApprovalGate waiting(String message, List<String> actions) {
            return new ApprovalGate(false, message, actions);
        }
    }
}

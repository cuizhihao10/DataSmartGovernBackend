/**
 * @Author : Cui
 * @Date: 2026/05/31 23:43
 * @Description DataSmart Govern Backend - AgentRunToolDagExecutionPreviewService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentAsyncTaskCommandPlanItemView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunAsyncTaskCommandPlanView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionPreviewView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolExecutionPolicyItemView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolExecutionPolicyView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolPlanDagView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolDagExecutionPreviewItemView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolPlanDagNodeView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolServiceAuthorizationPreviewView;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.model.AgentToolServiceAuthorizationDecision;
import com.czh.datasmart.govern.agent.service.AgentToolExecutionAuditService;
import com.czh.datasmart.govern.agent.service.authorization.AgentToolServiceAuthorizationPreviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent Run 级 DAG-aware 执行预览服务。
 *
 * <p>该服务位于 `dag-plan` 和真实执行器之间，只做只读合成，不产生任何副作用。
 * 它回答的是“如果现在推进 DAG，哪些节点可以走同步自动执行，哪些节点可以走异步 command，
 * 哪些节点需要人类、参数或前置依赖”。这样后续做真正 DAG worker 前，可以先把安全边界、执行入口、
 * 阻断原因和前端展示契约固定下来。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentRunToolDagExecutionPreviewService {

    private final AgentRuntimeProperties properties;
    private final AgentToolExecutionAuditService auditService;
    private final AgentRunToolPlanDagService dagService;
    private final AgentRunToolExecutionPolicyService policyService;
    private final AgentRunAsyncTaskCommandPlanningService asyncTaskCommandPlanningService;
    private final AgentToolServiceAuthorizationPreviewService serviceAuthorizationPreviewService;

    /**
     * 生成某次 Run 的 DAG-aware 执行预览。
     *
     * <p>调用链 deliberately 复用已有只读能力：
     * `dagService` 解释图依赖，`policyService` 解释工具状态和风险，`asyncTaskCommandPlanningService`
     * 解释异步工具的 command envelope。当前方法不直接读写审计仓储，也不绕过已有服务规则。</p>
     */
    public AgentRunToolDagExecutionPreviewView previewRunDagExecution(String sessionId, String runId) {
        AgentRunToolPlanDagView dag = dagService.inspectRunToolPlanDag(sessionId, runId);
        AgentRunToolExecutionPolicyView policy = policyService.inspectRunPolicy(sessionId, runId);
        Map<String, AgentToolExecutionAuditView> auditByAuditId = indexAudits(auditService.listByRun(sessionId, runId));
        Map<String, AgentRunToolExecutionPolicyItemView> policyByAuditId = indexPolicyItems(policy);
        Map<String, AgentAsyncTaskCommandPlanItemView> asyncByAuditId = asyncCommandPlans(sessionId, runId);
        List<AgentToolDagExecutionPreviewItemView> items = dag.nodes().stream()
                .map(node -> previewNode(
                        node,
                        auditByAuditId.get(node.auditId()),
                        policyByAuditId.get(node.auditId()),
                        asyncByAuditId.get(node.auditId())
                ))
                .toList();
        return new AgentRunToolDagExecutionPreviewView(
                sessionId,
                runId,
                true,
                items.size(),
                (int) items.stream().filter(AgentToolDagExecutionPreviewItemView::readyForExecution).count(),
                count(items, AgentToolDagExecutionPreviewAction.SYNC_AUTO_EXECUTE_CANDIDATE),
                count(items, AgentToolDagExecutionPreviewAction.ASYNC_COMMAND_DISPATCH_CANDIDATE),
                count(items, AgentToolDagExecutionPreviewAction.WAIT_HUMAN_ACTION),
                blockedCount(items),
                count(items, AgentToolDagExecutionPreviewAction.UNSUPPORTED_EXECUTION_PATH),
                serviceAuthorizationEvaluatedCount(items),
                serviceAuthorizationAllowedCount(items),
                serviceAuthorizationRejectedCount(items),
                hasExecutableCandidates(items),
                buildSummaryReasons(dag, items),
                buildRecommendedActions(items),
                items
        );
    }

    private AgentToolDagExecutionPreviewItemView previewNode(AgentToolPlanDagNodeView node,
                                                             AgentToolExecutionAuditView audit,
                                                             AgentRunToolExecutionPolicyItemView policy,
                                                             AgentAsyncTaskCommandPlanItemView asyncPlan) {
        List<String> reasons = new ArrayList<>(node.reasons());
        List<String> actions = new ArrayList<>(node.recommendedActions());
        PreviewDecision decision = decide(node, policy, asyncPlan, reasons, actions);
        AgentToolServiceAuthorizationPreviewView serviceAuthorization =
                serviceAuthorizationPreviewService.preview(audit, node, policy);
        decision = enforceServiceAuthorizationIfNeeded(decision, serviceAuthorization, reasons, actions);
        return new AgentToolDagExecutionPreviewItemView(
                node.nodeId(),
                node.auditId(),
                node.toolCode(),
                node.state(),
                node.executionMode(),
                node.executionDecision(),
                decision.action().name(),
                decision.executionPath(),
                node.readyForExecution(),
                decision.wouldTriggerSideEffect(),
                node.parallelGroup(),
                policy == null ? null : policy.riskLevel(),
                policy == null ? null : policy.readOnly(),
                policy == null ? null : policy.idempotent(),
                policy == null ? null : policy.requiresApproval(),
                asyncPlan != null && Boolean.TRUE.equals(asyncPlan.dispatchable()),
                asyncPlan == null ? null : asyncPlan.commandId(),
                serviceAuthorization,
                policy == null ? null : policy.sandboxAllowed(),
                policy == null ? null : policy.sandboxIsolationMode(),
                policy == null ? List.of() : policy.sandboxIssueCodes(),
                policy == null ? List.of() : policy.sandboxReasons(),
                policy == null ? List.of() : policy.sandboxRecommendedActions(),
                node.blockedByNodeIds(),
                List.copyOf(reasons),
                List.copyOf(actions)
        );
    }

    /**
     * 根据服务间授权预检结果决定是否降级执行候选。
     *
     * <p>默认配置下该方法不会改变 preview action，只会把授权状态展示给调用方。
     * 当生产环境打开 enforceInPreview 后，如果某个节点原本是 sync/async 执行候选，
     * 但服务账号授权未通过或权限中心不可用，就把它降级为 BLOCKED_BY_POLICY。
     * 这样真实 DAG worker 可以复用 preview 结果做更保守的调度展示。</p>
     */
    private PreviewDecision enforceServiceAuthorizationIfNeeded(PreviewDecision decision,
                                                                AgentToolServiceAuthorizationPreviewView serviceAuthorization,
                                                                List<String> reasons,
                                                                List<String> actions) {
        if (!decision.wouldTriggerSideEffect()
                || serviceAuthorization == null
                || !Boolean.TRUE.equals(serviceAuthorization.enforced())
                || Boolean.TRUE.equals(serviceAuthorization.allowed())) {
            return decision;
        }
        reasons.add("服务间授权预检未通过或未完成，且当前配置要求在 preview 阶段强制阻断执行候选。");
        actions.add("请先完成 permission-admin 服务账号授权、数据范围策略和必要审批配置，再重新生成 DAG execution preview。");
        return new PreviewDecision(
                AgentToolDagExecutionPreviewAction.BLOCKED_BY_POLICY,
                "服务间授权未满足，禁止进入真实执行入口。",
                false
        );
    }

    private PreviewDecision decide(AgentToolPlanDagNodeView node,
                                   AgentRunToolExecutionPolicyItemView policy,
                                   AgentAsyncTaskCommandPlanItemView asyncPlan,
                                   List<String> reasons,
                                   List<String> actions) {
        String decision = normalize(node.executionDecision());
        if (!Boolean.TRUE.equals(node.readyForExecution())) {
            return blockedNodeDecision(node, decision, reasons, actions);
        }
        if (AgentRunToolExecutionDecision.AUTO_EXECUTABLE.name().equals(decision)) {
            return syncDecision(node, policy, reasons, actions);
        }
        if (AgentRunToolExecutionDecision.WAITING_ASYNC_EXECUTOR.name().equals(decision)) {
            return asyncDecision(asyncPlan, reasons, actions);
        }
        return policyDecision(node, decision, reasons, actions);
    }

    private PreviewDecision blockedNodeDecision(AgentToolPlanDagNodeView node,
                                                String decision,
                                                List<String> reasons,
                                                List<String> actions) {
        PreviewDecision policyDecision = policyDecision(node, decision, reasons, actions);
        if (policyDecision.action() != AgentToolDagExecutionPreviewAction.BLOCKED_BY_POLICY
                || node.blockedByNodeIds().isEmpty()) {
            return policyDecision;
        }
        reasons.add("节点仍存在 DAG 前置阻断，当前 preview 不会调用任何执行入口。");
        actions.add("先让 blockedByNodeIds 中的前置节点完成或被人工处理，再重新生成执行预览。");
        return new PreviewDecision(
                AgentToolDagExecutionPreviewAction.WAIT_DEPENDENCIES,
                "等待 DAG 前置节点完成；当前没有执行入口。",
                false
        );
    }

    private PreviewDecision syncDecision(AgentToolPlanDagNodeView node,
                                         AgentRunToolExecutionPolicyItemView policy,
                                         List<String> reasons,
                                         List<String> actions) {
        if (!Boolean.TRUE.equals(properties.getSyncAutoExecutionEnabled())) {
            reasons.add("配置 `syncAutoExecutionEnabled=false`，即使节点 ready，也不能进入同步自动执行入口。");
            actions.add("如需启用同步自动执行，请先完成租户策略、权限和运维开关评估后再打开配置。");
            return new PreviewDecision(AgentToolDagExecutionPreviewAction.BLOCKED_BY_POLICY, "同步自动执行入口未启用。", false);
        }
        if (policy == null) {
            reasons.add("未找到 execution-policy 项，不能验证风险、只读和幂等条件。");
            return new PreviewDecision(AgentToolDagExecutionPreviewAction.BLOCKED_BY_POLICY, "缺少 policy，无法预览执行入口。", false);
        }
        if (!isLowRiskReadOnlyIdempotent(policy)) {
            reasons.add("节点虽然 ready，但不满足 LOW + readOnly + idempotent + requiresApproval=false 的同步自动执行守卫。");
            actions.add("保持人工触发或等待后续 permission-admin 策略放开，不要从 DAG preview 绕过执行守卫。");
            return new PreviewDecision(AgentToolDagExecutionPreviewAction.BLOCKED_BY_POLICY, "同步自动执行守卫未通过。", false);
        }
        reasons.add("节点已满足 DAG 依赖和同步自动执行第一阶段安全条件，可作为 dry-run 候选展示。");
        actions.add("真实执行时调用 auto-execute-sync，并传入 dryRun=false 与 auditIds=[" + node.auditId() + "]；执行前服务端会再次读取最新 policy。");
        return new PreviewDecision(
                AgentToolDagExecutionPreviewAction.SYNC_AUTO_EXECUTE_CANDIDATE,
                "POST /agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/auto-execute-sync",
                true
        );
    }

    private PreviewDecision asyncDecision(AgentAsyncTaskCommandPlanItemView asyncPlan,
                                          List<String> reasons,
                                          List<String> actions) {
        if (asyncPlan == null) {
            reasons.add("节点是 ASYNC_TASK，但未找到异步 command 草案，不能进入 dispatcher 候选。");
            actions.add("先查询 async-command-plans，确认工具是否被参数、审批或幂等策略阻断。");
            return new PreviewDecision(AgentToolDagExecutionPreviewAction.BLOCKED_BY_POLICY, "缺少 async command plan。", false);
        }
        reasons.addAll(asyncPlan.reasons());
        actions.addAll(asyncPlan.recommendedActions());
        if (!Boolean.TRUE.equals(asyncPlan.dispatchable())) {
            return new PreviewDecision(AgentToolDagExecutionPreviewAction.BLOCKED_BY_POLICY, "异步 command 草案不可下发。", false);
        }
        reasons.add("节点已满足 DAG 依赖，且异步 command 草案 dispatchable=true，可作为后续 outbox/dispatcher 候选。");
        return new PreviewDecision(
                AgentToolDagExecutionPreviewAction.ASYNC_COMMAND_DISPATCH_CANDIDATE,
                "POST /agent-runtime/async-task-commands/outbox/enqueue 或后续 DAG-aware dispatcher",
                true
        );
    }

    private PreviewDecision policyDecision(AgentToolPlanDagNodeView node,
                                           String decision,
                                           List<String> reasons,
                                           List<String> actions) {
        if (AgentRunToolExecutionDecision.WAITING_PARAMETER_COMPLETION.name().equals(decision)) {
            return new PreviewDecision(AgentToolDagExecutionPreviewAction.WAIT_PARAMETER_COMPLETION, "等待参数补齐。", false);
        }
        if (AgentRunToolExecutionDecision.WAITING_APPROVAL.name().equals(decision)
                || AgentRunToolExecutionDecision.DRAFT_ONLY_REVIEW.name().equals(decision)
                || AgentRunToolExecutionDecision.FAILED_CAN_RETRY.name().equals(decision)
                || AgentRunToolExecutionDecision.FAILED_BLOCKS_RUN.name().equals(decision)) {
            return new PreviewDecision(AgentToolDagExecutionPreviewAction.WAIT_HUMAN_ACTION, "等待人工审批、草稿确认或失败复核。", false);
        }
        if (AgentRunToolExecutionDecision.ALREADY_EXECUTING.name().equals(decision)) {
            return new PreviewDecision(AgentToolDagExecutionPreviewAction.ALREADY_EXECUTING, "等待工具执行结果或异步状态回写。", false);
        }
        if (AgentRunToolExecutionDecision.ALREADY_SUCCEEDED.name().equals(decision)
                || AgentRunToolExecutionDecision.SKIPPED_TERMINAL.name().equals(decision)
                || AgentRunToolExecutionDecision.CANCELLED_TERMINAL.name().equals(decision)
                || AgentRunToolExecutionDecision.RUN_TERMINAL_BLOCKED.name().equals(decision)) {
            return new PreviewDecision(AgentToolDagExecutionPreviewAction.TERMINAL_OR_COMPLETED, "节点或 Run 已不可重复推进。", false);
        }
        if (AgentToolExecutionMode.DRAFT_ONLY.name().equals(normalize(node.executionMode()))
                || AgentToolExecutionMode.APPROVAL_REQUIRED.name().equals(normalize(node.executionMode()))) {
            return new PreviewDecision(AgentToolDagExecutionPreviewAction.WAIT_HUMAN_ACTION, "该执行模式必须由人类确认。", false);
        }
        reasons.add("当前 preview 尚未为 executionMode=" + node.executionMode() + " 和 decision=" + decision + " 提供自动推进路径。");
        actions.add("保持只读预览，待工具 schema、permission-admin 授权和执行器策略补齐后再开放。");
        return new PreviewDecision(AgentToolDagExecutionPreviewAction.BLOCKED_BY_POLICY, "当前策略阻断或路径未知。", false);
    }

    private Map<String, AgentRunToolExecutionPolicyItemView> indexPolicyItems(AgentRunToolExecutionPolicyView policy) {
        Map<String, AgentRunToolExecutionPolicyItemView> indexed = new LinkedHashMap<>();
        for (AgentRunToolExecutionPolicyItemView item : policy.items()) {
            indexed.put(item.auditId(), item);
        }
        return indexed;
    }

    private Map<String, AgentToolExecutionAuditView> indexAudits(List<AgentToolExecutionAuditView> audits) {
        Map<String, AgentToolExecutionAuditView> indexed = new LinkedHashMap<>();
        for (AgentToolExecutionAuditView audit : audits) {
            indexed.put(audit.auditId(), audit);
        }
        return indexed;
    }

    private Map<String, AgentAsyncTaskCommandPlanItemView> asyncCommandPlans(String sessionId, String runId) {
        AgentRunAsyncTaskCommandPlanView view = asyncTaskCommandPlanningService.planRunAsyncTaskCommands(sessionId, runId);
        Map<String, AgentAsyncTaskCommandPlanItemView> indexed = new LinkedHashMap<>();
        for (AgentAsyncTaskCommandPlanItemView item : view.items()) {
            indexed.put(item.auditId(), item);
        }
        return indexed;
    }

    private boolean isLowRiskReadOnlyIdempotent(AgentRunToolExecutionPolicyItemView policy) {
        return AgentToolRiskLevel.LOW.name().equals(normalize(policy.riskLevel()))
                && Boolean.TRUE.equals(policy.readOnly())
                && Boolean.TRUE.equals(policy.idempotent())
                && !Boolean.TRUE.equals(policy.requiresApproval());
    }

    private List<String> buildSummaryReasons(AgentRunToolPlanDagView dag, List<AgentToolDagExecutionPreviewItemView> items) {
        List<String> reasons = new ArrayList<>();
        reasons.add("当前接口仅做 DAG-aware dry-run 预览，不会执行工具、创建任务、投递 Kafka 或推进审计状态。");
        if (Boolean.TRUE.equals(dag.hasCycle())) {
            reasons.add("DAG 存在依赖环，必须修复 ToolPlan dependsOn 后才能进入自动调度。");
        }
        if (hasExecutableCandidates(items)) {
            reasons.add("存在可推进候选，但真实执行仍需调用受控入口，并再次经过权限、限流、幂等和最新状态校验。");
        }
        if (blockedCount(items) > 0) {
            reasons.add("存在被依赖、审批、参数或策略阻断的节点，建议先处理阻断项而不是强行执行。");
        }
        if (serviceAuthorizationEvaluatedCount(items) == 0) {
            reasons.add("服务间授权预检尚未启用或未参与判断；进入真实 DAG worker 前必须补齐 SERVICE_ACCOUNT 到 permission-admin 的动作级策略。");
        } else if (serviceAuthorizationRejectedCount(items) > 0) {
            reasons.add("存在服务间授权未通过或权限中心不可用的节点，真实执行时应按 fail-closed 策略阻断这些节点。");
        } else {
            reasons.add("已生成服务间授权预检结果；真实执行入口仍需要二次读取最新权限和工具状态，避免预览后状态漂移。");
        }
        if (sandboxRejectedCount(items) > 0) {
            reasons.add("存在工具调用沙箱未通过的节点，preview 已将这些节点降级为策略阻断，真实 DAG worker 不应绕过该 verdict。");
        }
        return reasons;
    }

    private List<String> buildRecommendedActions(List<AgentToolDagExecutionPreviewItemView> items) {
        List<String> actions = new ArrayList<>();
        if (count(items, AgentToolDagExecutionPreviewAction.SYNC_AUTO_EXECUTE_CANDIDATE) > 0) {
            actions.add("同步候选可先调用 auto-execute-sync 的 dryRun=true 做二次确认，再由用户或策略选择是否真实执行。");
        }
        if (count(items, AgentToolDagExecutionPreviewAction.ASYNC_COMMAND_DISPATCH_CANDIDATE) > 0) {
            actions.add("异步候选应进入 outbox/dispatcher/task-management，而不是在 HTTP 请求线程内同步执行。");
        }
        if (count(items, AgentToolDagExecutionPreviewAction.WAIT_HUMAN_ACTION) > 0) {
            actions.add("优先处理审批、草稿确认或失败复核，避免 Agent 在未授权状态下继续行动。");
        }
        if (sandboxRejectedCount(items) > 0) {
            actions.add("优先处理 sandboxIssueCodes 对应的工具目录、目标服务、参数体量、审批或幂等配置问题，再重新生成 preview。");
        }
        actions.add("进入真实 DAG worker 前，应先接入 permission-admin 服务间授权和工具级并发/配额策略。");
        return actions;
    }

    private int count(List<AgentToolDagExecutionPreviewItemView> items, AgentToolDagExecutionPreviewAction action) {
        return (int) items.stream().filter(item -> action.name().equals(item.previewAction())).count();
    }

    private int blockedCount(List<AgentToolDagExecutionPreviewItemView> items) {
        return (int) items.stream()
                .filter(item -> !AgentToolDagExecutionPreviewAction.SYNC_AUTO_EXECUTE_CANDIDATE.name().equals(item.previewAction()))
                .filter(item -> !AgentToolDagExecutionPreviewAction.ASYNC_COMMAND_DISPATCH_CANDIDATE.name().equals(item.previewAction()))
                .filter(item -> !AgentToolDagExecutionPreviewAction.TERMINAL_OR_COMPLETED.name().equals(item.previewAction()))
                .filter(item -> !AgentToolDagExecutionPreviewAction.ALREADY_EXECUTING.name().equals(item.previewAction()))
                .count();
    }

    private boolean hasExecutableCandidates(List<AgentToolDagExecutionPreviewItemView> items) {
        return count(items, AgentToolDagExecutionPreviewAction.SYNC_AUTO_EXECUTE_CANDIDATE) > 0
                || count(items, AgentToolDagExecutionPreviewAction.ASYNC_COMMAND_DISPATCH_CANDIDATE) > 0;
    }

    private int serviceAuthorizationEvaluatedCount(List<AgentToolDagExecutionPreviewItemView> items) {
        return (int) items.stream()
                .filter(item -> item.serviceAuthorization() != null)
                .filter(item -> !AgentToolServiceAuthorizationDecision.NOT_EVALUATED.name()
                        .equals(item.serviceAuthorization().decision()))
                .count();
    }

    private int serviceAuthorizationAllowedCount(List<AgentToolDagExecutionPreviewItemView> items) {
        return (int) items.stream()
                .filter(item -> item.serviceAuthorization() != null)
                .filter(item -> Boolean.TRUE.equals(item.serviceAuthorization().allowed()))
                .count();
    }

    private int serviceAuthorizationRejectedCount(List<AgentToolDagExecutionPreviewItemView> items) {
        return (int) items.stream()
                .filter(item -> item.serviceAuthorization() != null)
                .filter(item -> !AgentToolServiceAuthorizationDecision.NOT_EVALUATED.name()
                        .equals(item.serviceAuthorization().decision()))
                .filter(item -> !Boolean.TRUE.equals(item.serviceAuthorization().allowed()))
                .count();
    }

    private int sandboxRejectedCount(List<AgentToolDagExecutionPreviewItemView> items) {
        return (int) items.stream()
                .filter(item -> Boolean.FALSE.equals(item.sandboxAllowed()))
                .count();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record PreviewDecision(AgentToolDagExecutionPreviewAction action,
                                   String executionPath,
                                   boolean wouldTriggerSideEffect) {
    }
}

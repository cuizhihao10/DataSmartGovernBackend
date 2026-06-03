/**
 * @Author : Cui
 * @Date: 2026/06/01 00:02
 * @Description DataSmart Govern Backend - AgentRunToolDagExecutionDryRunService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionDryRunRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionDryRunResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionPreviewView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolDagExecutionDryRunItemView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolDagExecutionPreviewItemView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolServiceAuthorizationPreviewView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent Run 级 DAG-aware 执行干运行服务。
 *
 * <p>该服务是从“只读执行预览”走向“真实 DAG worker”的中间安全层。它不重新推导 DAG、
 * 不重新实现工具策略，也不直接调用 {@code auto-execute-sync} 或异步 outbox 服务，
 * 而是先读取 {@link AgentRunToolDagExecutionPreviewService} 生成的统一 preview 结果，
 * 再根据调用方选择的 nodeId/auditId 组装一份无副作用的拟执行计划。</p>
 *
 * <p>这样做有两个核心收益：
 * 1. 单一事实来源：所有依赖、审批、参数、工具风险和服务间授权解释仍由 preview 负责，避免 dry-run 和 worker
 *    将来出现两套判断口径；
 * 2. 商业安全性：Agent loop、前端按钮或智能网关可以先看到“即将走哪条受控入口”，再决定是否交给用户确认、
 *    permission-admin 授权或后台 worker 调度。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentRunToolDagExecutionDryRunService {

    /**
     * 默认批量上限。
     *
     * <p>没有显式传 maxNodes 时，默认只预演 20 个节点。真实企业 DAG 可能包含大量并行扫描、分区同步或补偿节点，
     * 如果默认全量返回，容易让前端和 Agent loop 误以为一次可以全部推进，也会放大响应体。</p>
     */
    private static final int DEFAULT_MAX_NODES = 20;

    /**
     * 服务端硬上限。
     *
     * <p>该上限不是性能极限，而是产品安全阈值：dry-run 虽然无副作用，但过大的拟执行批次会掩盖审批、
     * 配额、限流和人工确认问题。后续可以把它升级为租户级配置。</p>
     */
    private static final int MAX_NODES_CAP = 100;

    private final AgentRunToolDagExecutionPreviewService previewService;
    private final AgentRunToolDagExecutionDryRunEventPublisher eventPublisher;
    private final AgentRunToolDagSelectionFingerprintSupport fingerprintSupport;

    /**
     * 生成一次 DAG-aware 执行干运行结果。
     *
     * <p>选择规则：
     * 如果请求显式传入 nodeIds/auditIds，则只处理这些目标；如果没有显式选择，则默认只选 preview 中已经是
     * 同步自动执行候选或异步 command 候选的节点。这个默认值适合 Agent loop “看看下一批能安全推进什么”，
     * 也避免把等待审批、等待依赖的节点塞进执行候选。</p>
     *
     * @param sessionId Agent 会话 ID，用于限定工作空间和上下文。
     * @param runId Agent Run ID，用于限定本次工具计划。
     * @param request dry-run 请求；允许为空，为空时采用默认候选选择规则。
     * @return 无副作用的拟执行计划和节点级解释。
     */
    public AgentRunToolDagExecutionDryRunResponse dryRunDagExecution(String sessionId,
                                                                     String runId,
                                                                     AgentRunToolDagExecutionDryRunRequest request) {
        return dryRunDagExecution(sessionId, runId, request, null);
    }

    /**
     * 生成 dry-run 响应并发布 runtime event 摘要。
     *
     * <p>相比三参数方法，该重载额外接收 traceId，用于把 HTTP 请求链路与 runtime event 关联起来。
     * 事件发布只写摘要，不写工具参数和执行路径；即使事件投影失败，也不会让本次 dry-run 变成真实执行或改变审计状态。</p>
     */
    public AgentRunToolDagExecutionDryRunResponse dryRunDagExecution(String sessionId,
                                                                     String runId,
                                                                     AgentRunToolDagExecutionDryRunRequest request,
                                                                     String traceId) {
        AgentRunToolDagExecutionDryRunRequest safeRequest = request == null
                ? new AgentRunToolDagExecutionDryRunRequest(List.of(), List.of(), null, false)
                : request;
        List<String> requestedNodeIds = normalizeSelectors(safeRequest.nodeIds());
        List<String> requestedAuditIds = normalizeSelectors(safeRequest.auditIds());
        int effectiveMaxNodes = effectiveMaxNodes(safeRequest.maxNodes());
        boolean explicitSelection = !requestedNodeIds.isEmpty() || !requestedAuditIds.isEmpty();
        boolean includeUnselected = Boolean.TRUE.equals(safeRequest.includeUnselectedPreviewItems());

        AgentRunToolDagExecutionPreviewView preview = previewService.previewRunDagExecution(sessionId, runId);
        Set<String> foundNodeIds = new LinkedHashSet<>();
        Set<String> foundAuditIds = new LinkedHashSet<>();
        List<AgentToolDagExecutionDryRunItemView> items = new ArrayList<>();
        int selectedWithinLimit = 0;
        int notSelectedCount = 0;

        for (AgentToolDagExecutionPreviewItemView previewItem : preview.items()) {
            boolean selected = isSelected(previewItem, requestedNodeIds, requestedAuditIds, explicitSelection);
            if (previewItem.nodeId() != null) {
                foundNodeIds.add(previewItem.nodeId());
            }
            if (previewItem.auditId() != null) {
                foundAuditIds.add(previewItem.auditId());
            }
            if (!selected) {
                notSelectedCount++;
                if (includeUnselected) {
                    items.add(toNotSelectedItem(previewItem, explicitSelection));
                }
                continue;
            }
            selectedWithinLimit++;
            if (selectedWithinLimit > effectiveMaxNodes) {
                items.add(toBatchLimitItem(previewItem, effectiveMaxNodes));
                continue;
            }
            items.add(toSelectedItem(sessionId, runId, previewItem));
        }

        addNotFoundSelectors(requestedNodeIds, requestedAuditIds, foundNodeIds, foundAuditIds, items);
        String selectionFingerprint = fingerprintSupport.fingerprint(
                sessionId,
                runId,
                requestedNodeIds,
                requestedAuditIds,
                effectiveMaxNodes,
                items
        );
        AgentRunToolDagExecutionDryRunResponse response = new AgentRunToolDagExecutionDryRunResponse(
                sessionId,
                runId,
                true,
                requestedNodeIds,
                requestedAuditIds,
                safeRequest.maxNodes(),
                effectiveMaxNodes,
                selectionFingerprint,
                countSelectedWithinLimit(items),
                count(items, AgentToolDagExecutionDryRunAction.SYNC_AUTO_EXECUTE_DRY_RUN),
                count(items, AgentToolDagExecutionDryRunAction.ASYNC_OUTBOX_ENQUEUE_PREVIEW),
                count(items, AgentToolDagExecutionDryRunAction.BLOCKED_BY_PREVIEW),
                notSelectedCount,
                count(items, AgentToolDagExecutionDryRunAction.REQUESTED_NODE_OR_AUDIT_NOT_FOUND),
                count(items, AgentToolDagExecutionDryRunAction.BATCH_LIMIT_REACHED),
                buildSummaryReasons(explicitSelection, preview, items, effectiveMaxNodes),
                buildRecommendedActions(items),
                List.copyOf(items)
        );
        eventPublisher.publish(sessionId, runId, traceId, safeRequest, response);
        return response;
    }

    private List<String> normalizeSelectors(List<String> selectors) {
        if (selectors == null || selectors.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String selector : selectors) {
            if (selector != null && !selector.isBlank()) {
                normalized.add(selector.trim());
            }
        }
        return List.copyOf(normalized);
    }

    private int effectiveMaxNodes(Integer requestedMaxNodes) {
        if (requestedMaxNodes == null || requestedMaxNodes <= 0) {
            return DEFAULT_MAX_NODES;
        }
        return Math.min(requestedMaxNodes, MAX_NODES_CAP);
    }

    private boolean isSelected(AgentToolDagExecutionPreviewItemView item,
                               List<String> requestedNodeIds,
                               List<String> requestedAuditIds,
                               boolean explicitSelection) {
        if (explicitSelection) {
            return requestedNodeIds.contains(item.nodeId()) || requestedAuditIds.contains(item.auditId());
        }
        return AgentToolDagExecutionPreviewAction.SYNC_AUTO_EXECUTE_CANDIDATE.name().equals(item.previewAction())
                || AgentToolDagExecutionPreviewAction.ASYNC_COMMAND_DISPATCH_CANDIDATE.name().equals(item.previewAction());
    }

    private AgentToolDagExecutionDryRunItemView toSelectedItem(String sessionId,
                                                               String runId,
                                                               AgentToolDagExecutionPreviewItemView item) {
        if (AgentToolDagExecutionPreviewAction.SYNC_AUTO_EXECUTE_CANDIDATE.name().equals(item.previewAction())) {
            List<String> reasons = new ArrayList<>(item.reasons());
            reasons.add("本次 dry-run 将该节点映射到同步自动执行入口的 dryRun=true 二次确认，不会执行真实工具。");
            List<String> actions = new ArrayList<>(item.recommendedActions());
            actions.add("如用户确认且生产策略允许，可再调用同一路径并设置 dryRun=false；真实入口会重新读取最新状态和策略。");
            return fromPreview(
                    item,
                    true,
                    AgentToolDagExecutionDryRunAction.SYNC_AUTO_EXECUTE_DRY_RUN,
                    "POST /agent-runtime/sessions/" + sessionId + "/runs/" + runId
                            + "/tool-executions/auto-execute-sync {dryRun=true, auditIds=[" + item.auditId() + "]}",
                    reasons,
                    actions
            );
        }
        if (AgentToolDagExecutionPreviewAction.ASYNC_COMMAND_DISPATCH_CANDIDATE.name().equals(item.previewAction())) {
            List<String> reasons = new ArrayList<>(item.reasons());
            reasons.add("本次 dry-run 只展示异步 command enqueue 预案，不会写 outbox、不会投递 Kafka、不会创建 task-management 任务。");
            List<String> actions = new ArrayList<>(item.recommendedActions());
            actions.add("后续真实推进应进入受控 outbox/dispatcher，并由 task-management worker 负责租约、重试、幂等和状态回写。");
            return fromPreview(
                    item,
                    true,
                    AgentToolDagExecutionDryRunAction.ASYNC_OUTBOX_ENQUEUE_PREVIEW,
                    "异步 outbox enqueue 预案：commandId=" + item.asyncCommandId() + "；当前接口仅预演，不执行",
                    reasons,
                    actions
            );
        }
        List<String> reasons = new ArrayList<>(item.reasons());
        reasons.add("调用方选择了该节点，但上游 previewAction=" + item.previewAction() + "，当前不能进入拟执行批次。");
        List<String> actions = new ArrayList<>(item.recommendedActions());
        actions.add("请先处理 preview 提示的依赖、参数、审批、权限或策略问题，再重新发起 dry-run。");
        return fromPreview(
                item,
                true,
                AgentToolDagExecutionDryRunAction.BLOCKED_BY_PREVIEW,
                item.executionPath(),
                reasons,
                actions
        );
    }

    private AgentToolDagExecutionDryRunItemView toNotSelectedItem(AgentToolDagExecutionPreviewItemView item,
                                                                   boolean explicitSelection) {
        List<String> reasons = new ArrayList<>(item.reasons());
        if (explicitSelection) {
            reasons.add("该节点存在于 preview，但不在本次 nodeIds/auditIds 选择范围内。");
        } else {
            reasons.add("默认 dry-run 只选择同步/异步可执行候选；该节点当前不是可推进候选。");
        }
        return fromPreview(
                item,
                false,
                AgentToolDagExecutionDryRunAction.NOT_SELECTED,
                item.executionPath(),
                reasons,
                item.recommendedActions()
        );
    }

    private AgentToolDagExecutionDryRunItemView toBatchLimitItem(AgentToolDagExecutionPreviewItemView item,
                                                                  int effectiveMaxNodes) {
        List<String> reasons = new ArrayList<>(item.reasons());
        reasons.add("该节点被请求命中，但本次 dry-run 已达到 maxNodes=" + effectiveMaxNodes + " 的服务端有效上限。");
        List<String> actions = new ArrayList<>(item.recommendedActions());
        actions.add("如确需推进更多节点，请分批提交，或在补齐租户配额、工具限流和并发池策略后提高批量上限。");
        return fromPreview(
                item,
                true,
                AgentToolDagExecutionDryRunAction.BATCH_LIMIT_REACHED,
                item.executionPath(),
                reasons,
                actions
        );
    }

    private AgentToolDagExecutionDryRunItemView fromPreview(AgentToolDagExecutionPreviewItemView item,
                                                             boolean selected,
                                                             AgentToolDagExecutionDryRunAction action,
                                                             String executionPath,
                                                             List<String> reasons,
                                                             List<String> actions) {
        AgentToolServiceAuthorizationPreviewView authorization = item.serviceAuthorization();
        return new AgentToolDagExecutionDryRunItemView(
                item.nodeId(),
                item.auditId(),
                selectorFor(item),
                item.toolCode(),
                selected,
                item.previewAction(),
                action.name(),
                executionPath,
                item.readyForExecution(),
                item.wouldTriggerSideEffect(),
                item.asyncDispatchable(),
                item.asyncCommandId(),
                authorization == null ? null : authorization.decision(),
                authorization == null ? null : authorization.allowed(),
                authorization == null ? List.of() : authorization.policyVersions(),
                authorization == null ? List.of() : authorization.delegationEvidence(),
                item.sandboxAllowed(),
                item.sandboxIsolationMode(),
                item.sandboxIssueCodes(),
                item.sandboxReasons(),
                item.sandboxRecommendedActions(),
                item.runtimeProtectionAllowed(),
                item.runtimeGlobalInFlight(),
                item.runtimeTenantInFlight(),
                item.runtimeTargetServiceInFlight(),
                item.runtimeMaxGlobalInFlight(),
                item.runtimeMaxTenantInFlight(),
                item.runtimeMaxTargetServiceInFlight(),
                item.runtimeCircuitOpen(),
                item.runtimeCircuitOpenUntil(),
                item.runtimeConsecutiveFailures(),
                item.runtimeProtectionIssueCodes(),
                item.runtimeProtectionReasons(),
                item.runtimeProtectionRecommendedActions(),
                item.riskLevel(),
                item.readOnly(),
                item.idempotent(),
                item.requiresApproval(),
                List.copyOf(reasons),
                List.copyOf(actions)
        );
    }

    private String selectorFor(AgentToolDagExecutionPreviewItemView item) {
        if (item.nodeId() != null && !item.nodeId().isBlank()) {
            return "nodeId:" + item.nodeId();
        }
        return "auditId:" + item.auditId();
    }

    private void addNotFoundSelectors(List<String> requestedNodeIds,
                                      List<String> requestedAuditIds,
                                      Set<String> foundNodeIds,
                                      Set<String> foundAuditIds,
                                      List<AgentToolDagExecutionDryRunItemView> items) {
        for (String nodeId : requestedNodeIds) {
            if (!foundNodeIds.contains(nodeId)) {
                items.add(notFoundItem("nodeId:" + nodeId, nodeId, null));
            }
        }
        for (String auditId : requestedAuditIds) {
            if (!foundAuditIds.contains(auditId)) {
                items.add(notFoundItem("auditId:" + auditId, null, auditId));
            }
        }
    }

    private AgentToolDagExecutionDryRunItemView notFoundItem(String selector, String nodeId, String auditId) {
        return new AgentToolDagExecutionDryRunItemView(
                nodeId,
                auditId,
                selector,
                null,
                false,
                null,
                AgentToolDagExecutionDryRunAction.REQUESTED_NODE_OR_AUDIT_NOT_FOUND.name(),
                null,
                false,
                false,
                false,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                List.of("调用方显式请求的目标在当前 Run 的 DAG execution preview 中不存在。"),
                List.of("请确认 nodeId/auditId 是否来自同一个 sessionId/runId，并在工具计划刷新后重新获取 preview。")
        );
    }

    private List<String> buildSummaryReasons(boolean explicitSelection,
                                             AgentRunToolDagExecutionPreviewView preview,
                                             List<AgentToolDagExecutionDryRunItemView> items,
                                             int effectiveMaxNodes) {
        List<String> reasons = new ArrayList<>();
        reasons.add("当前接口只生成 DAG-aware execution dry-run 预案，不执行工具、不创建任务、不写 outbox、不投递 Kafka。");
        if (explicitSelection) {
            reasons.add("本次 dry-run 使用调用方提供的 nodeIds/auditIds 作为选择范围，服务端仍会复用 preview 结果做安全解释。");
        } else {
            reasons.add("请求未指定 nodeIds/auditIds，因此默认只选择 preview 中同步自动执行候选和异步 command 候选。");
        }
        if (count(items, AgentToolDagExecutionDryRunAction.BATCH_LIMIT_REACHED) > 0) {
            reasons.add("部分节点因 effectiveMaxNodes=" + effectiveMaxNodes + " 被暂缓，建议分批推进，避免一次性扩大自动化副作用面。");
        }
        if (count(items, AgentToolDagExecutionDryRunAction.REQUESTED_NODE_OR_AUDIT_NOT_FOUND) > 0) {
            reasons.add("存在请求目标未命中当前 preview，说明调用方可能使用了过期 DAG、跨 Run auditId 或错误节点 ID。");
        }
        if (Boolean.FALSE.equals(preview.hasExecutableCandidates())) {
            reasons.add("上游 preview 当前没有可执行候选，通常需要先处理依赖、审批、参数补全或服务间授权。");
        }
        if (sandboxRejectedCount(items) > 0) {
            reasons.add("本次 dry-run 包含沙箱拒绝的节点；这些节点即使命中选择器，也不会进入真实执行候选。");
        }
        if (runtimeProtectionRejectedCount(items) > 0) {
            reasons.add("本次 dry-run 包含运行时保护暂缓的节点；这些节点即使命中选择器，也不应进入真实 execute 或异步 outbox。");
        }
        return reasons;
    }

    private List<String> buildRecommendedActions(List<AgentToolDagExecutionDryRunItemView> items) {
        List<String> actions = new ArrayList<>();
        if (count(items, AgentToolDagExecutionDryRunAction.SYNC_AUTO_EXECUTE_DRY_RUN) > 0) {
            actions.add("同步候选应先让用户或策略层确认 dry-run 结果，再调用 auto-execute-sync dryRun=false 触发真实执行。");
        }
        if (count(items, AgentToolDagExecutionDryRunAction.ASYNC_OUTBOX_ENQUEUE_PREVIEW) > 0) {
            actions.add("异步候选不应在 HTTP 线程中执行，后续应进入 outbox/dispatcher/task-management worker 链路。");
        }
        if (count(items, AgentToolDagExecutionDryRunAction.BLOCKED_BY_PREVIEW) > 0) {
            actions.add("被 preview 阻断的节点应先处理依赖、参数、审批、权限或策略问题，而不是绕过 preview 直接调用执行入口。");
        }
        if (sandboxRejectedCount(items) > 0) {
            actions.add("处理 dry-run item 中的 sandboxIssueCodes，再重新生成 execution-policy、preview 和 dry-run。");
        }
        if (runtimeProtectionRejectedCount(items) > 0) {
            actions.add("处理 dry-run item 中的 runtimeProtectionIssueCodes：并发超限时等待或拆批，目标服务熔断时先排查下游健康。");
        }
        actions.add("进入真实 DAG worker 前，还需要补齐租户配额、工具级限流、并发池、worker 指标和失败补偿策略。");
        return actions;
    }

    private int countSelectedWithinLimit(List<AgentToolDagExecutionDryRunItemView> items) {
        return (int) items.stream()
                .filter(item -> Boolean.TRUE.equals(item.selected()))
                .filter(item -> !AgentToolDagExecutionDryRunAction.BATCH_LIMIT_REACHED.name().equals(item.dryRunAction()))
                .count();
    }

    private int count(List<AgentToolDagExecutionDryRunItemView> items, AgentToolDagExecutionDryRunAction action) {
        return (int) items.stream()
                .filter(item -> action.name().equals(item.dryRunAction()))
                .count();
    }

    private int sandboxRejectedCount(List<AgentToolDagExecutionDryRunItemView> items) {
        return (int) items.stream()
                .filter(item -> Boolean.FALSE.equals(item.sandboxAllowed()))
                .count();
    }

    private int runtimeProtectionRejectedCount(List<AgentToolDagExecutionDryRunItemView> items) {
        return (int) items.stream()
                .filter(item -> Boolean.FALSE.equals(item.runtimeProtectionAllowed()))
                .count();
    }
}

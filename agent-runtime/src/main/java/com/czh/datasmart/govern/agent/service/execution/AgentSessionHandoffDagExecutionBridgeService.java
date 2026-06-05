/**
 * @Author : Cui
 * @Date: 2026/06/05 00:00
 * @Description DataSmart Govern Backend - AgentSessionHandoffDagExecutionBridgeService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionDryRunRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagExecutionDryRunResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionHandoffDagExecutionBridgePreviewRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentSessionHandoffDagExecutionBridgePreviewResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolDagExecutionDryRunItemView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Master Agent handoff DAG 与现有 Tool DAG 执行链路之间的桥接预检服务。
 *
 * <p>5.16 已经让 Java 控制面能展示 handoff DAG，但那张图本身不应该直接执行工具。DataSmart 现有执行链路
 * 已经具备 Tool DAG dry-run、selected-node confirmation、async command outbox、dispatcher pre-check 等
 * 治理能力；本服务的职责是把 handoff DAG 上的“tool-control”选择安全地翻译为这些既有入口的预检结果。</p>
 *
 * <p>为什么第一版只做 preview 而不直接入箱：handoff DAG 是会话级协作图，Tool DAG 是具体工具执行图。
 * 二者粒度不同。如果用户在 handoff DAG 上点了“tool-control”，系统仍需要重新读取当前 Run 的工具 DAG、
 * 依赖、权限、沙箱、容量保护和策略版本，再决定哪些具体工具节点可推进。直接从 handoff DAG 写 outbox
 * 会绕过这些细粒度判断，违背商业化 Agent 平台对可审计、可暂停、可恢复执行的要求。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentSessionHandoffDagExecutionBridgeService {

    private static final String TOOL_CONTROL_NODE = "tool-control";
    private static final String ACTION_TOOL_CONTROL_DRY_RUN = "TOOL_CONTROL_DRY_RUN";
    private static final String ACTION_HANDOFF_NODE_NOT_EXECUTABLE = "HANDOFF_NODE_NOT_EXECUTABLE";
    private static final String ACTION_NO_TOOL_CANDIDATE = "NO_TOOL_CANDIDATE";

    private final AgentRunToolDagExecutionDryRunService dryRunService;

    /**
     * 生成 handoff DAG 到 Tool DAG 的执行桥接预览。
     *
     * <p>流程说明：</p>
     * <p>1. 规范化 handoffNodeIds/toolNodeIds/toolAuditIds，去空、去重、保序；</p>
     * <p>2. 如果 handoff 选择不包含 tool-control，则仍可返回一个空选择 dry-run，帮助调用方看到“当前选择不是工具执行阶段”；</p>
     * <p>3. 如果选择了 tool-control，则调用现有 dry-run 服务。调用方显式传了 toolNodeIds/toolAuditIds 时只预检这些工具；
     * 否则使用 dry-run 的默认候选规则，自动挑选当前可推进的同步或异步工具候选；</p>
     * <p>4. 根据 dry-run 结果构造 selected-node outbox request template。模板只给下一步人工确认或 Agent policy 层使用，
     * 本服务不会调用 selected-node outbox endpoint。</p>
     *
     * @param sessionId Agent 会话 ID。
     * @param runId Agent Run ID。
     * @param request 桥接预检请求，可为空；为空时默认尝试 tool-control dry-run。
     * @param traceId 当前链路 ID，用于 dry-run runtime event 关联。
     * @return 桥接预检结果。
     */
    public AgentSessionHandoffDagExecutionBridgePreviewResponse previewBridge(
            String sessionId,
            String runId,
            AgentSessionHandoffDagExecutionBridgePreviewRequest request,
            String traceId) {
        AgentSessionHandoffDagExecutionBridgePreviewRequest safeRequest = request == null
                ? new AgentSessionHandoffDagExecutionBridgePreviewRequest(
                List.of(TOOL_CONTROL_NODE), List.of(), List.of(), null, false, true)
                : request;
        List<String> handoffNodeIds = normalize(safeRequest.handoffNodeIds());
        if (handoffNodeIds.isEmpty()) {
            handoffNodeIds = List.of(TOOL_CONTROL_NODE);
        }
        List<String> mappedToolNodeIds = normalize(safeRequest.toolNodeIds());
        List<String> mappedToolAuditIds = normalize(safeRequest.toolAuditIds());
        boolean toolControlSelected = containsToolControl(handoffNodeIds);

        AgentRunToolDagExecutionDryRunResponse dryRun = dryRunService.dryRunDagExecution(
                sessionId,
                runId,
                new AgentRunToolDagExecutionDryRunRequest(
                        toolControlSelected ? mappedToolNodeIds : List.of(),
                        toolControlSelected ? mappedToolAuditIds : List.of(),
                        safeRequest.maxNodes(),
                        safeRequest.includeUnselectedPreviewItems()
                ),
                traceId
        );
        String bridgeAction = resolveBridgeAction(toolControlSelected, dryRun);
        boolean bridgeReady = ACTION_TOOL_CONTROL_DRY_RUN.equals(bridgeAction)
                && dryRun.selectedCount() != null
                && dryRun.selectedCount() > 0;
        Map<String, Object> template = Boolean.TRUE.equals(safeRequest.buildSelectedNodeOutboxTemplate())
                ? selectedNodeOutboxTemplate(dryRun)
                : Map.of();

        return new AgentSessionHandoffDagExecutionBridgePreviewResponse(
                sessionId,
                runId,
                bridgeReady,
                bridgeAction,
                handoffNodeIds,
                mappedToolNodeIds,
                mappedToolAuditIds,
                dryRun,
                template,
                summaryReasons(toolControlSelected, dryRun, bridgeAction),
                recommendedActions(toolControlSelected, dryRun, bridgeAction, template)
        );
    }

    private String resolveBridgeAction(boolean toolControlSelected, AgentRunToolDagExecutionDryRunResponse dryRun) {
        if (!toolControlSelected) {
            return ACTION_HANDOFF_NODE_NOT_EXECUTABLE;
        }
        if (dryRun.selectedCount() == null || dryRun.selectedCount() <= 0) {
            return ACTION_NO_TOOL_CANDIDATE;
        }
        return ACTION_TOOL_CONTROL_DRY_RUN;
    }

    private Map<String, Object> selectedNodeOutboxTemplate(AgentRunToolDagExecutionDryRunResponse dryRun) {
        List<String> asyncNodeIds = new ArrayList<>();
        List<String> asyncAuditIds = new ArrayList<>();
        Map<String, String> policyVersionsByAuditId = new LinkedHashMap<>();
        for (AgentToolDagExecutionDryRunItemView item : dryRun.items()) {
            if (!AgentToolDagExecutionDryRunAction.ASYNC_OUTBOX_ENQUEUE_PREVIEW.name().equals(item.dryRunAction())) {
                continue;
            }
            if (hasText(item.nodeId())) {
                asyncNodeIds.add(item.nodeId());
            }
            if (hasText(item.auditId())) {
                asyncAuditIds.add(item.auditId());
                String policyVersion = firstText(item.serviceAuthorizationPolicyVersions());
                if (hasText(policyVersion)) {
                    policyVersionsByAuditId.put(item.auditId(), policyVersion);
                }
            }
        }
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("nodeIds", List.copyOf(asyncNodeIds));
        template.put("auditIds", List.copyOf(asyncAuditIds));
        template.put("maxNodes", dryRun.requestedMaxNodes());
        template.put("expectedDryRunFingerprint", dryRun.selectionFingerprint());
        template.put("expectedPolicyVersionsByAuditId", Map.copyOf(policyVersionsByAuditId));
        /*
         * 这里必须明确为 false。桥接预览只负责告诉调用方“如果要继续，应携带哪些字段去 selected-node outbox endpoint”，
         * 不替用户、策略或审批流做最终确认。真实确认时调用方必须显式把 confirmed 改为 true，服务端仍会重新 dry-run。
         */
        template.put("confirmed", false);
        return Map.copyOf(template);
    }

    private List<String> summaryReasons(boolean toolControlSelected,
                                        AgentRunToolDagExecutionDryRunResponse dryRun,
                                        String bridgeAction) {
        List<String> reasons = new ArrayList<>();
        reasons.add("本响应只做 handoff DAG 到 Tool DAG dry-run 的桥接预检，不执行工具、不写 outbox、不创建确认记录。");
        if (!toolControlSelected) {
            reasons.add("本次 handoff 选择不包含 tool-control，因此不会把 Master/Memory/Feedback 等会话级节点强行映射为工具执行。");
        } else {
            reasons.add("本次 handoff 选择包含 tool-control，服务端已复用现有 DAG dry-run 重新计算工具级执行预案。");
        }
        reasons.add("bridgeAction=" + bridgeAction + "，dryRun selectedCount=" + dryRun.selectedCount()
                + "，asyncEnqueuePreviewCount=" + dryRun.asyncEnqueuePreviewCount() + "。");
        return List.copyOf(reasons);
    }

    private List<String> recommendedActions(boolean toolControlSelected,
                                            AgentRunToolDagExecutionDryRunResponse dryRun,
                                            String bridgeAction,
                                            Map<String, Object> template) {
        List<String> actions = new ArrayList<>();
        if (!toolControlSelected) {
            actions.add("如果要推进真实工具执行，请先在 handoff DAG 上选择 tool-control，或从 Tool DAG 视图中选择具体 nodeId/auditId。");
            return List.copyOf(actions);
        }
        if (ACTION_NO_TOOL_CANDIDATE.equals(bridgeAction)) {
            actions.add("当前没有可推进工具候选；请查看 dryRun.items 中的阻断原因，处理依赖、审批、参数、沙箱或容量问题后重新预检。");
            return List.copyOf(actions);
        }
        if (dryRun.asyncEnqueuePreviewCount() != null && dryRun.asyncEnqueuePreviewCount() > 0) {
            actions.add("如需推进异步工具，请让用户、策略或审批流确认后，调用 dag-selected-node-outbox/enqueue，并携带模板中的 fingerprint 与 policyVersion。");
        }
        if (dryRun.syncDryRunCandidateCount() != null && dryRun.syncDryRunCandidateCount() > 0) {
            actions.add("同步工具候选仍应走 auto-execute-sync 的 dryRun=true/false 双阶段确认，不应通过异步 selected-node outbox 入箱。");
        }
        if (template.isEmpty()) {
            actions.add("本次未生成 selected-node outbox 模板；如果前端需要确认按钮，请传 buildSelectedNodeOutboxTemplate=true。");
        }
        actions.add("真实执行前服务端会重新 dry-run，因此预览模板不是授权令牌，只是下一步受控调用的低敏参数建议。");
        return List.copyOf(actions);
    }

    private boolean containsToolControl(List<String> handoffNodeIds) {
        return handoffNodeIds.stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(TOOL_CONTROL_NODE::equals);
    }

    private List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (hasText(value)) {
                normalized.add(value.trim());
            }
        }
        return List.copyOf(normalized);
    }

    private String firstText(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream().filter(this::hasText).findFirst().orElse(null);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

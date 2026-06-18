/**
 * @Author : Cui
 * @Date: 2026/06/18 01:39
 * @Description DataSmart Govern Backend - AgentToolActionResumeGateGraphBuilder.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionExecutionGraphEdgeView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionExecutionGraphNodeView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactBundleResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactOutboxSummaryView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactReceiptSummaryView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeFactView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionResumeGateGraphView;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具动作恢复门控图构建器。
 *
 * <p>恢复事实包已经能告诉调用方“审批、澄清、outbox、receipt 哪些事实可用”。本构建器负责把这些事实转换成
 * 更直观的条件图：checkpoint/locator -> security scope -> fact gates -> resume gate。这样设计的好处是，
 * 后续无论入口来自 MCP `tools/call`、模型 tool_call、A2A action，还是 DataSmart 自己的技能系统，
 * 都可以复用同一套恢复前门控解释，而不用在每个协议 adapter 中散落 if/else。</p>
 *
 * <p>职责边界：本类只解释低敏响应，不查询数据库、不调用 permission-admin、不写 outbox、不恢复 Python checkpoint。
 * 真实恢复执行必须由后续 runner 在读取同一批 host facts 后再次做权限、幂等、预算、审计和 worker 回执校验。</p>
 */
final class AgentToolActionResumeGateGraphBuilder {

    static final String GRAPH_SCHEMA_VERSION = "datasmart.agent-runtime.tool-action-resume-gate-graph.v1";
    static final String STATE_READY = "READY_FOR_RESUME_PREVIEW";
    static final String STATE_WAITING = "WAITING_RESUME_FACTS";
    static final String STATE_REJECTED = "RESUME_BLOCKED_BY_REJECTED_FACT";

    private static final String FACT_APPROVAL = "APPROVAL_CONFIRMATION_FACT";
    private static final String FACT_CLARIFICATION = "CLARIFICATION_FACT";
    private static final String FACT_OUTBOX = "OUTBOX_WRITE_CONFIRMATION";
    private static final String FACT_WORKER_RECEIPT = "WORKER_RECEIPT_PROJECTION";

    /**
     * 将事实包转换成单张恢复门控图。
     *
     * <p>节点顺序刻意固定：定位节点先确认 checkpoint/thread/command 这些“从哪里恢复”的线索；
     * 安全范围节点说明请求已被 gateway/permission-admin Header 收口；各事实节点说明恢复前置条件是否齐备；
     * 最终 resume gate 只表达是否允许做 resume-preview，不表达真实工具已经执行。</p>
     *
     * @param bundle Java 控制面返回的低敏恢复事实包。
     * @return 图形化恢复门控视图。
     */
    AgentToolActionResumeGateGraphView build(AgentToolActionResumeFactBundleResponse bundle) {
        AgentToolActionResumeFactBundleResponse safeBundle = bundle == null ? emptyBundle() : bundle;
        String graphState = graphState(safeBundle);
        boolean resumePreviewReady = STATE_READY.equals(graphState);
        List<AgentToolActionExecutionGraphNodeView> nodes = new ArrayList<>();
        List<AgentToolActionExecutionGraphEdgeView> edges = new ArrayList<>();

        nodes.add(locatorNode(safeBundle));
        nodes.add(securityScopeNode(safeBundle));
        edges.add(edge("checkpoint-locator", "security-scope", "LOCATOR_TO_SECURITY_SCOPE", "ALWAYS",
                "恢复前必须先把 checkpoint/thread/command 等低敏定位符收口到当前租户、项目和 actor 范围。"));

        /*
         * 每个 fact node 都从 security-scope 出发，表示“事实是否可见”必须先满足当前请求的数据范围。
         * 如果未来加入 service-account mTLS 或签名校验，可以在 security-scope 与 fact gate 之间再插入认证节点。
         */
        for (AgentToolActionResumeFactView fact : safeList(safeBundle.facts())) {
            nodes.add(factNode(fact, safeBundle));
            edges.add(edge("security-scope", factNodeId(fact.factType()), "SECURITY_SCOPE_TO_FACT_GATE",
                    "WHEN_REQUEST_SCOPE_RESTRICTED",
                    "事实验真必须在 Header 与请求体求交后的范围内进行，调用方不能自报更大的 tenant/project/actor。"));
        }
        addOutboxToReceiptEdgeIfNeeded(safeBundle, edges);

        nodes.add(resumeGateNode(safeBundle, graphState, resumePreviewReady));
        for (AgentToolActionResumeFactView fact : safeList(safeBundle.facts())) {
            edges.add(edge(factNodeId(fact.factType()), "resume-gate", "FACT_GATE_TO_RESUME_GATE",
                    "WHEN_FACT_EVALUATED",
                    "每类恢复事实都必须被纳入最终门控判断，避免只凭单一 approval 或 commandId 恢复工具。"));
        }

        int blockedNodeCount = (int) nodes.stream()
                .filter(node -> !Boolean.TRUE.equals(node.executable()))
                .count();
        int executableNodeCount = nodes.size() - blockedNodeCount;
        return new AgentToolActionResumeGateGraphView(
                graphId(safeBundle),
                GRAPH_SCHEMA_VERSION,
                true,
                graphState,
                terminalState(graphState),
                resumePreviewReady,
                safeBundle.payloadPolicy(),
                safeBundle.requestedLocator(),
                nodes.size(),
                edges.size(),
                blockedNodeCount,
                executableNodeCount,
                safeBundle.requiredFactTypes(),
                safeBundle.availableFactTypes(),
                safeBundle.missingFactTypes(),
                safeBundle.rejectedFactTypes(),
                graphSummary(safeBundle, graphState),
                graphActions(safeBundle, graphState),
                nodes,
                edges
        );
    }

    private AgentToolActionExecutionGraphNodeView locatorNode(AgentToolActionResumeFactBundleResponse bundle) {
        Map<String, Object> locator = safeMap(bundle.requestedLocator());
        boolean hasCheckpointOrCommand = bool(locator.get("checkpointIdPresent"))
                || bool(locator.get("threadIdPresent"))
                || hasText(locator.get("commandId"))
                || hasText(locator.get("runId"))
                || hasText(locator.get("sessionId"));
        boolean locatorIndexHit = bool(locator.get("locatorIndexHit"));
        List<String> evidenceRefs = new ArrayList<>();
        evidenceRefs.add("checkpointIdPresent:" + bool(locator.get("checkpointIdPresent")));
        evidenceRefs.add("threadIdPresent:" + bool(locator.get("threadIdPresent")));
        evidenceRefs.add("runIdPresent:" + hasText(locator.get("runId")));
        evidenceRefs.add("sessionIdPresent:" + hasText(locator.get("sessionId")));
        evidenceRefs.add("commandIdPresent:" + hasText(locator.get("commandId")));
        evidenceRefs.add("locatorIndexHit:" + locatorIndexHit);
        return node(
                "checkpoint-locator",
                "CHECKPOINT_LOCATOR",
                "RESUME_LOCATOR",
                hasCheckpointOrCommand ? "LOCATOR_HINTS_AVAILABLE" : "LOCATOR_HINTS_MISSING",
                true,
                hasCheckpointOrCommand,
                List.of(),
                evidenceRefs,
                hasCheckpointOrCommand ? List.of() : List.of("CHECKPOINT_THREAD_OR_COMMAND_LOCATOR_REQUIRED"),
                List.of(
                        "定位节点表示本次恢复预览已经拿到 checkpoint/thread/run/session/command 中至少一类低敏线索。",
                        "locator index 命中时，Java 会从历史安全定位索引补齐缺失字段，但不会把 approvalFactId 或 outboxId 原文回显。"
                ),
                hasCheckpointOrCommand
                        ? List.of("继续进入安全范围节点，确认定位符没有越过租户、项目或 actor 边界。")
                        : List.of("由 Python checkpoint 或智能网关补充 threadId、checkpointId、runId、sessionId 或 commandId。")
        );
    }

    private AgentToolActionExecutionGraphNodeView securityScopeNode(AgentToolActionResumeFactBundleResponse bundle) {
        Map<String, Object> locator = safeMap(bundle.requestedLocator());
        boolean hasTenant = hasText(locator.get("scopedTenantId"));
        boolean hasActor = hasText(locator.get("scopedActorId"));
        boolean scopeReady = hasTenant && hasActor;
        return node(
                "security-scope",
                "SECURITY_SCOPE",
                "TENANT_PROJECT_ACTOR_SCOPE",
                scopeReady ? "SCOPE_RESTRICTED" : "SCOPE_INCOMPLETE",
                true,
                scopeReady,
                scopeReady ? List.of() : List.of("checkpoint-locator"),
                List.of(
                        "scopedTenantIdPresent:" + hasTenant,
                        "scopedProjectIdPresent:" + hasText(locator.get("scopedProjectId")),
                        "scopedActorIdPresent:" + hasActor
                ),
                scopeReady ? List.of() : List.of("TENANT_AND_ACTOR_SCOPE_REQUIRED"),
                List.of(
                        "安全范围节点表达 gateway/permission-admin Header 已经参与收口，body 参数不能扩大可见范围。",
                        "真实恢复执行还需要服务账号签名或 mTLS，本节点当前只表达控制面查询范围。"
                ),
                scopeReady
                        ? List.of("继续逐项验真审批、澄清、outbox 和 worker receipt 等恢复事实。")
                        : List.of("补齐当前租户、actor 和数据范围 Header；缺少身份时必须 fail-closed。")
        );
    }

    private AgentToolActionExecutionGraphNodeView factNode(AgentToolActionResumeFactView fact,
                                                           AgentToolActionResumeFactBundleResponse bundle) {
        boolean available = Boolean.TRUE.equals(fact.available()) && !Boolean.TRUE.equals(fact.rejected());
        boolean rejected = Boolean.TRUE.equals(fact.rejected());
        boolean retryable = Boolean.TRUE.equals(fact.retryable());
        List<String> evidenceRefs = new ArrayList<>();
        evidenceRefs.add("source:" + defaultText(fact.source(), "UNKNOWN_SOURCE"));
        evidenceRefs.add("status:" + defaultText(fact.status(), "UNKNOWN_STATUS"));
        evidenceRefs.addAll(prefixAll("evidence:", fact.evidenceCodes()));
        evidenceRefs.addAll(outboxEvidence(fact.factType(), bundle.outboxSummary()));
        evidenceRefs.addAll(receiptEvidence(fact.factType(), bundle.receiptSummary()));
        return node(
                factNodeId(fact.factType()),
                factNodeType(fact.factType()),
                "RESUME_FACT_GATE",
                factNodeStatus(fact, available, rejected, retryable),
                true,
                available,
                available ? List.of() : List.of("security-scope"),
                evidenceRefs,
                rejected || !available ? safeList(fact.issueCodes()) : List.of(),
                factReasons(fact),
                factActions(fact, available, rejected, retryable)
        );
    }

    private AgentToolActionExecutionGraphNodeView resumeGateNode(AgentToolActionResumeFactBundleResponse bundle,
                                                                 String graphState,
                                                                 boolean resumePreviewReady) {
        List<String> blockedBy = new ArrayList<>();
        for (String factType : safeList(bundle.missingFactTypes())) {
            blockedBy.add(factNodeId(factType));
        }
        for (String factType : safeList(bundle.rejectedFactTypes())) {
            blockedBy.add(factNodeId(factType));
        }
        return node(
                "resume-gate",
                "RESUME_GATE",
                "FINAL_RESUME_PREVIEW_GATE",
                resumePreviewReady ? "READY_FOR_PREVIEW_ONLY_RESUME" : graphState,
                true,
                resumePreviewReady,
                blockedBy,
                List.of(
                        "requiredFactCount:" + safeList(bundle.requiredFactTypes()).size(),
                        "availableFactCount:" + safeList(bundle.availableFactTypes()).size(),
                        "missingFactCount:" + safeList(bundle.missingFactTypes()).size(),
                        "rejectedFactCount:" + safeList(bundle.rejectedFactTypes()).size()
                ),
                resumePreviewReady ? List.of() : missingForResumeGate(bundle),
                List.of(
                        "最终门控节点只判断是否允许进入 Python/OpenClaw/LangGraph 的 resume-preview。",
                        "即使本节点 READY，也不代表工具副作用已经执行；真实执行仍必须经过 outbox、worker、receipt 和审计闭环。"
                ),
                graphActions(bundle, graphState)
        );
    }

    private String graphState(AgentToolActionResumeFactBundleResponse bundle) {
        if (!safeList(bundle.rejectedFactTypes()).isEmpty()) {
            return STATE_REJECTED;
        }
        if (!safeList(bundle.missingFactTypes()).isEmpty()) {
            return STATE_WAITING;
        }
        return STATE_READY;
    }

    private String terminalState(String graphState) {
        return switch (graphState) {
            case STATE_READY -> "READY_FOR_PYTHON_RESUME_PREVIEW_ONLY";
            case STATE_REJECTED -> "STOP_BEFORE_RESUME_PREVIEW";
            default -> "WAIT_FOR_CONTROL_PLANE_FACTS";
        };
    }

    private List<String> graphSummary(AgentToolActionResumeFactBundleResponse bundle, String graphState) {
        List<String> summary = new ArrayList<>();
        summary.add("恢复门控图当前状态为 " + graphState + "，它来自 Java 控制面事实包，而不是调用方自报结果。");
        summary.add("图中节点只展示事实类型、状态、证据码和问题码，不展示审批 ID、澄清内容、payloadReference、SQL、prompt 或工具参数。");
        if (STATE_READY.equals(graphState)) {
            summary.add("所有要求的恢复事实已被采信，可进入 Python resume-preview，但仍不能跳过 Java outbox/worker 的真实执行链路。");
        }
        if (STATE_WAITING.equals(graphState)) {
            summary.add("当前仍缺失部分恢复事实，应等待审批、澄清、outbox 写入或 worker receipt 物化后再重试。");
        }
        if (STATE_REJECTED.equals(graphState)) {
            summary.add("至少一个恢复事实已被服务端拒绝，必须重新生成或修复对应事实，不能继续 resume-preview。");
        }
        return List.copyOf(summary);
    }

    private List<String> graphActions(AgentToolActionResumeFactBundleResponse bundle, String graphState) {
        List<String> actions = new ArrayList<>(safeList(bundle.recommendedActions()));
        if (STATE_READY.equals(graphState)) {
            actions.add("CALL_PYTHON_RUNTIME_RESUME_PREVIEW_WITHOUT_DIRECT_SIDE_EFFECT_EXECUTION");
        } else if (STATE_REJECTED.equals(graphState)) {
            actions.add("FAIL_CLOSED_AND_RECREATE_REJECTED_CONTROL_PLANE_FACTS");
        } else {
            actions.add("RETRY_AFTER_CONTROL_PLANE_FACTS_ARE_MATERIALIZED");
        }
        return List.copyOf(actions);
    }

    private List<String> factReasons(AgentToolActionResumeFactView fact) {
        return switch (defaultText(fact.factType(), "UNKNOWN")) {
            case FACT_APPROVAL -> List.of(
                    "审批事实节点用于证明敏感工具调用已经被 permission-admin 或确认页采信。",
                    "调用方传入 approvalFactId 不等于审批通过，必须由 Java 控制面回查并验证作用域。"
            );
            case FACT_CLARIFICATION -> List.of(
                    "澄清事实节点用于证明用户或上游 Agent 已通过受控入口补充了缺失信息。",
                    "本节点只展示澄清事实状态，不保存也不回显用户澄清原文。"
            );
            case FACT_OUTBOX -> List.of(
                    "outbox 事实节点用于证明 Java 已把可恢复命令写入 durable outbox。",
                    "它不展示载荷引用或服务间命令体正文，避免控制面图扩散工具参数和内部投递内容。"
            );
            case FACT_WORKER_RECEIPT -> List.of(
                    "worker receipt 节点用于证明下游 worker 或 dry-run pre-check 已回写机器状态。",
                    "receipt 只表达 outcome/errorCode 等低敏状态，不把工具结果正文当作事实值回传。"
            );
            default -> List.of("未知事实类型会被按缺失处理，避免新事实类型在未建模前意外放行恢复。");
        };
    }

    private List<String> factActions(AgentToolActionResumeFactView fact,
                                     boolean available,
                                     boolean rejected,
                                     boolean retryable) {
        if (available) {
            return List.of("该事实已可采信，继续汇总到最终 resume gate。");
        }
        if (rejected) {
            return List.of("该事实被服务端拒绝，必须重新生成或修复，不能靠重试绕过。");
        }
        if (retryable) {
            return List.of("该事实暂未完成但可重试，等待控制面物化后重新查询门控图。");
        }
        return List.of("补齐该事实类型，或检查定位符、权限范围、策略版本和 worker 回执链路。");
    }

    private String factNodeStatus(AgentToolActionResumeFactView fact,
                                  boolean available,
                                  boolean rejected,
                                  boolean retryable) {
        if (available) {
            return "FACT_AVAILABLE";
        }
        if (rejected) {
            return "FACT_REJECTED";
        }
        if (retryable) {
            return "FACT_WAITING_RETRYABLE";
        }
        return "FACT_MISSING";
    }

    private String factNodeType(String factType) {
        return switch (defaultText(factType, "UNKNOWN")) {
            case FACT_APPROVAL -> "HUMAN_APPROVAL_FACT";
            case FACT_CLARIFICATION -> "CLARIFICATION_FACT";
            case FACT_OUTBOX -> "OUTBOX_WRITE_FACT";
            case FACT_WORKER_RECEIPT -> "WORKER_RECEIPT_FACT";
            default -> "UNKNOWN_RESUME_FACT";
        };
    }

    private String factNodeId(String factType) {
        return "fact-" + defaultText(factType, "unknown").toLowerCase().replace('_', '-');
    }

    private List<String> outboxEvidence(String factType, AgentToolActionResumeFactOutboxSummaryView summary) {
        if (!FACT_OUTBOX.equals(factType) || summary == null) {
            return List.of();
        }
        return List.of(
                "commandIdPresent:" + hasText(summary.commandId()),
                "outboxStatus:" + defaultText(summary.status(), "UNKNOWN_OUTBOX_STATUS"),
                "payloadReferencePresent:" + Boolean.TRUE.equals(summary.payloadReferencePresent())
        );
    }

    private List<String> receiptEvidence(String factType, AgentToolActionResumeFactReceiptSummaryView summary) {
        if (!FACT_WORKER_RECEIPT.equals(factType) || summary == null) {
            return List.of();
        }
        return List.of(
                "receiptCount:" + summary.receiptCount(),
                "latestOutcome:" + defaultText(summary.latestOutcome(), "UNKNOWN_RECEIPT_OUTCOME"),
                "latestPreCheckPassed:" + Boolean.TRUE.equals(summary.latestPreCheckPassed()),
                "sideEffectExecuted:" + Boolean.TRUE.equals(summary.latestSideEffectExecuted())
        );
    }

    private void addOutboxToReceiptEdgeIfNeeded(AgentToolActionResumeFactBundleResponse bundle,
                                                List<AgentToolActionExecutionGraphEdgeView> edges) {
        if (safeList(bundle.requiredFactTypes()).contains(FACT_OUTBOX)
                && safeList(bundle.requiredFactTypes()).contains(FACT_WORKER_RECEIPT)) {
            edges.add(edge(factNodeId(FACT_OUTBOX), factNodeId(FACT_WORKER_RECEIPT),
                    "OUTBOX_FACT_TO_WORKER_RECEIPT_FACT", "WHEN_COMMAND_MAY_BE_DISPATCHED",
                    "worker receipt 只能证明某条 outbox command 被 worker 观察或预检，不能脱离 command 独立放行。"));
        }
    }

    private List<String> missingForResumeGate(AgentToolActionResumeFactBundleResponse bundle) {
        List<String> missing = new ArrayList<>();
        missing.addAll(prefixAll("missing:", bundle.missingFactTypes()));
        missing.addAll(prefixAll("rejected:", bundle.rejectedFactTypes()));
        return List.copyOf(missing);
    }

    private AgentToolActionExecutionGraphNodeView node(String nodeId,
                                                       String nodeType,
                                                       String stage,
                                                       String status,
                                                       boolean required,
                                                       boolean executable,
                                                       List<String> blockedByNodeIds,
                                                       List<String> evidenceRefs,
                                                       List<String> missingRequirements,
                                                       List<String> reasons,
                                                       List<String> recommendedActions) {
        return new AgentToolActionExecutionGraphNodeView(nodeId, nodeType, stage, status, required, executable,
                safeList(blockedByNodeIds), safeList(evidenceRefs), safeList(missingRequirements),
                safeList(reasons), safeList(recommendedActions));
    }

    private AgentToolActionExecutionGraphEdgeView edge(String fromNodeId,
                                                       String toNodeId,
                                                       String edgeType,
                                                       String condition,
                                                       String reason) {
        return new AgentToolActionExecutionGraphEdgeView(fromNodeId, toNodeId, edgeType, condition, reason);
    }

    private String graphId(AgentToolActionResumeFactBundleResponse bundle) {
        Map<String, Object> locator = safeMap(bundle.requestedLocator());
        String source = defaultText(locator.get("commandId"), "no-command")
                + "|" + defaultText(locator.get("runId"), "no-run")
                + "|" + defaultText(locator.get("sessionId"), "no-session")
                + "|" + defaultText(locator.get("scopedTenantId"), "no-tenant")
                + "|" + defaultText(locator.get("scopedProjectId"), "no-project")
                + "|" + defaultText(locator.get("scopedActorId"), "no-actor");
        return "tool-action-resume-gate-graph:" + shortSha256(source);
    }

    private String shortSha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(defaultText(value, "unknown").getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < Math.min(8, hash.length); i++) {
                builder.append(String.format("%02x", hash[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(defaultText(value, "unknown").hashCode());
        }
    }

    private AgentToolActionResumeFactBundleResponse emptyBundle() {
        return new AgentToolActionResumeFactBundleResponse(
                AgentToolActionResumeFactBundleService.SCHEMA_VERSION,
                true,
                AgentToolActionResumeFactBundleService.QUERY_BOUNDARY,
                AgentToolActionResumeFactBundleService.PAYLOAD_POLICY,
                Map.of(),
                List.of(),
                List.of(),
                List.of("APPROVAL_CONFIRMATION_FACT", "OUTBOX_WRITE_CONFIRMATION", "WORKER_RECEIPT_PROJECTION"),
                List.of(),
                List.of(),
                null,
                null,
                List.of("SUBMIT_LOW_SENSITIVE_LOCATORS_BEFORE_RESUME_GATE_GRAPH_PREVIEW"),
                Map.of(),
                java.time.Instant.now()
        );
    }

    private List<String> prefixAll(String prefix, List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : safeList(values)) {
            if (hasText(value)) {
                result.add(prefix + value.trim());
            }
        }
        return List.copyOf(result);
    }

    private boolean bool(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(defaultText(value, ""));
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private String defaultText(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? defaultValue : text;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private Map<String, Object> safeMap(Map<String, Object> values) {
        return values == null ? Map.of() : new LinkedHashMap<>(values);
    }
}

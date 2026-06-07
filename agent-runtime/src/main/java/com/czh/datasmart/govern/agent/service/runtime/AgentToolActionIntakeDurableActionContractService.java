/**
 * @Author : Cui
 * @Date: 2026/06/07 14:04
 * @Description DataSmart Govern Backend - AgentToolActionIntakeDurableActionContractService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionIntakeDecisionSummaryView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionIntakeDurableActionContractQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionIntakeDurableActionContractView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionIntakeProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionIntakeProjectionView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 从工具动作入口事件推导 durable action 契约预览的服务。
 *
 * <p>本服务刻意不写 outbox。原因是 `tool_action_intake_recorded` 是低敏控制面事件，不保存原始 MCP
 * `arguments`、prompt、SQL、样本数据或模型输出；直接用它创建 task-management command 会导致两类风险：
 * 一是命令缺少真实 payloadReference，worker 无法可靠执行；二是后续为了“凑齐执行参数”可能诱导控制面重新保存
 * 原始参数，破坏前面建立的低敏边界。</p>
 *
 * <p>因此当前阶段的正确产物是 contract preview：它告诉管理台、确认页或后续 LangGraph/OpenClaw-style
 * 执行图节点，某个工具动作要进入 durable action/outbox 前需要哪些证据，例如 payloadReference、审批事实、
 * policyVersion、幂等键、outbox command schema 和 worker receipt。这样我们把产品向可恢复执行推进了一步，
 * 但不会越过安全边界。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionIntakeDurableActionContractService {

    private static final String PAYLOAD_POLICY = "PAYLOAD_REFERENCE_ONLY_NO_RAW_ARGUMENTS";
    private static final String STATE_READY_CONTROLLED = "READY_FOR_DURABLE_ACTION_CONTRACT";
    private static final String STATE_READY_ASYNC = "READY_FOR_ASYNC_OUTBOX_CONTRACT";
    private static final String STATE_WAITING_APPROVAL = "WAITING_APPROVAL";
    private static final String STATE_NEEDS_CLARIFICATION = "NEEDS_CLARIFICATION";
    private static final String STATE_WAITING_BUDGET = "WAITING_TOOL_BUDGET";
    private static final String STATE_BLOCKED = "BLOCKED_BEFORE_EXECUTION";
    private static final String STATE_REJECTED = "REJECTED_BEFORE_READINESS";
    private static final String STATE_INTAKE_ONLY = "INTAKE_ONLY";

    private final AgentToolActionIntakeProjectionService projectionService;

    /**
     * 查询并推导工具动作入口 durable action 契约。
     *
     * @param query 与工具动作入口投影相同的查询条件，支持 run/session/request/tenant/project/actor/afterSequence。
     * @param accessContext 网关或认证层解析出的访问上下文，继续沿用投影服务的数据范围控制。
     * @return 低敏契约预览与窗口聚合。
     */
    public AgentToolActionIntakeDurableActionContractQueryResponse queryContracts(
            AgentRuntimeEventProjectionQuery query,
            AgentRuntimeEventQueryAccessContext accessContext) {
        AgentToolActionIntakeProjectionQueryResponse projections =
                projectionService.querySnapshots(query, accessContext);
        List<AgentToolActionIntakeDurableActionContractView> contracts = projections.snapshots().stream()
                .flatMap(snapshot -> contractsFor(snapshot).stream())
                .toList();
        return new AgentToolActionIntakeDurableActionContractQueryResponse(
                projections.limit(),
                projections.totalMatched(),
                contracts.size(),
                contracts.stream().filter(this::isReadyForDurableContract).count(),
                contracts.stream().filter(contract -> STATE_WAITING_APPROVAL.equals(contract.durableActionState())).count(),
                contracts.stream().filter(contract -> STATE_NEEDS_CLARIFICATION.equals(contract.durableActionState())).count(),
                contracts.stream().filter(this::isBlockedOrRejected).count(),
                contracts.stream().filter(contract -> Boolean.TRUE.equals(contract.outboxWritableNow())).count(),
                countBy(contracts, AgentToolActionIntakeDurableActionContractView::durableActionState),
                countBy(contracts, AgentToolActionIntakeDurableActionContractView::toolName),
                countMissingRequirements(contracts),
                summaryReasons(projections.totalMatched(), contracts),
                recommendedActions(contracts),
                contracts
        );
    }

    private List<AgentToolActionIntakeDurableActionContractView> contractsFor(
            AgentToolActionIntakeProjectionView snapshot) {
        List<AgentToolActionIntakeDecisionSummaryView> decisions = safeList(snapshot.decisionSummaries());
        if (decisions.isEmpty()) {
            return List.of(eventLevelContract(snapshot));
        }
        List<AgentToolActionIntakeDurableActionContractView> contracts = new ArrayList<>();
        for (int index = 0; index < decisions.size(); index++) {
            contracts.add(decisionContract(snapshot, decisions.get(index), index));
        }
        return List.copyOf(contracts);
    }

    private AgentToolActionIntakeDurableActionContractView decisionContract(
            AgentToolActionIntakeProjectionView snapshot,
            AgentToolActionIntakeDecisionSummaryView decision,
            int index) {
        String state = stateFromDecision(snapshot, decision);
        boolean outboxWritableNow = outboxWritableNow(snapshot, state);
        return new AgentToolActionIntakeDurableActionContractView(
                contractId(snapshot, safeText(decision.toolName()), safeText(decision.decision()), index),
                snapshot.identityKey(),
                snapshot.replaySequence(),
                snapshot.tenantId(),
                snapshot.projectId(),
                snapshot.actorId(),
                snapshot.requestId(),
                snapshot.runId(),
                snapshot.sessionId(),
                snapshot.createdAt(),
                snapshot.protocolFamily(),
                snapshot.intakeSource(),
                safeText(decision.toolName()),
                safeText(decision.decision()),
                state,
                decision.executable(),
                decision.queueRequired(),
                decision.requiresHumanApproval(),
                decision.parameterIssueCount(),
                safeList(decision.issueCodes()),
                safeList(decision.reasonCodes()),
                outboxWritableNow,
                commandType(state, decision),
                idempotencyKey(snapshot, safeText(decision.toolName()), index),
                Boolean.TRUE.equals(snapshot.graphWorkerReceiptRequiredForSideEffects()),
                requiredEvidence(decision),
                missingRequirements(snapshot, state, decision, outboxWritableNow),
                PAYLOAD_POLICY,
                guardrailNotes(state, outboxWritableNow)
        );
    }

    private AgentToolActionIntakeDurableActionContractView eventLevelContract(
            AgentToolActionIntakeProjectionView snapshot) {
        String state = stateFromSnapshot(snapshot);
        String toolName = firstOrDefault(snapshot.toolNames(), "UNKNOWN_TOOL");
        return new AgentToolActionIntakeDurableActionContractView(
                contractId(snapshot, toolName, state, 0),
                snapshot.identityKey(),
                snapshot.replaySequence(),
                snapshot.tenantId(),
                snapshot.projectId(),
                snapshot.actorId(),
                snapshot.requestId(),
                snapshot.runId(),
                snapshot.sessionId(),
                snapshot.createdAt(),
                snapshot.protocolFamily(),
                snapshot.intakeSource(),
                toolName,
                state.toLowerCase(Locale.ROOT),
                state,
                false,
                false,
                false,
                0,
                safeList(snapshot.issueCodes()),
                safeList(snapshot.readinessReasonCodes()),
                false,
                "NONE",
                idempotencyKey(snapshot, toolName, 0),
                Boolean.TRUE.equals(snapshot.graphWorkerReceiptRequiredForSideEffects()),
                requiredEvidence(null),
                missingRequirements(snapshot, state, null, false),
                PAYLOAD_POLICY,
                guardrailNotes(state, false)
        );
    }

    private String stateFromDecision(AgentToolActionIntakeProjectionView snapshot,
                                     AgentToolActionIntakeDecisionSummaryView decision) {
        String normalizedDecision = normalize(decision.decision());
        if (Boolean.TRUE.equals(decision.requiresHumanApproval()) || normalizedDecision.contains("approval")) {
            return STATE_WAITING_APPROVAL;
        }
        if (safeInt(decision.parameterIssueCount()) > 0 || normalizedDecision.contains("clarification")) {
            return STATE_NEEDS_CLARIFICATION;
        }
        if (normalizedDecision.contains("throttled") || safeInt(snapshot.readinessThrottledCount()) > 0) {
            return STATE_WAITING_BUDGET;
        }
        if (normalizedDecision.contains("blocked") || safeInt(snapshot.readinessBlockedCount()) > 0) {
            return STATE_BLOCKED;
        }
        if (Boolean.TRUE.equals(decision.queueRequired())) {
            return STATE_READY_ASYNC;
        }
        if (Boolean.TRUE.equals(decision.executable()) || normalizedDecision.contains("ready")) {
            return STATE_READY_CONTROLLED;
        }
        return STATE_INTAKE_ONLY;
    }

    private String stateFromSnapshot(AgentToolActionIntakeProjectionView snapshot) {
        if (safeInt(snapshot.rejectedBeforeReadinessCount()) > 0) {
            return STATE_REJECTED;
        }
        if (safeInt(snapshot.readinessBlockedCount()) > 0) {
            return STATE_BLOCKED;
        }
        if (safeInt(snapshot.readinessApprovalRequiredCount()) > 0) {
            return STATE_WAITING_APPROVAL;
        }
        if (safeInt(snapshot.readinessClarificationRequiredCount()) > 0) {
            return STATE_NEEDS_CLARIFICATION;
        }
        if (safeInt(snapshot.readinessThrottledCount()) > 0) {
            return STATE_WAITING_BUDGET;
        }
        if (safeInt(snapshot.readinessExecutableCount()) > 0) {
            return STATE_READY_CONTROLLED;
        }
        return STATE_INTAKE_ONLY;
    }

    private boolean outboxWritableNow(AgentToolActionIntakeProjectionView snapshot, String state) {
        return isReadyState(state)
                && Boolean.TRUE.equals(snapshot.productionReadyForExecution())
                && safeList(snapshot.missingProductionRequirements()).isEmpty();
    }

    private String commandType(String state, AgentToolActionIntakeDecisionSummaryView decision) {
        if (!isReadyState(state)) {
            return "NONE";
        }
        if (decision != null && Boolean.TRUE.equals(decision.queueRequired())) {
            return "AGENT_TOOL_ACTION_ASYNC_COMMAND";
        }
        return "AGENT_TOOL_ACTION_CONTROLLED_COMMAND";
    }

    private List<String> requiredEvidence(AgentToolActionIntakeDecisionSummaryView decision) {
        List<String> evidence = new ArrayList<>();
        evidence.add("SOURCE_RUNTIME_EVENT_REPLAY_SEQUENCE");
        evidence.add("LOW_SENSITIVE_TOOL_DECISION_SUMMARY");
        evidence.add("PAYLOAD_REFERENCE_NOT_RAW_ARGUMENTS");
        evidence.add("IDEMPOTENCY_KEY");
        evidence.add("OUTBOX_COMMAND_SCHEMA");
        evidence.add("WORKER_RECEIPT");
        if (decision != null && Boolean.TRUE.equals(decision.requiresHumanApproval())) {
            evidence.add("HUMAN_APPROVAL_CONFIRMATION_ID");
        }
        return List.copyOf(evidence);
    }

    private List<String> missingRequirements(AgentToolActionIntakeProjectionView snapshot,
                                             String state,
                                             AgentToolActionIntakeDecisionSummaryView decision,
                                             boolean outboxWritableNow) {
        Set<String> missing = new LinkedHashSet<>(safeList(snapshot.missingProductionRequirements()));
        if (!outboxWritableNow && isReadyState(state)) {
            missing.add("DURABLE_ACTION_COMMAND_NOT_CREATED");
            missing.add("PAYLOAD_REFERENCE_REQUIRED");
        }
        if (!Boolean.TRUE.equals(snapshot.graphOutboxWritten())) {
            missing.add("OUTBOX_RECORD_NOT_WRITTEN");
        }
        if (STATE_WAITING_APPROVAL.equals(state)) {
            missing.add("HUMAN_APPROVAL_FACT_REQUIRED");
        }
        if (STATE_NEEDS_CLARIFICATION.equals(state)) {
            missing.add("USER_CLARIFICATION_FACT_REQUIRED");
        }
        if (STATE_REJECTED.equals(state)) {
            missing.add("VISIBLE_TOOL_OR_PROTOCOL_ACCEPTANCE_REQUIRED");
        }
        if (decision != null && safeInt(decision.parameterIssueCount()) > 0) {
            missing.add("VALID_TOOL_ARGUMENT_SHAPE_REQUIRED");
        }
        return List.copyOf(missing);
    }

    private List<String> guardrailNotes(String state, boolean outboxWritableNow) {
        List<String> notes = new ArrayList<>();
        notes.add("该契约来自低敏 runtime event，只能作为控制面预览，不能替代真实 outbox command。");
        notes.add("真实执行必须使用 payloadReference 读取受控参数，不能从本契约恢复原始 arguments。");
        if (!outboxWritableNow) {
            notes.add("当前不具备立即写 outbox 条件，应先补齐缺失证据并重新经过权限、审批、幂等和容量检查。");
        }
        if (STATE_REJECTED.equals(state) || STATE_BLOCKED.equals(state)) {
            notes.add("被拒绝或阻断的工具动作不能进入执行器，必须回到协议、工具可见性或策略配置层修复。");
        }
        return List.copyOf(notes);
    }

    private List<String> summaryReasons(int sourceSnapshotCount,
                                        List<AgentToolActionIntakeDurableActionContractView> contracts) {
        List<String> reasons = new ArrayList<>();
        reasons.add("本次从 " + sourceSnapshotCount + " 条工具动作入口事件推导出 "
                + contracts.size() + " 条 durable action 契约预览。");
        reasons.add("契约预览不会写 outbox，也不会携带工具参数；它只说明进入可恢复执行前缺少哪些证据。");
        if (contracts.stream().anyMatch(contract -> Boolean.TRUE.equals(contract.outboxWritableNow()))) {
            reasons.add("窗口内存在已满足最低入箱条件的契约，后续仍需由专用确认/入箱 API 写入 outbox。");
        } else {
            reasons.add("窗口内没有契约满足立即写 outbox 条件，符合当前 preview-only MCP intake 阶段预期。");
        }
        return List.copyOf(reasons);
    }

    private List<String> recommendedActions(List<AgentToolActionIntakeDurableActionContractView> contracts) {
        List<String> actions = new ArrayList<>();
        actions.add("优先设计确认页或 execution graph 节点，把 contractId、sourceReplaySequence、payloadReference、policyVersion 和 confirmationId 汇总后再写 outbox。");
        actions.add("不要让 MCP tools/call preview 事件直接创建 task-management command；必须先补齐 payloadReference、幂等键和 worker receipt 策略。");
        if (contracts.stream().anyMatch(contract -> STATE_WAITING_APPROVAL.equals(contract.durableActionState()))) {
            actions.add("存在等待审批契约，下一步应接入 permission-admin 或前端确认页返回 HUMAN_APPROVAL_CONFIRMATION_ID。");
        }
        if (contracts.stream().anyMatch(contract -> STATE_REJECTED.equals(contract.durableActionState()))) {
            actions.add("存在 readiness 前拒绝契约，应检查工具注册名、可见工具集合、协议 method 和 JSON 参数形态。");
        }
        return List.copyOf(actions);
    }

    private boolean isReadyForDurableContract(AgentToolActionIntakeDurableActionContractView contract) {
        return isReadyState(contract.durableActionState());
    }

    private boolean isBlockedOrRejected(AgentToolActionIntakeDurableActionContractView contract) {
        return STATE_BLOCKED.equals(contract.durableActionState())
                || STATE_REJECTED.equals(contract.durableActionState());
    }

    private boolean isReadyState(String state) {
        return STATE_READY_CONTROLLED.equals(state) || STATE_READY_ASYNC.equals(state);
    }

    private Map<String, Long> countMissingRequirements(List<AgentToolActionIntakeDurableActionContractView> contracts) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AgentToolActionIntakeDurableActionContractView contract : contracts) {
            for (String requirement : safeList(contract.missingRequirements())) {
                mergeCount(counts, requirement);
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    private Map<String, Long> countBy(List<AgentToolActionIntakeDurableActionContractView> contracts,
                                      java.util.function.Function<AgentToolActionIntakeDurableActionContractView, String> mapper) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AgentToolActionIntakeDurableActionContractView contract : contracts) {
            mergeCount(counts, mapper.apply(contract));
        }
        return Collections.unmodifiableMap(counts);
    }

    private void mergeCount(Map<String, Long> counts, String key) {
        String normalized = safeText(key);
        if (normalized != null) {
            counts.merge(normalized, 1L, Long::sum);
        }
    }

    private String contractId(AgentToolActionIntakeProjectionView snapshot,
                              String toolName,
                              String decision,
                              int index) {
        return "tool-action-contract:" + hash(snapshot.identityKey(), snapshot.replaySequence(), toolName, decision, index);
    }

    private String idempotencyKey(AgentToolActionIntakeProjectionView snapshot, String toolName, int index) {
        return "tool-action-intake:" + hash(snapshot.identityKey(), snapshot.replaySequence(), toolName, index);
    }

    private String hash(Object... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object part : parts) {
                digest.update(Objects.toString(part, "").getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            byte[] bytes = digest.digest();
            StringBuilder builder = new StringBuilder(32);
            for (int index = 0; index < 16; index++) {
                builder.append(String.format("%02x", bytes[index]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256，无法生成工具动作入口契约 ID", exception);
        }
    }

    private String firstOrDefault(List<String> values, String defaultValue) {
        return safeList(values).stream()
                .filter(value -> safeText(value) != null)
                .findFirst()
                .orElse(defaultValue);
    }

    private String safeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}

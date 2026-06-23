/**
 * @Author : Cui
 * @Date: 2026/06/04 00:00
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandPreCheckService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolExecutionPolicyItemView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolExecutionPolicyView;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxRecord;
import com.czh.datasmart.govern.agent.service.execution.confirmation.AgentRunToolDagConfirmationRecord;
import com.czh.datasmart.govern.agent.service.execution.confirmation.AgentRunToolDagConfirmationStatus;
import com.czh.datasmart.govern.agent.service.execution.confirmation.AgentRunToolDagConfirmationStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Agent 异步命令执行前复核服务。
 *
 * <p>该服务是“真实 DAG worker”上线前必须先固定的控制面契约。selected-node confirmation 与 command outbox
 * 已经能证明用户/策略在某一刻确认过一批异步节点，但真实 worker 领取 command 的时间可能晚于确认时间：
 * permission-admin 策略可能已经变更、确认可能过期、工具审计状态可能被其他流程推进、运行时容量可能已经打满，
 * 或目标服务可能进入熔断。因此 worker 不能只因为 command 已经 PUBLISHED/PENDING 就直接执行副作用。</p>
 *
 * <p>本服务当前只做“只读 verdict”，不修改 outbox、不推进审计状态、不申请 runtime lease，也不投递消息。
 * 这样做的好处是：
 * 1. 未来 task-management worker、agent-runtime worker、HTTP 诊断接口和补偿入口可以复用同一套判断；
 * 2. 单测可以先固定语义，避免真实 worker 上线后再返工；
 * 3. 容量类问题可以被标记为 DEFERRED，后续 worker 可按退避重试，而不是误判为永久失败。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentAsyncTaskCommandPreCheckService {

    private static final String ALLOW_EXECUTION = "ALLOW_EXECUTION";
    private static final String BLOCKED = "BLOCKED";
    private static final String DEFERRED = "DEFERRED";

    private final AgentRunToolExecutionPolicyService policyService;
    private final AgentRunToolDagConfirmationStore confirmationStore;
    private final AgentCommandSafetyPrecheckEvidenceEvaluator commandSafetyPrecheckEvidenceEvaluator;
    private final ObjectMapper objectMapper;

    /**
     * 对一条异步 command outbox 记录做执行前复核。
     *
     * @param record outbox 记录；通常由 dispatcher、future worker 或诊断接口传入。
     * @return 低敏、可展示、可测试的准入 verdict。
     */
    public AgentAsyncTaskCommandPreCheckVerdict inspect(AgentAsyncTaskCommandOutboxRecord record) {
        if (record == null) {
            return blocked(null, null, null, null, null,
                    List.of("COMMAND_RECORD_MISSING"),
                    List.of("异步命令记录为空，worker 无法定位 session/run/audit，也无法执行任何副作用。"),
                    List.of("请检查 dispatcher 或诊断入口是否传入了有效 outbox record。"));
        }

        Map<String, Object> payload = parsePayload(record.payloadJson());
        String confirmationId = text(payload.get("confirmationId"));
        List<String> payloadPolicyVersions = stringList(payload.get("policyVersions"));
        Optional<AgentRunToolDagConfirmationRecord> confirmation = findConfirmation(confirmationId);
        AgentRunToolExecutionPolicyItemView policy = findPolicyItem(record);

        List<String> issueCodes = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        evaluateConfirmation(record, confirmationId, confirmation, issueCodes, reasons, actions);
        evaluatePolicyVersion(payloadPolicyVersions, confirmation, issueCodes, reasons, actions);
        evaluateCurrentPolicy(policy, issueCodes, reasons, actions);
        evaluateCommandSafetyPrecheckEvidence(payload, issueCodes, reasons, actions);

        String decision = decision(issueCodes);
        if (issueCodes.isEmpty()) {
            reasons.add("异步命令执行前复核通过：确认记录有效，当前策略仍允许异步执行，沙箱与运行时保护均未阻断。");
            actions.add("worker 可以继续进入受控工具执行入口；真实执行前仍应申请 runtime lease，并在 finally 中释放。");
        }
        return new AgentAsyncTaskCommandPreCheckVerdict(
                record.commandId(),
                record.auditId(),
                confirmationId,
                ALLOW_EXECUTION.equals(decision),
                decision,
                policy == null ? null : policy.decision(),
                policy == null ? null : policy.sandboxAllowed(),
                policy == null ? null : policy.runtimeProtectionAllowed(),
                confirmation.map(item -> item.status().name()).orElse(null),
                confirmation.map(AgentRunToolDagConfirmationRecord::expiresAt).orElse(null),
                issueCodes,
                reasons,
                actions
        );
    }

    private Optional<AgentRunToolDagConfirmationRecord> findConfirmation(String confirmationId) {
        if (!hasText(confirmationId)) {
            return Optional.empty();
        }
        return confirmationStore.findByConfirmationId(confirmationId);
    }

    private AgentRunToolExecutionPolicyItemView findPolicyItem(AgentAsyncTaskCommandOutboxRecord record) {
        try {
            AgentRunToolExecutionPolicyView policy = policyService.inspectRunPolicy(record.sessionId(), record.runId());
            return policy.items().stream()
                    .filter(item -> Objects.equals(item.auditId(), record.auditId()))
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException exception) {
            /*
             * 策略中心不可用时不能静默放行。这里不直接抛异常，是为了让 worker 可以把 verdict 写成
             * DEFERRED 事件，再按退避策略重试；否则异常会散落在不同 worker 中，难以形成统一观测口径。
             */
            return null;
        }
    }

    private void evaluateConfirmation(AgentAsyncTaskCommandOutboxRecord record,
                                      String confirmationId,
                                      Optional<AgentRunToolDagConfirmationRecord> confirmation,
                                      List<String> issueCodes,
                                      List<String> reasons,
                                      List<String> actions) {
        if (!hasText(confirmationId)) {
            add(issueCodes, reasons, actions,
                    "CONFIRMATION_ID_MISSING",
                    "异步命令 payload 中缺少 confirmationId，无法证明该 command 来自 selected-node 确认链路。",
                    "请重新从 DAG dry-run -> selected-node confirmation 入口入箱；历史 Run 级命令应走兼容迁移或人工复核。");
            return;
        }
        if (confirmation.isEmpty()) {
            add(issueCodes, reasons, actions,
                    "CONFIRMATION_NOT_FOUND",
                    "未找到 confirmationId=" + confirmationId + " 对应的确认记录，不能执行真实副作用。",
                    "请检查 confirmation store、MySQL 持久化配置和 outbox/confirmation 是否处于同一事务边界。");
            return;
        }
        AgentRunToolDagConfirmationRecord recordConfirmation = confirmation.get();
        if (!AgentRunToolDagConfirmationStatus.CONFIRMED.equals(recordConfirmation.status())
                || !Boolean.TRUE.equals(recordConfirmation.confirmed())) {
            add(issueCodes, reasons, actions,
                    "CONFIRMATION_NOT_CONFIRMED",
                    "确认记录当前状态不是 CONFIRMED，worker 不能把它当成有效执行证据。",
                    "请重新执行 selected-node confirmation，或由管理员检查确认记录状态机。");
        }
        if (recordConfirmation.expiresAt() != null && recordConfirmation.expiresAt().isBefore(Instant.now())) {
            add(issueCodes, reasons, actions,
                    "CONFIRMATION_EXPIRED",
                    "确认记录已过期，说明用户看到的 dry-run 预案可能已经不再代表当前执行条件。",
                    "请重新执行 dry-run 和 selected-node confirmation，生成新的 confirmationId。");
        }
        if (!recordConfirmation.selectedAuditIds().contains(record.auditId())) {
            add(issueCodes, reasons, actions,
                    "AUDIT_ID_NOT_IN_CONFIRMATION",
                    "当前 command 的 auditId 不在确认记录选中列表中，存在跨节点或过期命令风险。",
                    "请核对 commandId、auditId、confirmationId 是否来自同一次 DAG selected-node 入箱。");
        }
        if (!recordConfirmation.commandIds().isEmpty() && !recordConfirmation.commandIds().contains(record.commandId())) {
            add(issueCodes, reasons, actions,
                    "COMMAND_ID_NOT_IN_CONFIRMATION",
                    "当前 commandId 不在确认记录关联的 commandIds 中，不能证明该命令由确认动作生成。",
                    "请检查 outbox 幂等键、confirmation 写入和网关重试是否产生了不一致事实。");
        }
    }

    private void evaluatePolicyVersion(List<String> payloadPolicyVersions,
                                       Optional<AgentRunToolDagConfirmationRecord> confirmation,
                                       List<String> issueCodes,
                                       List<String> reasons,
                                       List<String> actions) {
        if (confirmation.isEmpty()) {
            return;
        }
        List<String> confirmationVersions = confirmation.get().policyVersions();
        if (confirmationVersions.isEmpty()) {
            return;
        }
        Set<String> payloadSet = new LinkedHashSet<>(payloadPolicyVersions);
        for (String version : confirmationVersions) {
            if (!payloadSet.contains(version)) {
                add(issueCodes, reasons, actions,
                        "POLICY_VERSION_EVIDENCE_MISMATCH",
                        "command payload 中的 policyVersions 与 confirmation 记录不一致，说明执行证据可能被截断、篡改或来自旧版本。",
                        "请重新入箱生成 command payload，并检查 outbox 序列化和消息投递链路。");
                return;
            }
        }
    }

    private void evaluateCurrentPolicy(AgentRunToolExecutionPolicyItemView policy,
                                       List<String> issueCodes,
                                       List<String> reasons,
                                       List<String> actions) {
        if (policy == null) {
            add(issueCodes, reasons, actions,
                    "CURRENT_POLICY_ITEM_MISSING",
                    "执行前未能读取当前 Run 级策略项，不能确认工具仍处于可异步执行状态。",
                    "请等待 agent-runtime 策略服务恢复后重试；若持续失败，请检查 session/run/audit 是否仍存在。");
            return;
        }
        if (!AgentRunToolExecutionDecision.WAITING_ASYNC_EXECUTOR.name().equals(policy.decision())) {
            add(issueCodes, reasons, actions,
                    "CURRENT_POLICY_NOT_ASYNC_EXECUTABLE",
                    "当前 policyDecision=" + policy.decision() + "，不再是 WAITING_ASYNC_EXECUTOR。",
                    "请重新 dry-run/confirmation，或按当前策略处理审批、参数、依赖和状态变化。");
        }
        if (Boolean.FALSE.equals(policy.sandboxAllowed())) {
            add(issueCodes, reasons, actions,
                    "SANDBOX_REJECTED_BEFORE_WORKER",
                    "当前沙箱 verdict 已拒绝该工具，worker 不能继续执行真实副作用。",
                    "请处理 sandboxIssueCodes=" + policy.sandboxIssueCodes() + " 后重新生成执行证据。");
        }
        if (Boolean.FALSE.equals(policy.runtimeProtectionAllowed())) {
            add(issueCodes, reasons, actions,
                    "RUNTIME_PROTECTION_DEFERRED_BEFORE_WORKER",
                    "当前运行时保护判定容量或目标服务健康不适合继续执行。",
                    "请按 runtimeProtectionIssueCodes=" + policy.runtimeProtectionIssueCodes() + " 进行退避、拆批或排查熔断。");
        }
    }

    /**
     * 复核 command payload 中的命令安全预检证据。
     *
     * <p>这一步是 5.92 safety-precheck 与 worker pre-check 的衔接点。它不要求所有历史 command 都携带
     * commandSafetyPrecheck，因为现有 outbox 里还有数据同步、元数据读取等非 run-program 类命令；但只要 payload
     * 显式声明 `commandSafetyPrecheckRequired=true` 或 evidence.required=true，就必须提供低敏、可验证的 allow verdict。</p>
     *
     * <p>为什么这里不直接读取 commandLine：worker 领取的是已经入箱的 durable command，payload 必须保持低敏信封。
     * 原始命令文本只能在预检阶段短暂进入服务端判定，不能被复制到 outbox、runtime event、指标或诊断响应里。</p>
     */
    private void evaluateCommandSafetyPrecheckEvidence(Map<String, Object> payload,
                                                       List<String> issueCodes,
                                                       List<String> reasons,
                                                       List<String> actions) {
        AgentCommandSafetyPrecheckEvidenceEvaluator.AgentCommandSafetyPrecheckEvidenceResult result =
                commandSafetyPrecheckEvidenceEvaluator.evaluate(payload);
        if (Boolean.TRUE.equals(result.passed())) {
            reasons.addAll(result.reasons());
            actions.addAll(result.recommendedActions());
            return;
        }
        issueCodes.addAll(result.issueCodes());
        reasons.addAll(result.reasons());
        actions.addAll(result.recommendedActions());
    }

    private String decision(List<String> issueCodes) {
        if (issueCodes.isEmpty()) {
            return ALLOW_EXECUTION;
        }
        if (issueCodes.contains("RUNTIME_PROTECTION_DEFERRED_BEFORE_WORKER")
                || issueCodes.contains("CURRENT_POLICY_ITEM_MISSING")) {
            return DEFERRED;
        }
        return BLOCKED;
    }

    private AgentAsyncTaskCommandPreCheckVerdict blocked(String commandId,
                                                         String auditId,
                                                         String confirmationId,
                                                         String policyDecision,
                                                         String confirmationStatus,
                                                         List<String> issueCodes,
                                                         List<String> reasons,
                                                         List<String> actions) {
        return new AgentAsyncTaskCommandPreCheckVerdict(
                commandId,
                auditId,
                confirmationId,
                false,
                BLOCKED,
                policyDecision,
                null,
                null,
                confirmationStatus,
                null,
                issueCodes,
                reasons,
                actions
        );
    }

    private Map<String, Object> parsePayload(String payloadJson) {
        if (!hasText(payloadJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<>() { });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .filter(this::hasText)
                    .map(String::trim)
                    .distinct()
                    .toList();
        }
        if (hasText(text(value))) {
            return List.of(text(value));
        }
        return List.of();
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private void add(List<String> issueCodes,
                     List<String> reasons,
                     List<String> actions,
                     String issueCode,
                     String reason,
                     String action) {
        issueCodes.add(issueCode);
        reasons.add(reason);
        actions.add(action);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

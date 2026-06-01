/**
 * @Author : Cui
 * @Date: 2026/06/01 21:18
 * @Description DataSmart Govern Backend - AgentAsyncToolExecutionPreCheckService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.support.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Agent 异步工具 worker 执行前二次复核服务。
 *
 * <p>本服务位于 task-management worker 内部，调用时机是：
 * `claimNextTask` 已经把任务置为 RUNNING、`payloadResolver` 已经从 agent-runtime 回读参数快照，
 * 但 worker 还没有向 agent-runtime 回写 RUNNING，也没有调用任何真实工具适配器。
 * 这是副作用发生前最适合做 fail-closed 的位置。</p>
 *
 * <p>当前阶段已经从“只校验本地字段”推进到“回查 agent-runtime confirmation”。
 * 这一步是为了让 task-management 不只相信 Kafka command 或 task.params，而是在真实工具执行前重新确认：
 * 当前 commandId、auditId、policyVersions 是否确实来自 agent-runtime 记录过的 selected-node 确认事实。
 * 后续 permission-admin 实时 evaluate、租户配额、工具限流和熔断会继续挂到同一个 pre-check 链路上。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentAsyncToolExecutionPreCheckService {

    /**
     * 当前允许进入 worker 执行的 Agent 审计状态。
     *
     * <p>PLANNED 表示工具已经确认入箱但尚未执行，是首次执行的正常状态。
     * EXECUTING 表示上一次 worker 已经成功回写 RUNNING，但后续因为下游限流、回写失败或任务退避进入补偿路径；
     * 为了支持幂等重试和恢复，允许它再次进入适配器，但真实下游必须继续使用 commandId/idempotencyKey 防重。</p>
     */
    private static final Set<String> EXECUTABLE_AUDIT_STATES = Set.of("PLANNED", "EXECUTING");

    private static final String PAYLOAD_REFERENCE_PREFIX = "agent-tool-audit://";
    private static final String PAYLOAD_KIND_PLAN_ARGUMENTS = "plan-arguments";
    private static final int MAX_EVIDENCE_ITEMS = 20;
    private static final int MAX_EVIDENCE_LENGTH = 512;

    private final List<AgentAsyncToolExecutor> executors;
    private final AgentAsyncToolWorkerProperties properties;
    private final AgentRuntimeToolDagConfirmationClient confirmationClient;

    /**
     * 执行 worker 前置复核。
     *
     * @param payload 已经完成 payloadReference 一致性校验的参数快照
     * @return 允许或阻断结果；调用方必须在 allowed=false 时停止真实工具执行
     */
    public AgentAsyncToolExecutionPreCheckResult preCheck(AgentAsyncToolResolvedPayload payload) {
        List<String> messages = new ArrayList<>();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        if (payload == null) {
            return rejected("Agent 异步工具载荷为空，worker 不能进入真实执行。", messages, diagnostics);
        }

        diagnostics.put("taskId", payload.taskId());
        diagnostics.put("commandId", payload.commandId());
        diagnostics.put("auditId", payload.auditId());
        diagnostics.put("toolCode", payload.toolCode());

        String structuralIssue = validateStructuralFields(payload, messages);
        if (structuralIssue != null) {
            return rejected(structuralIssue, messages, diagnostics);
        }

        String stateIssue = validateStateFields(payload, messages, diagnostics);
        if (stateIssue != null) {
            return rejected(stateIssue, messages, diagnostics);
        }

        String executorIssue = validateExecutorWhitelist(payload, messages, diagnostics);
        if (executorIssue != null) {
            return rejected(executorIssue, messages, diagnostics);
        }

        String evidenceIssue = validateExecutionEvidence(payload, messages, diagnostics);
        if (evidenceIssue != null) {
            return rejected(evidenceIssue, messages, diagnostics);
        }

        messages.add("worker 二次复核通过：任务状态、Agent 审计状态、payloadReference、工具白名单和执行证据均满足当前阶段规则。");
        return AgentAsyncToolExecutionPreCheckResult.allowed(messages);
    }

    private String validateStructuralFields(AgentAsyncToolResolvedPayload payload, List<String> messages) {
        if (!AgentAsyncToolPayloadResolver.TASK_TYPE.equals(payload.taskType())) {
            return "worker 只允许执行 AGENT_ASYNC_TOOL 任务，当前 taskType=" + payload.taskType();
        }
        if (payload.commandId() == null || payload.commandId().isBlank()) {
            return "worker 执行前 commandId 不能为空。";
        }
        if (payload.payloadReference() == null || !payload.payloadReference().startsWith(PAYLOAD_REFERENCE_PREFIX)) {
            return "worker 执行前 payloadReference 必须使用受控 agent-tool-audit:// 协议。";
        }
        if (!PAYLOAD_KIND_PLAN_ARGUMENTS.equals(payload.payloadKind())) {
            return "worker 当前只允许执行 plan-arguments 载荷，payloadKind=" + payload.payloadKind();
        }
        if (Boolean.TRUE.equals(payload.dryRunOnly())) {
            return "worker 当前解析结果仍标记 dryRunOnly=true，不能进入真实执行。";
        }
        messages.add("结构复核通过：任务类型、commandId、payloadReference 协议和 payloadKind 均符合 worker 执行入口要求。");
        return null;
    }

    private String validateStateFields(AgentAsyncToolResolvedPayload payload,
                                       List<String> messages,
                                       Map<String, Object> diagnostics) {
        diagnostics.put("taskStatus", payload.taskStatus());
        diagnostics.put("auditState", payload.auditState());
        if (!TaskStatus.RUNNING.equals(payload.taskStatus())) {
            return "worker 只能执行已经被认领为 RUNNING 的任务，当前 taskStatus=" + payload.taskStatus();
        }
        String auditState = normalize(payload.auditState());
        if (!EXECUTABLE_AUDIT_STATES.contains(auditState)) {
            return "Agent 工具审计状态不允许进入 worker 执行，auditState=" + payload.auditState()
                    + "，允许状态=" + EXECUTABLE_AUDIT_STATES;
        }
        messages.add("状态复核通过：task-management 任务处于 RUNNING，Agent 审计状态允许进入执行或补偿。");
        return null;
    }

    private String validateExecutorWhitelist(AgentAsyncToolResolvedPayload payload,
                                             List<String> messages,
                                             Map<String, Object> diagnostics) {
        boolean supported = executors != null && executors.stream()
                .anyMatch(executor -> executor.supports(payload.toolCode()));
        diagnostics.put("executorSupported", supported);
        if (!supported) {
            return "没有找到支持 toolCode=" + payload.toolCode() + " 的 Agent 异步工具白名单适配器。";
        }
        messages.add("工具白名单复核通过：toolCode 已被显式 Java 适配器支持，不会按 targetEndpoint 任意转发。");
        return null;
    }

    private String validateExecutionEvidence(AgentAsyncToolResolvedPayload payload,
                                             List<String> messages,
                                             Map<String, Object> diagnostics) {
        String confirmationId = optionalText(payload.confirmationId());
        diagnostics.put("hasConfirmationId", confirmationId != null);
        diagnostics.put("policyVersionCount", payload.policyVersions().size());
        diagnostics.put("delegationEvidenceCount", payload.delegationEvidence().size());
        if (confirmationId == null) {
            messages.add("执行证据复核提示：当前任务未携带 confirmationId，兼容历史 Run 级入口；生产推荐走 selected-node confirmation。");
        } else if (!confirmationId.startsWith("dag-confirmation:")) {
            return "confirmationId 不符合 DAG selected-node 确认格式，confirmationId=" + confirmationId;
        } else {
            messages.add("执行证据复核通过：confirmationId 使用 DAG selected-node 确认格式。");
        }

        String policyIssue = validateEvidenceList("policyVersions", payload.policyVersions());
        if (policyIssue != null) {
            return policyIssue;
        }
        String delegationIssue = validateEvidenceList("delegationEvidence", payload.delegationEvidence());
        if (delegationIssue != null) {
            return delegationIssue;
        }
        messages.add("执行证据摘要复核通过：policyVersions 与 delegationEvidence 符合低敏短文本规则。");
        String remoteConfirmationIssue = validateRemoteConfirmation(payload, messages, diagnostics);
        if (remoteConfirmationIssue != null) {
            return remoteConfirmationIssue;
        }
        return null;
    }

    /**
     * 回查 agent-runtime 的原始 DAG selected-node 确认记录。
     *
     * <p>本地 task.params 中的 confirmationId、policyVersions 和 delegationEvidence 是 Kafka command 入箱时写入的快照。
     * 快照本身有价值，但仍然可能因为消息重放、人工修复、错误写入或未来跨服务兼容问题而与 agent-runtime 的确认事实不一致。
     * 因此这里用 confirmationId 回到 agent-runtime 查询低敏确认视图，并做五类一致性判断：</p>
     *
     * <p>1. confirmationId/sessionId/runId 必须和当前任务一致，防止跨会话或跨 Run 复用确认；</p>
     * <p>2. 确认状态必须是 CONFIRMED，避免未来撤销、拒绝或过期记录继续执行；</p>
     * <p>3. selectedAuditIds 必须包含当前 auditId，证明这个工具审计节点属于被确认的 DAG 节点集合；</p>
     * <p>4. commandIds 必须包含当前 commandId，证明当前任务来自确认动作产生的 command outbox；</p>
     * <p>5. 任务携带的 policyVersions/delegationEvidence 必须被确认记录覆盖，避免执行证据在 task-management 侧被放大或篡改。</p>
     *
     * <p>依赖不可用的处理也很关键：如果 agent-runtime 暂时不可达，worker 不应继续执行副作用；
     * 但这类问题通常可恢复，所以方法会抛出 {@link AgentAsyncToolPreCheckUnavailableException}，
     * 由调度层把任务 defer 回队列，而不是直接把任务永久失败。</p>
     */
    private String validateRemoteConfirmation(AgentAsyncToolResolvedPayload payload,
                                              List<String> messages,
                                              Map<String, Object> diagnostics) {
        String confirmationId = optionalText(payload.confirmationId());
        if (confirmationId == null) {
            diagnostics.put("remoteConfirmationCheck", "skipped_without_confirmation_id");
            return null;
        }
        if (!properties.isConfirmationCheckEnabled()) {
            diagnostics.put("remoteConfirmationCheck", "disabled_by_config");
            messages.add("agent-runtime 确认回查已被配置关闭：当前仅使用 task.params 中的本地执行证据，生产环境建议开启。");
            return null;
        }
        try {
            AgentRuntimeToolDagConfirmationView confirmation = confirmationClient.fetchConfirmation(payload);
            diagnostics.put("remoteConfirmationCheck", "queried");
            diagnostics.put("remoteConfirmationStatus", confirmation.status());
            diagnostics.put("remoteSelectedAuditCount", confirmation.selectedAuditIds().size());
            diagnostics.put("remoteCommandCount", confirmation.commandIds().size());
            diagnostics.put("remotePolicyVersionCount", confirmation.policyVersions().size());
            if (!sameText(confirmationId, confirmation.confirmationId())) {
                return "agent-runtime 确认记录与当前任务 confirmationId 不一致，拒绝执行。";
            }
            if (!sameText(payload.sessionId(), confirmation.sessionId()) || !sameText(payload.runId(), confirmation.runId())) {
                return "agent-runtime 确认记录与当前任务 sessionId/runId 不一致，拒绝跨上下文执行。";
            }
            if (!Boolean.TRUE.equals(confirmation.confirmed()) || !"CONFIRMED".equals(normalize(confirmation.status()))) {
                return "agent-runtime 确认记录状态不是 CONFIRMED，拒绝执行，status=" + confirmation.status();
            }
            if (!containsNormalized(confirmation.selectedAuditIds(), payload.auditId())) {
                return "agent-runtime 确认记录未包含当前 auditId，拒绝执行，auditId=" + payload.auditId();
            }
            if (!containsNormalized(confirmation.commandIds(), payload.commandId())) {
                return "agent-runtime 确认记录未包含当前 commandId，拒绝执行，commandId=" + payload.commandId();
            }
            String policyCoverageIssue = validateRemoteEvidenceCoverage(
                    "policyVersions", payload.policyVersions(), confirmation.policyVersions());
            if (policyCoverageIssue != null) {
                return policyCoverageIssue;
            }
            String delegationCoverageIssue = validateRemoteEvidenceCoverage(
                    "delegationEvidence", payload.delegationEvidence(), confirmation.delegationEvidence());
            if (delegationCoverageIssue != null) {
                return delegationCoverageIssue;
            }
            messages.add("agent-runtime 确认回查通过：confirmationId、sessionId/runId、auditId、commandId 与低敏证据均和确认记录一致。");
            return null;
        } catch (RuntimeException exception) {
            diagnostics.put("remoteConfirmationCheck", "temporarily_unavailable");
            diagnostics.put("remoteConfirmationCheckError", safeExceptionMessage(exception));
            if (properties.isConfirmationCheckFailOpenOnError()) {
                messages.add("agent-runtime 确认回查失败但配置为 fail-open：worker 将继续依赖本地证据执行，生产环境应谨慎使用。");
                return null;
            }
            throw new AgentAsyncToolPreCheckUnavailableException(
                    "agent-runtime confirmation 回查暂时不可用，worker 已阻止副作用并等待重试。", exception);
        }
    }

    private String validateRemoteEvidenceCoverage(String fieldName, List<String> taskValues, List<String> confirmationValues) {
        Set<String> taskSet = normalizedTextSet(taskValues);
        if (taskSet.isEmpty()) {
            return null;
        }
        Set<String> confirmationSet = normalizedTextSet(confirmationValues);
        if (!confirmationSet.containsAll(taskSet)) {
            return "agent-runtime 确认记录未覆盖当前任务携带的 " + fieldName + "，拒绝执行。";
        }
        return null;
    }

    private String validateEvidenceList(String fieldName, List<String> values) {
        List<String> normalized = values == null ? List.of() : values;
        if (normalized.size() > MAX_EVIDENCE_ITEMS) {
            return fieldName + " 数量不能超过 " + MAX_EVIDENCE_ITEMS;
        }
        Set<String> deduplicated = new LinkedHashSet<>();
        for (String value : normalized) {
            String item = optionalText(value);
            if (item == null) {
                continue;
            }
            if (item.length() > MAX_EVIDENCE_LENGTH) {
                return fieldName + " 单项长度不能超过 " + MAX_EVIDENCE_LENGTH;
            }
            if (looksLikeSensitivePayload(item)) {
                return fieldName + " 只能保存低敏审计摘要，不能包含 SQL、prompt、token 或密码片段。";
            }
            deduplicated.add(item);
        }
        return null;
    }

    private AgentAsyncToolExecutionPreCheckResult rejected(String message,
                                                           List<String> messages,
                                                           Map<String, Object> diagnostics) {
        messages.add("worker 二次复核阻断：" + message);
        return AgentAsyncToolExecutionPreCheckResult.rejected(
                "AGENT_ASYNC_TOOL_PRECHECK_REJECTED",
                message,
                messages,
                diagnostics
        );
    }

    private boolean looksLikeSensitivePayload(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("select ")
                || lower.contains("insert ")
                || lower.contains("authorization:")
                || lower.contains("bearer ")
                || lower.contains("password")
                || lower.contains("prompt:");
    }

    private boolean sameText(String left, String right) {
        String normalizedLeft = optionalText(left);
        String normalizedRight = optionalText(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }

    private boolean containsNormalized(List<String> values, String expected) {
        String normalizedExpected = optionalText(expected);
        return normalizedExpected != null && normalizedTextSet(values).contains(normalizedExpected);
    }

    private Set<String> normalizedTextSet(List<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            String item = optionalText(value);
            if (item != null) {
                normalized.add(item);
            }
        }
        return normalized;
    }

    private String safeExceptionMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        String trimmed = message.trim();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300);
    }

    private String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}

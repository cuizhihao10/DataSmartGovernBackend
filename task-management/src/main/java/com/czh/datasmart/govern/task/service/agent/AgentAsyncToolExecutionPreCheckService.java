/**
 * @Author : Cui
 * @Date: 2026/06/01 21:18
 * @Description DataSmart Govern Backend - AgentAsyncToolExecutionPreCheckService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

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
 * <p>当前阶段先落地本地可验证的二次复核骨架，不直接引入远端 permission-admin 查询。
 * 这样做是为了保持批次可控：先把任务状态、工具白名单、审计状态、payloadReference 和执行证据这些
 * “本地已经掌握的事实”拦住；下一阶段再把 confirmation 反查、权限策略实时 evaluate、租户配额和工具限流接进来。</p>
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

    private String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}

/**
 * @Author : Cui
 * @Date: 2026/06/11 22:20
 * @Description DataSmart Govern Backend - AgentToolActionControlledDryRunReceiptEventDisplayBuilder.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventDisplayView;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 受控工具动作 dry-run receipt 的 timeline 展示解释器。
 *
 * <p>runtime event projection 是机器事实层，字段偏治理和审计；本类把这些低敏字段翻译成前端、
 * 运维台和智能网关更容易理解的展示语义。它不会读取工具参数，也不会根据 receipt 自动推进执行；
 * 只解释“当前工具动作被 dry-run 调度器处理到了哪一步”。</p>
 */
final class AgentToolActionControlledDryRunReceiptEventDisplayBuilder {

    private static final String REPLAY_POLICY_APPEND_AND_ACK = "APPEND_TO_TIMELINE_AND_ALLOW_ACK_CURSOR";

    private AgentToolActionControlledDryRunReceiptEventDisplayBuilder() {
    }

    static AgentRuntimeEventDisplayView build(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = safeAttributes(record);
        String outcome = text(attributes, "outcome", "UNKNOWN");
        boolean preCheckPassed = bool(attributes, "preCheckPassed");
        boolean sideEffectExecuted = bool(attributes, "sideEffectExecuted");
        Map<String, Object> metrics = metrics(attributes, outcome, preCheckPassed, sideEffectExecuted);
        return new AgentRuntimeEventDisplayView(
                "TOOL_ACTION_CONTROLLED_DRY_RUN_RECEIPT",
                title(outcome, preCheckPassed),
                summary(record, outcome, preCheckPassed, sideEffectExecuted),
                status(outcome, preCheckPassed),
                "tool-guardrail",
                requiresAttention(outcome, preCheckPassed, sideEffectExecuted),
                REPLAY_POLICY_APPEND_AND_ACK,
                recommendedActions(attributes, outcome, preCheckPassed),
                Collections.unmodifiableMap(metrics)
        );
    }

    private static Map<String, Object> metrics(Map<String, Object> attributes,
                                               String outcome,
                                               boolean preCheckPassed,
                                               boolean sideEffectExecuted) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("outcome", outcome);
        metrics.put("preCheckPassed", preCheckPassed);
        metrics.put("sideEffectExecuted", sideEffectExecuted);
        metrics.put("toolCode", text(attributes, "toolCode", null));
        metrics.put("targetService", text(attributes, "targetService", null));
        metrics.put("taskId", attributes.get("taskId"));
        metrics.put("taskRunId", attributes.get("taskRunId"));
        metrics.put("payloadStoreEvidence", bool(attributes, "payloadStoreEvidence"));
        metrics.put("payloadBodyAvailable", bool(attributes, "payloadBodyAvailable"));
        metrics.put("workerDispatchEnabled", bool(attributes, "workerDispatchEnabled"));
        metrics.put("policyVersionCount", intValue(attributes, "policyVersionCount"));
        metrics.put("delegationEvidenceCount", intValue(attributes, "delegationEvidenceCount"));
        return metrics;
    }

    private static String title(String outcome, boolean preCheckPassed) {
        return switch (outcome) {
            case "FAILED_PRECHECK" -> "受控工具动作前置复核失败";
            case "DEFERRED_WAITING_APPROVAL_FACT" -> "受控工具动作等待审批事实";
            case "DEFERRED_WAITING_PAYLOAD_BODY" -> "受控工具动作等待 payload body";
            case "DEFERRED_READY_FOR_EXECUTOR" -> "受控工具动作等待专用 executor";
            case "CAPACITY_LIMITED" -> "受控工具动作 dry-run 受容量保护限制";
            default -> preCheckPassed ? "受控工具动作 dry-run 已记录" : "受控工具动作 dry-run 已进入治理";
        };
    }

    private static String summary(AgentRuntimeEventProjectionRecord record,
                                  String outcome,
                                  boolean preCheckPassed,
                                  boolean sideEffectExecuted) {
        if (sideEffectExecuted) {
            return "receipt 声称 dry-run 已产生副作用，这不符合受控工具动作当前阶段的安全语义，请检查内部调用方。";
        }
        String message = record.message();
        if (message != null && !message.isBlank()) {
            return message;
        }
        if ("FAILED_PRECHECK".equals(outcome)) {
            return "受控工具动作在真实工具执行前被阻断，task-management 没有调用下游业务服务。";
        }
        if (preCheckPassed) {
            return "受控工具动作通过低敏前置复核，但仍等待 payload body、审批事实或专用 executor 补齐。";
        }
        return "受控工具动作 dry-run receipt 已写入 timeline。";
    }

    private static String status(String outcome, boolean preCheckPassed) {
        return switch (outcome) {
            case "FAILED_PRECHECK" -> "BLOCKED_BEFORE_SIDE_EFFECT";
            case "DEFERRED_WAITING_APPROVAL_FACT" -> "WAITING_APPROVAL_FACT";
            case "DEFERRED_WAITING_PAYLOAD_BODY" -> "WAITING_PAYLOAD_BODY";
            case "DEFERRED_READY_FOR_EXECUTOR" -> "WAITING_CONTROLLED_EXECUTOR";
            case "CAPACITY_LIMITED" -> "WAITING_WORKER_CAPACITY";
            default -> preCheckPassed ? "PRECHECK_PASSED_NO_SIDE_EFFECT" : "DRY_RUN_RECORDED";
        };
    }

    private static boolean requiresAttention(String outcome, boolean preCheckPassed, boolean sideEffectExecuted) {
        return sideEffectExecuted || "FAILED_PRECHECK".equals(outcome) || !preCheckPassed;
    }

    @SuppressWarnings("unchecked")
    private static List<String> recommendedActions(Map<String, Object> attributes,
                                                   String outcome,
                                                   boolean preCheckPassed) {
        Object value = attributes.get("recommendedActions");
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return (List<String>) value;
        }
        if ("FAILED_PRECHECK".equals(outcome)) {
            return List.of("检查 payload store 证据、策略版本、runId 绑定和 task.params 低敏命令信封。");
        }
        if ("DEFERRED_WAITING_APPROVAL_FACT".equals(outcome)) {
            return List.of("在 permission-admin 中登记或完成 approvalFactId 对应审批，并确保其绑定当前 tenant/project/actor/session/run/command/tool。");
        }
        if ("DEFERRED_WAITING_PAYLOAD_BODY".equals(outcome)) {
            return List.of("补齐 agent-runtime payload store 生产实现，并确保真实参数只由服务端 executor 按授权读取。");
        }
        if ("DEFERRED_READY_FOR_EXECUTOR".equals(outcome)) {
            return List.of("接入专用 executor、permission-admin 审批事实和低敏 worker receipt 后再开放真实副作用。");
        }
        if (preCheckPassed) {
            return List.of("继续观察后续 executor receipt，确认是否进入真实执行前的审批与幂等复核。");
        }
        return List.of("查看同一 runId 的前后事件，确认受控工具动作停留在哪个治理阶段。");
    }

    private static Map<String, Object> safeAttributes(AgentRuntimeEventProjectionRecord record) {
        return record == null || record.attributes() == null ? Map.of() : record.attributes();
    }

    private static String text(Map<String, Object> attributes, String key, String fallback) {
        Object value = attributes.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private static boolean bool(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(Objects.toString(value, "false"));
    }

    private static int intValue(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}

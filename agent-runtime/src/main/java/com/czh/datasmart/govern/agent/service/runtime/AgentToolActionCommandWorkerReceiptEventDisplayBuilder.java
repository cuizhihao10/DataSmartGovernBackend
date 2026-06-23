/**
 * @Author : Cui
 * @Date: 2026/06/23 00:00
 * @Description DataSmart Govern Backend - AgentToolActionCommandWorkerReceiptEventDisplayBuilder.java
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
 * 受控命令 worker 回执的 timeline 展示解释器。
 *
 * <p>runtime event attributes 是机器事实层，字段命名更偏治理与审计；timeline display 是面向人类的解释层，
 * 需要把低敏字段翻译成“是否已执行”“是否需要补偿”“是否等待容量”“是否可以继续排障”等产品状态。
 * 本类只读取低敏白名单字段，绝不尝试读取命令行、stdout/stderr、真实路径、环境变量、payload body、
 * SQL、prompt 或模型输出。</p>
 *
 * <p>它也不会根据回执自动推进任务状态。状态推进仍应由 task-management、worker 调度器和权限中心完成；
 * display 只负责解释已经发生的事实，避免 timeline 展示层变成隐式状态机。</p>
 */
final class AgentToolActionCommandWorkerReceiptEventDisplayBuilder {

    private static final String REPLAY_POLICY_APPEND_AND_ACK = "APPEND_TO_TIMELINE_AND_ALLOW_ACK_CURSOR";

    private AgentToolActionCommandWorkerReceiptEventDisplayBuilder() {
    }

    static AgentRuntimeEventDisplayView build(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = safeAttributes(record);
        String outcome = text(attributes, "outcome", "UNKNOWN");
        boolean preCheckPassed = bool(attributes, "preCheckPassed");
        boolean sideEffectStarted = bool(attributes, "sideEffectStarted");
        boolean sideEffectExecuted = bool(attributes, "sideEffectExecuted");
        Map<String, Object> metrics = metrics(attributes, outcome, preCheckPassed, sideEffectStarted, sideEffectExecuted);
        return new AgentRuntimeEventDisplayView(
                "TOOL_ACTION_COMMAND_WORKER_RECEIPT",
                title(outcome, sideEffectExecuted),
                summary(record, outcome, preCheckPassed, sideEffectStarted, sideEffectExecuted),
                status(outcome, preCheckPassed, sideEffectStarted, sideEffectExecuted),
                sideEffectExecuted ? "tool-executed" : "tool-guardrail",
                requiresAttention(outcome, preCheckPassed),
                REPLAY_POLICY_APPEND_AND_ACK,
                recommendedActions(attributes, outcome, preCheckPassed, sideEffectExecuted),
                Collections.unmodifiableMap(metrics)
        );
    }

    private static Map<String, Object> metrics(Map<String, Object> attributes,
                                               String outcome,
                                               boolean preCheckPassed,
                                               boolean sideEffectStarted,
                                               boolean sideEffectExecuted) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("outcome", outcome);
        metrics.put("preCheckPassed", preCheckPassed);
        metrics.put("sideEffectStarted", sideEffectStarted);
        metrics.put("sideEffectExecuted", sideEffectExecuted);
        metrics.put("toolCode", text(attributes, "toolCode", null));
        metrics.put("targetService", text(attributes, "targetService", null));
        metrics.put("taskId", attributes.get("taskId"));
        metrics.put("taskRunId", attributes.get("taskRunId"));
        metrics.put("workerReceiptMode", text(attributes, "workerReceiptMode", null));
        metrics.put("commandSafetyDecision", text(attributes, "commandSafetyDecision", "UNKNOWN"));
        metrics.put("commandSafetyIssueCodeCount", intValue(attributes, "commandSafetyIssueCodeCount"));
        metrics.put("normalizedTimeoutSeconds", intValue(attributes, "normalizedTimeoutSeconds"));
        metrics.put("normalizedOutputByteLimitBytes", intValue(attributes, "normalizedOutputByteLimitBytes"));
        metrics.put("artifactAvailable", bool(attributes, "artifactAvailable"));
        metrics.put("artifactReferenceType", text(attributes, "artifactReferenceType", null));
        return metrics;
    }

    private static String title(String outcome, boolean sideEffectExecuted) {
        return switch (outcome) {
            case "FAILED_PRECHECK" -> "受控命令 worker 执行前复核失败";
            case "WORKER_PRECHECK_PASSED" -> "受控命令 worker 执行前复核通过";
            case "EXECUTION_SUCCEEDED" -> "受控命令 worker 已确认执行成功";
            case "EXECUTION_FAILED" -> "受控命令 worker 执行失败";
            case "EXECUTION_SKIPPED" -> "受控命令 worker 已跳过执行";
            case "CAPACITY_LIMITED" -> "受控命令 worker 受容量保护限制";
            case "COMPENSATION_REQUIRED" -> "受控命令 worker 需要补偿处理";
            default -> sideEffectExecuted ? "受控命令 worker 已写回执行事实" : "受控命令 worker 回执已记录";
        };
    }

    private static String summary(AgentRuntimeEventProjectionRecord record,
                                  String outcome,
                                  boolean preCheckPassed,
                                  boolean sideEffectStarted,
                                  boolean sideEffectExecuted) {
        String message = record.message();
        if (message != null && !message.isBlank()) {
            return message;
        }
        if ("EXECUTION_SUCCEEDED".equals(outcome)) {
            return "worker 已在安全预检放行后完成受控执行；timeline 只展示低敏结果，不展示命令行或输出正文。";
        }
        if ("EXECUTION_FAILED".equals(outcome)) {
            return "worker 已写回低敏失败摘要；如需查看详细输出，应通过受权限保护的 artifact 流程二次鉴权读取。";
        }
        if ("FAILED_PRECHECK".equals(outcome)) {
            return "worker 在真实副作用开始前阻断命令，当前回执可作为恢复事实包中的 rejected 依据。";
        }
        if (preCheckPassed && !sideEffectStarted && !sideEffectExecuted) {
            return "worker 侧服务端复核已经通过，但尚未进入可能产生副作用的执行区。";
        }
        return "worker 回执已进入 runtime event timeline，可用于后续审计、恢复事实包和执行状态诊断。";
    }

    private static String status(String outcome,
                                 boolean preCheckPassed,
                                 boolean sideEffectStarted,
                                 boolean sideEffectExecuted) {
        return switch (outcome) {
            case "FAILED_PRECHECK" -> "BLOCKED_BEFORE_SIDE_EFFECT";
            case "WORKER_PRECHECK_PASSED" -> "READY_FOR_CONTROLLED_EXECUTION";
            case "EXECUTION_SUCCEEDED" -> "SIDE_EFFECT_CONFIRMED";
            case "EXECUTION_FAILED" -> sideEffectStarted ? "SIDE_EFFECT_FAILED_OR_UNKNOWN" : "FAILED_BEFORE_SIDE_EFFECT";
            case "EXECUTION_SKIPPED" -> "EXECUTION_SKIPPED";
            case "CAPACITY_LIMITED" -> "WAITING_WORKER_CAPACITY";
            case "COMPENSATION_REQUIRED" -> "COMPENSATION_REQUIRED";
            default -> fallbackStatus(preCheckPassed, sideEffectStarted, sideEffectExecuted);
        };
    }

    private static String fallbackStatus(boolean preCheckPassed,
                                         boolean sideEffectStarted,
                                         boolean sideEffectExecuted) {
        if (sideEffectExecuted) {
            return "SIDE_EFFECT_CONFIRMED";
        }
        if (sideEffectStarted) {
            return "SIDE_EFFECT_STARTED";
        }
        if (preCheckPassed) {
            return "READY_FOR_CONTROLLED_EXECUTION";
        }
        return "WORKER_RECEIPT_RECORDED";
    }

    private static boolean requiresAttention(String outcome, boolean preCheckPassed) {
        return "FAILED_PRECHECK".equals(outcome)
                || "EXECUTION_FAILED".equals(outcome)
                || "COMPENSATION_REQUIRED".equals(outcome)
                || "CAPACITY_LIMITED".equals(outcome)
                || !preCheckPassed;
    }

    @SuppressWarnings("unchecked")
    private static List<String> recommendedActions(Map<String, Object> attributes,
                                                   String outcome,
                                                   boolean preCheckPassed,
                                                   boolean sideEffectExecuted) {
        Object value = attributes.get("recommendedActions");
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return (List<String>) value;
        }
        if ("FAILED_PRECHECK".equals(outcome)) {
            return List.of("查看 commandSafetyDecision 与 issueCode 摘要，确认是否缺少审批、预算、沙箱能力或安全策略版本。");
        }
        if ("WORKER_PRECHECK_PASSED".equals(outcome)) {
            return List.of("等待 worker 进入受控执行区；如长时间无执行结果，应检查 worker lease、容量和队列积压。");
        }
        if ("EXECUTION_SUCCEEDED".equals(outcome)) {
            return List.of("在任务中心确认业务状态是否已推进；如有产物引用，按权限进入 artifact 元数据页查看。");
        }
        if ("EXECUTION_FAILED".equals(outcome)) {
            return List.of("按 errorCode 聚合排障，并通过受权限保护的 artifact 读取裁剪后的输出摘要。");
        }
        if ("CAPACITY_LIMITED".equals(outcome)) {
            return List.of("不要让 Agent 立即高频重试；应等待 worker 容量恢复、降低并发或转入队列退避。");
        }
        if ("COMPENSATION_REQUIRED".equals(outcome)) {
            return List.of("进入任务运维流程确认是否需要补偿、重试、回滚或人工标记最终状态。");
        }
        if (sideEffectExecuted) {
            return List.of("确认任务中心、审计中心和 artifact 元数据是否已经与本次执行事实对账。");
        }
        if (preCheckPassed) {
            return List.of("继续观察后续 execution receipt，确认 worker 是否真正进入受控执行。");
        }
        return List.of("查看同一 runId 的前后事件，确认命令停留在哪个治理阶段。");
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

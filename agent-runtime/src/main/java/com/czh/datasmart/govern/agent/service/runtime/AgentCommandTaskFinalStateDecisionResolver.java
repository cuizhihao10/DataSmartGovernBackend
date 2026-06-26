/**
 * @Author : Cui
 * @Date: 2026/06/26 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateDecisionResolver.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * worker receipt outcome 到任务状态建议的规则解析器。
 *
 * <p>该类专门承载最终态对账的业务规则，不访问数据库、不读取 HTTP Header，也不组装 Controller DTO。
 * 这样做是为了让规则本身更容易学习和测试：读者可以单独理解“什么 outcome 会让任务成功、失败、退避、等待”，
 * 而不必在权限收口、哈希指纹、DTO 字段映射里寻找业务分支。</p>
 *
 * <p>规则采用保守策略：只有 receipt 明确表达真实执行成功/失败、补偿需要或执行前阻断时，才建议终态；
 * WORKER_PRECHECK_PASSED、CAPACITY_LIMITED、DRY_RUN_PASSED 都不会被误判为成功终态。</p>
 */
class AgentCommandTaskFinalStateDecisionResolver {

    AgentCommandTaskFinalStateDecision decide(AgentToolActionWorkerReceiptIndexRecord latest) {
        String outcome = normalizeUpper(latest.outcome());
        return switch (outcome) {
            case "EXECUTION_SUCCEEDED" -> executionSucceeded(latest);
            case "EXECUTION_FAILED" -> executionFailed(latest);
            case "COMPENSATION_REQUIRED" -> compensationRequired(latest);
            case "FAILED_PRECHECK" -> blockedBeforeExecution(latest);
            case "WORKER_PRECHECK_PASSED" -> workerPrecheckPassed(latest);
            case "CAPACITY_LIMITED" -> capacityLimited(latest);
            case "DRY_RUN_PASSED" -> dryRunPassed(latest);
            default -> unknownOutcome(latest, outcome);
        };
    }

    AgentCommandTaskFinalStateDecision noReceiptDecision() {
        return AgentCommandTaskFinalStateDecision.waiting(
                "WAITING_WORKER_RECEIPT",
                "尚未查询到 worker receipt，不能判断任务最终态。",
                List.of("FINAL_STATE_RECONCILIATION_SCOPED", "FINAL_STATE_WORKER_RECEIPT_NOT_FOUND"),
                List.of("WORKER_RECEIPT_NOT_FOUND"),
                List.of("继续等待 worker receipt。", "检查 command outbox 是否已投递、worker 是否已领取、receipt 写回是否失败。")
        );
    }

    private AgentCommandTaskFinalStateDecision executionSucceeded(AgentToolActionWorkerReceiptIndexRecord latest) {
        List<String> issues = new ArrayList<>();
        if (!Boolean.TRUE.equals(latest.preCheckPassed()) || !Boolean.TRUE.equals(latest.sideEffectExecuted())) {
            issues.add("SUCCESS_OUTCOME_INCONSISTENT_WITH_RECEIPT_FLAGS");
        }
        addCallbackLinkIssues(latest, issues);
        return decision(
                "SUCCEEDED",
                "SUCCEEDED",
                true,
                true,
                false,
                false,
                "SUCCEEDED",
                "受控命令 worker 已通过低敏 receipt 确认执行成功，建议把 Agent 异步任务推进到成功。",
                null,
                "受控命令执行成功已由 worker receipt 确认，真实输出正文请继续通过 artifact 授权链路读取。",
                evidence(latest, "FINAL_STATE_RECEIPT_EXECUTION_SUCCEEDED"),
                issues,
                List.of("触发 SUCCEEDED 状态回调。", "如需展示执行产物，请继续走 artifact metadata/body-read/final-check 门禁。")
        );
    }

    private AgentCommandTaskFinalStateDecision executionFailed(AgentToolActionWorkerReceiptIndexRecord latest) {
        List<String> issues = new ArrayList<>();
        addCallbackLinkIssues(latest, issues);
        issues.add("EXECUTION_FAILED_MAY_REQUIRE_COMPENSATION_REVIEW");
        return decision(
                "FAILED",
                "FAILED",
                true,
                true,
                true,
                false,
                "FAILED",
                "受控命令 worker 已写回执行失败 receipt，建议把 Agent 异步任务推进到失败并进入补偿排障。",
                safeErrorCode(latest, "AGENT_COMMAND_WORKER_EXECUTION_FAILED"),
                null,
                evidence(latest, "FINAL_STATE_RECEIPT_EXECUTION_FAILED"),
                issues,
                List.of("触发 FAILED 状态回调。", "检查 outbox dead-letter/requeue/ignore/note 补偿台。", "如副作用可能已经开始，请由运维确认是否需要业务补偿。")
        );
    }

    private AgentCommandTaskFinalStateDecision compensationRequired(AgentToolActionWorkerReceiptIndexRecord latest) {
        List<String> issues = new ArrayList<>();
        addCallbackLinkIssues(latest, issues);
        issues.add("WORKER_DECLARED_COMPENSATION_REQUIRED");
        return decision(
                "COMPENSATION_REQUIRED",
                "FAILED",
                true,
                true,
                true,
                false,
                "FAILED",
                "受控命令 worker 声明需要补偿处理，建议任务先进入失败可见性，再由补偿台接管。",
                safeErrorCode(latest, "AGENT_COMMAND_WORKER_COMPENSATION_REQUIRED"),
                null,
                evidence(latest, "FINAL_STATE_RECEIPT_COMPENSATION_REQUIRED"),
                issues,
                List.of("触发 FAILED 状态回调。", "进入人工补偿流程，确认是否重排、忽略或业务回滚。")
        );
    }

    private AgentCommandTaskFinalStateDecision blockedBeforeExecution(AgentToolActionWorkerReceiptIndexRecord latest) {
        List<String> issues = new ArrayList<>();
        addCallbackLinkIssues(latest, issues);
        issues.add("COMMAND_BLOCKED_BEFORE_SIDE_EFFECT");
        return decision(
                "BLOCKED_BEFORE_EXECUTION",
                "FAILED",
                true,
                true,
                false,
                false,
                "FAILED",
                "受控命令在执行前复核阶段被阻断，未产生真实副作用，建议任务失败并等待策略/审批修复后重排。",
                safeErrorCode(latest, "AGENT_COMMAND_WORKER_PRECHECK_REJECTED"),
                null,
                evidence(latest, "FINAL_STATE_RECEIPT_FAILED_PRECHECK"),
                issues,
                List.of("触发 FAILED 状态回调。", "修复权限、审批、预算或安全策略后，通过 outbox requeue 重新投递。")
        );
    }

    private AgentCommandTaskFinalStateDecision workerPrecheckPassed(AgentToolActionWorkerReceiptIndexRecord latest) {
        List<String> issues = new ArrayList<>();
        addCallbackLinkIssues(latest, issues);
        return decision(
                "WAITING_CONTROLLED_EXECUTION",
                "RUNNING",
                false,
                true,
                false,
                true,
                "RUNNING",
                "受控命令 worker 预检已通过，但 receipt 尚未确认真实执行结果，建议刷新为执行中并继续等待。",
                null,
                null,
                evidence(latest, "FINAL_STATE_RECEIPT_WORKER_PRECHECK_PASSED"),
                issues,
                List.of("触发 RUNNING 可见性回调。", "继续等待 EXECUTION_SUCCEEDED、EXECUTION_FAILED 或补偿 receipt。")
        );
    }

    private AgentCommandTaskFinalStateDecision capacityLimited(AgentToolActionWorkerReceiptIndexRecord latest) {
        List<String> issues = new ArrayList<>();
        addCallbackLinkIssues(latest, issues);
        issues.add("WORKER_CAPACITY_LIMITED_NOT_TERMINAL");
        return decision(
                "WAITING_WORKER_CAPACITY",
                "DEFERRED",
                false,
                true,
                false,
                true,
                "DEFERRED",
                "受控命令 worker 受容量保护限制，建议刷新为退避等待，不要把任务误判为失败终态。",
                safeErrorCode(latest, "AGENT_COMMAND_WORKER_CAPACITY_LIMITED"),
                null,
                evidence(latest, "FINAL_STATE_RECEIPT_CAPACITY_LIMITED"),
                issues,
                List.of("触发 DEFERRED 可见性回调。", "观察队列积压、worker 并发和租户配额。")
        );
    }

    private AgentCommandTaskFinalStateDecision dryRunPassed(AgentToolActionWorkerReceiptIndexRecord latest) {
        List<String> issues = new ArrayList<>();
        issues.add("DRY_RUN_IS_NOT_REAL_EXECUTION_RESULT");
        addCallbackLinkIssues(latest, issues);
        return decision(
                "WAITING_REAL_WORKER_RECEIPT",
                "RUNNING",
                false,
                false,
                false,
                true,
                null,
                "当前只有 dry-run 通过事实，不能据此把任务置为成功或失败。",
                null,
                null,
                evidence(latest, "FINAL_STATE_RECEIPT_DRY_RUN_ONLY"),
                issues,
                List.of("继续等待真实 command worker receipt。", "确认 outbox dispatcher 与 worker 是否已经领取命令。")
        );
    }

    private AgentCommandTaskFinalStateDecision unknownOutcome(AgentToolActionWorkerReceiptIndexRecord latest,
                                                             String outcome) {
        List<String> issues = new ArrayList<>();
        issues.add("UNKNOWN_WORKER_RECEIPT_OUTCOME_" + safeCode(outcome));
        addCallbackLinkIssues(latest, issues);
        return decision(
                "UNKNOWN_RECEIPT_OUTCOME",
                "RUNNING",
                false,
                false,
                false,
                true,
                null,
                "最新 worker receipt outcome 暂未纳入最终态规则，保守保持等待并要求人工确认。",
                safeErrorCode(latest, "AGENT_COMMAND_WORKER_RECEIPT_RECORDED"),
                null,
                evidence(latest, "FINAL_STATE_RECEIPT_UNKNOWN_OUTCOME"),
                issues,
                List.of("补充 outcome 对账规则或由运维人工确认。", "不要在未知 outcome 下自动推进终态。")
        );
    }

    private AgentCommandTaskFinalStateDecision decision(String reconciliationStatus,
                                                       String reconciledTaskStatus,
                                                       boolean terminal,
                                                       boolean callbackRecommended,
                                                       boolean requiresManualCompensation,
                                                       boolean retryable,
                                                       String callbackStatus,
                                                       String callbackMessage,
                                                       String callbackErrorCode,
                                                       String outputSummary,
                                                       List<String> evidenceCodes,
                                                       List<String> issueCodes,
                                                       List<String> recommendedActions) {
        return new AgentCommandTaskFinalStateDecision(
                reconciliationStatus,
                reconciledTaskStatus,
                terminal,
                callbackRecommended,
                requiresManualCompensation,
                retryable,
                callbackStatus,
                callbackMessage,
                callbackErrorCode,
                outputSummary,
                List.copyOf(evidenceCodes),
                List.copyOf(issueCodes),
                List.copyOf(recommendedActions)
        );
    }

    private List<String> evidence(AgentToolActionWorkerReceiptIndexRecord latest, String outcomeEvidenceCode) {
        List<String> evidence = new ArrayList<>();
        evidence.add("FINAL_STATE_RECONCILIATION_SCOPED");
        evidence.add("FINAL_STATE_WORKER_RECEIPT_INDEX_FOUND");
        evidence.add(outcomeEvidenceCode);
        if (latest.replaySequence() != null) {
            evidence.add("FINAL_STATE_LATEST_REPLAY_SEQUENCE_SELECTED");
        }
        if (latest.taskId() != null) {
            evidence.add("FINAL_STATE_TASK_ID_LINK_PRESENT");
        }
        if (hasText(latest.auditId())) {
            evidence.add("FINAL_STATE_AGENT_AUDIT_LINK_PRESENT");
        }
        return List.copyOf(evidence);
    }

    private void addCallbackLinkIssues(AgentToolActionWorkerReceiptIndexRecord latest, List<String> issues) {
        if (latest.taskId() == null) {
            issues.add("TASK_ID_MISSING_FOR_AUTOMATED_CALLBACK");
        }
        if (latest.taskRunId() == null) {
            issues.add("TASK_RUN_ID_MISSING_FOR_AUTOMATED_CALLBACK");
        }
        if (!hasText(latest.auditId())) {
            issues.add("AUDIT_ID_MISSING_FOR_AGENT_AUDIT_CALLBACK");
        }
    }

    private String safeErrorCode(AgentToolActionWorkerReceiptIndexRecord latest, String fallback) {
        if (latest == null || !hasText(latest.errorCode())) {
            return fallback;
        }
        return latest.errorCode();
    }

    private String normalizeUpper(String value) {
        String text = value == null || value.isBlank() ? null : value.trim();
        return text == null ? "UNKNOWN" : text.toUpperCase(Locale.ROOT);
    }

    private String safeCode(String value) {
        String text = value == null || value.isBlank() ? null : value.trim();
        if (text == null) {
            return "UNKNOWN";
        }
        return text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_.:-]", "_");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

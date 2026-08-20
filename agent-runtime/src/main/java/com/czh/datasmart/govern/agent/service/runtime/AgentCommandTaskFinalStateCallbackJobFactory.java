/**
 * @Author : Cui
 * @Date: 2026/08/19 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateCallbackJobFactory.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * 从 Java worker receipt 事实创建最终态 callback job 的纯规则工厂。
 *
 * <p>这里的白名单是自动收敛的第一道保险：只有 {@code EXECUTION_SUCCEEDED}、{@code EXECUTION_FAILED}、
 * {@code COMPENSATION_REQUIRED} 和 {@code FAILED_PRECHECK} 能创建 job。AUTO_APPROVED 只代表审批事实，
 * PUBLISHED 只代表 command 已投递，它们都不会越过此处变成成功 callback。</p>
 */
final class AgentCommandTaskFinalStateCallbackJobFactory {

    private AgentCommandTaskFinalStateCallbackJobFactory() {
    }

    /**
     * 基于一条 Java receipt 生成稳定 job；不合格 outcome 返回空，保持 fail-closed。
     *
     * @param receipt Java 已物化的低敏 worker receipt。
     * @param now 当前 Java 控制面时间。
     * @return 可自动收敛的 durable job，或空表示仍应等待真实执行事实。
     */
    static Optional<AgentCommandTaskFinalStateCallbackJob> create(
            AgentToolActionWorkerReceiptIndexRecord receipt,
            Instant now) {
        if (receipt == null || !receipt.indexable()) {
            return Optional.empty();
        }
        String outcome = normalizedOutcome(receipt.outcome());
        String callbackStatus = callbackStatus(outcome);
        if (callbackStatus == null) {
            return Optional.empty();
        }
        Instant createdAt = now == null ? Instant.now() : now;
        boolean compensation = "EXECUTION_FAILED".equals(outcome) || "COMPENSATION_REQUIRED".equals(outcome);
        return Optional.of(new AgentCommandTaskFinalStateCallbackJob(
                workId(receipt.eventIdentityKey()),
                receipt.eventIdentityKey(),
                receipt.replaySequence(),
                receipt.commandId(),
                receipt.taskId(),
                receipt.taskRunId(),
                receipt.executorId(),
                receipt.auditId(),
                receipt.tenantId(),
                receipt.projectId(),
                receipt.actorId(),
                receipt.runId(),
                receipt.sessionId(),
                receipt.toolCode(),
                callbackStatus,
                callbackIdempotencyKey(receipt.commandId(), reconciliationStatus(outcome), receipt.replaySequence()),
                compensation,
                AgentCommandTaskFinalStateCallbackJobStatus.PENDING,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                createdAt,
                createdAt
        ));
    }

    /**
     * 构造下游 callback 的稳定幂等键，第二段使用 reconciliation 状态而不是 callback 状态，
     * 以保持与既有 dispatch service 的幂等契约完全一致。
     */
    static String callbackIdempotencyKey(String commandId, String reconciliationStatus, Long replaySequence) {
        String sequence = replaySequence == null ? "no-receipt" : String.valueOf(replaySequence);
        return "agent-command-final-state:" + safeCode(commandId) + ":" + safeCode(reconciliationStatus) + ":" + sequence;
    }

    /**
     * 通过 receipt identity 的 SHA-256 短摘要生成内部 job ID，避免把原 identity 暴露到日志或外部系统。
     */
    static String workId(String sourceReceiptIdentityKey) {
        String value = sourceReceiptIdentityKey == null ? "missing" : sourceReceiptIdentityKey;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "agent-final-state-callback:" + HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256，无法生成最终态 callback job ID", exception);
        }
    }

    /**
     * 将 outcome 归一为稳定大写代码，空值一律不允许进入自动路径。
     */
    private static String normalizedOutcome(String outcome) {
        return outcome == null || outcome.isBlank() ? "" : outcome.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 返回可自动处理的最终 callback 状态；未列入白名单的事实统一返回 null。
     */
    private static String callbackStatus(String outcome) {
        return switch (outcome) {
            case "EXECUTION_SUCCEEDED" -> "SUCCEEDED";
            case "EXECUTION_FAILED", "COMPENSATION_REQUIRED", "FAILED_PRECHECK" -> "FAILED";
            default -> null;
        };
    }

    /**
     * 把 receipt outcome 映射为现有 reconciliation service 使用的状态键，供下游幂等键保持兼容。
     */
    private static String reconciliationStatus(String outcome) {
        return switch (outcome) {
            case "EXECUTION_SUCCEEDED" -> "SUCCEEDED";
            case "EXECUTION_FAILED" -> "FAILED";
            case "COMPENSATION_REQUIRED" -> "COMPENSATION_REQUIRED";
            case "FAILED_PRECHECK" -> "BLOCKED_BEFORE_EXECUTION";
            default -> "UNKNOWN";
        };
    }

    /**
     * 对幂等键中的可见代码进行保守清洗，避免异常 ID 把不可信文本写入 task-management。
     */
    private static String safeCode(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        return value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_.:-]", "_");
    }
}

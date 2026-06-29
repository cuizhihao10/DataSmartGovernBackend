/**
 * @Author : Cui
 * @Date: 2026/06/29 19:34
 * @Description DataSmart Govern Backend - TaskManagementReceiptOutboxDispatchResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/**
 * task-management receipt outbox 派发结果。
 *
 * <p>结果只返回聚合计数和低敏 issueCode，不返回 receipt payload、task-management 响应体、内部 URL、SQL、字段映射、
 * checkpoint 原始值、样本数据、prompt 或模型输出。</p>
 */
public record TaskManagementReceiptOutboxDispatchResult(
        int scannedCount,
        int attemptedCount,
        int deliveredCount,
        int retryScheduledCount,
        int deadLetteredCount,
        int skippedCount,
        List<String> issueCodes,
        String payloadPolicy
) {

    public static final String PAYLOAD_POLICY =
            "LOW_SENSITIVE_RECEIPT_OUTBOX_SUMMARY_NO_PAYLOAD_NO_URL_NO_SQL_NO_SAMPLE_NO_MODEL_OUTPUT";

    public static TaskManagementReceiptOutboxDispatchResult empty(String issueCode) {
        return new TaskManagementReceiptOutboxDispatchResult(
                0, 0, 0, 0, 0, 0,
                issueCode == null || issueCode.isBlank() ? List.of() : List.of(issueCode),
                PAYLOAD_POLICY
        );
    }

    public TaskManagementReceiptOutboxDispatchResult plus(TaskManagementReceiptOutboxDispatchResult other) {
        if (other == null) {
            return this;
        }
        List<String> mergedIssues = new java.util.ArrayList<>(issueCodes == null ? List.of() : issueCodes);
        mergedIssues.addAll(other.issueCodes == null ? List.of() : other.issueCodes);
        return new TaskManagementReceiptOutboxDispatchResult(
                scannedCount + other.scannedCount,
                attemptedCount + other.attemptedCount,
                deliveredCount + other.deliveredCount,
                retryScheduledCount + other.retryScheduledCount,
                deadLetteredCount + other.deadLetteredCount,
                skippedCount + other.skippedCount,
                List.copyOf(mergedIssues),
                PAYLOAD_POLICY
        );
    }

    public static TaskManagementReceiptOutboxDispatchResult scannedOnly(int scannedCount) {
        return new TaskManagementReceiptOutboxDispatchResult(
                scannedCount, 0, 0, 0, 0, 0, List.of(), PAYLOAD_POLICY
        );
    }

    public static TaskManagementReceiptOutboxDispatchResult delivered() {
        return new TaskManagementReceiptOutboxDispatchResult(
                1, 1, 1, 0, 0, 0, List.of(), PAYLOAD_POLICY
        );
    }

    public static TaskManagementReceiptOutboxDispatchResult retryScheduled(String issueCode) {
        return new TaskManagementReceiptOutboxDispatchResult(
                1, 1, 0, 1, 0, 0, List.of(safeIssue(issueCode)), PAYLOAD_POLICY
        );
    }

    public static TaskManagementReceiptOutboxDispatchResult deadLettered(String issueCode) {
        return new TaskManagementReceiptOutboxDispatchResult(
                1, 1, 0, 0, 1, 0, List.of(safeIssue(issueCode)), PAYLOAD_POLICY
        );
    }

    public static TaskManagementReceiptOutboxDispatchResult skipped(String issueCode) {
        return new TaskManagementReceiptOutboxDispatchResult(
                1, 0, 0, 0, 0, 1, List.of(safeIssue(issueCode)), PAYLOAD_POLICY
        );
    }

    private static String safeIssue(String issueCode) {
        return issueCode == null || issueCode.isBlank() ? "UNKNOWN_RECEIPT_OUTBOX_ISSUE" : issueCode;
    }
}

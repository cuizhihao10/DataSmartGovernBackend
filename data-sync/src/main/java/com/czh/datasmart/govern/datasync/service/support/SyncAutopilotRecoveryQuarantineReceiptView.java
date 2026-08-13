/**
 * @Author : Cui
 * @Date: 2026/08/12
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryQuarantineReceiptView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

/** Safe projection proving that the bounded quarantine side effect durably completed. */
public record SyncAutopilotRecoveryQuarantineReceiptView(
        String receiptId,
        Long caseId,
        Long syncTaskId,
        Long executionId,
        Integer selectedCount,
        Integer affectedCount,
        String operationState,
        String receiptState,
        String previewDigest,
        String actionFingerprint) {
}

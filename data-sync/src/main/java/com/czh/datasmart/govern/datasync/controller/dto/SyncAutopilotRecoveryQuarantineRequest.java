/**
 * @Author : Cui
 * @Date: 2026/08/12
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryQuarantineRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/** Fixed wire contract for the internal autonomous quarantine endpoint. */
public record SyncAutopilotRecoveryQuarantineRequest(
        Long expectedVersion,
        Long tenantId,
        Long projectId,
        Long syncTaskId,
        Long executionId,
        Integer cycle,
        String authorizationDigest,
        String policyDigest,
        String previewDigest,
        List<Long> selectedSampleIds,
        String actionFingerprint,
        String receiptId) {
}

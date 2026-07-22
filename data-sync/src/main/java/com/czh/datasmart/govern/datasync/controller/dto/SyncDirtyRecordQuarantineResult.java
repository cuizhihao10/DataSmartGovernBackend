/**
 * @Author : Cui
 * @Date: 2026/07/22
 * @Description DataSmart Govern Backend - SyncDirtyRecordQuarantineResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/** Low-sensitive preview/apply result for dirty-record quarantine. */
public record SyncDirtyRecordQuarantineResult(
        Long taskId,
        Long executionId,
        int selectedCount,
        int eligibleCount,
        int affectedCount,
        String operationState,
        String confirmationDigest,
        List<Long> selectedSampleIds,
        List<String> issueCodes,
        String message
) {
}

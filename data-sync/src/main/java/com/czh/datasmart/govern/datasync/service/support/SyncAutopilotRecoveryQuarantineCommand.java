/**
 * @Author : Cui
 * @Date: 2026/08/12
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryQuarantineCommand.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.List;

/**
 * Low-sensitive command for one preauthorized, digest-bound dirty-record quarantine.
 *
 * <p>The command contains no source key values, row bodies, SQL, credentials, logs, prompts, or model prose.
 * Every field is independently checked against the persisted recovery case, task policy, and current error
 * samples before a side effect occurs.</p>
 */
public record SyncAutopilotRecoveryQuarantineCommand(
        Long caseId,
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

    /** Copies the ID list so caller mutation cannot change facts after validation. */
    public SyncAutopilotRecoveryQuarantineCommand {
        selectedSampleIds = selectedSampleIds == null ? List.of() : List.copyOf(selectedSampleIds);
    }
}

/**
 * @Author : Cui
 * @Date: 2026/07/22 18:35
 * @Description DataSmart Govern Backend - SyncTaskImportArtifactDryRunResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/** Dry-run response consumed by the UI and Agent diagnostic tool. */
public record SyncTaskImportArtifactDryRunResult(
        SyncTaskImportArtifactView artifact,
        SyncTaskImportResult importResult,
        String confirmationDigest,
        String ragQuery,
        Boolean repairRequired) {
}

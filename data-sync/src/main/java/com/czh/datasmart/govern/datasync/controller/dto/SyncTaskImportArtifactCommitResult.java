/**
 * @Author : Cui
 * @Date: 2026/07/22 18:35
 * @Description DataSmart Govern Backend - SyncTaskImportArtifactCommitResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/** Confirmed import result with the immutable artifact identity used for execution. */
public record SyncTaskImportArtifactCommitResult(
        SyncTaskImportArtifactView artifact,
        SyncTaskImportResult importResult) {
}

/**
 * @Author : Cui
 * @Date: 2026/07/22 18:35
 * @Description DataSmart Govern Backend - SyncTaskImportArtifactView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import com.czh.datasmart.govern.datasync.entity.SyncTaskImportArtifact;

import java.time.LocalDateTime;

/** Safe artifact view; file bytes and persisted diagnostic JSON are never returned. */
public record SyncTaskImportArtifactView(
        String artifactRef,
        Long tenantId,
        Long projectId,
        Long ownerId,
        String parentArtifactRef,
        Integer versionNumber,
        String fileName,
        String fileFormat,
        String contentHash,
        Long contentSizeBytes,
        String artifactState,
        String dryRunStatus,
        String dryRunDigest,
        String repairPatchDigest,
        LocalDateTime createTime,
        LocalDateTime updateTime) {

    public static SyncTaskImportArtifactView from(SyncTaskImportArtifact artifact, String parentArtifactRef) {
        return new SyncTaskImportArtifactView(
                artifact.getArtifactRef(), artifact.getTenantId(), artifact.getProjectId(), artifact.getOwnerId(),
                parentArtifactRef, artifact.getVersionNumber(), artifact.getFileName(), artifact.getFileFormat(),
                artifact.getContentHash(), artifact.getContentSizeBytes(), artifact.getArtifactState(),
                artifact.getDryRunStatus(), artifact.getDryRunDigest(), artifact.getRepairPatchDigest(),
                artifact.getCreateTime(), artifact.getUpdateTime());
    }
}

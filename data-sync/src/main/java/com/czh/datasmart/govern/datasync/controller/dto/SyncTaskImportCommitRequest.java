/**
 * @Author : Cui
 * @Date: 2026/07/22 18:35
 * @Description DataSmart Govern Backend - SyncTaskImportCommitRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.NotBlank;

/** User confirmation required before an artifact can create and optionally run tasks. */
public record SyncTaskImportCommitRequest(
        @NotBlank String confirmationDigest,
        Boolean runImmediately) {
}

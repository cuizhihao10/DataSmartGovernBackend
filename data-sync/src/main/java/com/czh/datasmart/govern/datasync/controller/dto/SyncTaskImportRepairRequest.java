/**
 * @Author : Cui
 * @Date: 2026/07/22 18:35
 * @Description DataSmart Govern Backend - SyncTaskImportRepairRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** Explicit confirmation contract for creating a repaired immutable artifact version. */
public record SyncTaskImportRepairRequest(
        @NotNull Integer baseVersion,
        @NotBlank String confirmationDigest,
        @NotEmpty List<@Valid SyncTaskImportCellPatch> patches) {
}

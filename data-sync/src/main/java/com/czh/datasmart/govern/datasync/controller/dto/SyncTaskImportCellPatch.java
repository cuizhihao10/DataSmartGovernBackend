/**
 * @Author : Cui
 * @Date: 2026/07/22 18:35
 * @Description DataSmart Govern Backend - SyncTaskImportCellPatch.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/** One model-proposed, user-confirmed task-import cell replacement. */
public record SyncTaskImportCellPatch(
        @Min(2) Integer rowNumber,
        @NotBlank String columnName,
        String expectedValue,
        String replacementValue) {
}

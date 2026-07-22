package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Exact confirmation required before an external schema mutation is applied. */
@Data
public class DataSourceSchemaRepairApplyRequest {

    @NotNull
    private Long planId;

    @NotBlank
    private String confirmationDigest;

    private boolean confirmed;
}

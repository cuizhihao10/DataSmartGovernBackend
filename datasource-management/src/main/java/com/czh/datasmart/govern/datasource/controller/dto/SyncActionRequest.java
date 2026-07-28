package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Common actor context for controlled permission and alert actions.
 */
@Data
public class SyncActionRequest {

    @NotNull(message = "actorId 不能为空")
    private Long actorId;

    @NotBlank(message = "actorRole 不能为空")
    private String actorRole;

    @NotNull(message = "actorTenantId 不能为空")
    private Long actorTenantId;

    private String note;
}

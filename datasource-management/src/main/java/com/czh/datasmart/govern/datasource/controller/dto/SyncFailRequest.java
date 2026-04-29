package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:18
 * @Description DataSmart Govern Backend - SyncFailRequest.java
 * @Version:1.0.0
 *
 * 执行失败回写请求。
 */
@Data
public class SyncFailRequest {

    @NotNull(message = "actorId 不能为空")
    private Long actorId;

    @NotBlank(message = "actorRole 不能为空")
    private String actorRole;

    /**
     * 失败回写动作所属租户。
     */
    @NotNull(message = "actorTenantId 不能为空")
    private Long actorTenantId;

    @NotNull(message = "executionId 不能为空")
    private Long executionId;

    @NotNull(message = "failedRecordCount 不能为空")
    @Min(value = 0, message = "failedRecordCount 不能小于 0")
    private Long failedRecordCount;

    @NotBlank(message = "errorSummary 不能为空")
    private String errorSummary;
}

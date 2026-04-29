package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:18
 * @Description DataSmart Govern Backend - SyncRunRequest.java
 * @Version:1.0.0
 *
 * 运行任务请求。
 */
@Data
public class SyncRunRequest {

    @NotNull(message = "actorId 不能为空")
    private Long actorId;

    @NotBlank(message = "actorRole 不能为空")
    private String actorRole;

    /**
     * 运行动作发起人所属租户。
     */
    @NotNull(message = "actorTenantId 不能为空")
    private Long actorTenantId;

    /**
     * 本次触发类型。
     * 允许在默认 triggerType 基础上临时显式覆盖，例如管理员执行一次 backfill。
     */
    @NotBlank(message = "triggerType 不能为空")
    private String triggerType;

    /**
     * 触发原因说明。
     */
    private String triggerReason;
}

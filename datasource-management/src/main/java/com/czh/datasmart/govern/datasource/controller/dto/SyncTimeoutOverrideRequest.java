package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:18
 * @Description DataSmart Govern Backend - SyncTimeoutOverrideRequest.java
 * @Version:1.0.0
 *
 * 管理员超时覆盖请求。
 */
@Data
public class SyncTimeoutOverrideRequest {

    @NotNull(message = "actorId 不能为空")
    private Long actorId;

    @NotBlank(message = "actorRole 不能为空")
    private String actorRole;

    @NotNull(message = "timeoutSeconds 不能为空")
    @Min(value = 1, message = "timeoutSeconds 必须大于 0")
    private Integer timeoutSeconds;

    private String note;
}

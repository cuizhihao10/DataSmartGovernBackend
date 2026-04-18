package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:18
 * @Description DataSmart Govern Backend - SyncPriorityOverrideRequest.java
 * @Version:1.0.0
 *
 * 管理员优先级覆盖请求。
 */
@Data
public class SyncPriorityOverrideRequest {

    @NotNull(message = "actorId 不能为空")
    private Long actorId;

    @NotBlank(message = "actorRole 不能为空")
    private String actorRole;

    @NotBlank(message = "priority 不能为空")
    private String priority;

    private String note;
}

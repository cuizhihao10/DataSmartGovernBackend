package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/19 20:28
 * @Description DataSmart Govern Backend - UpdateSyncTaskRequest.java
 * @Version:1.0.0
 *
 * 更新同步任务请求。
 * 这里更新的是任务的治理属性，而不是底层模板定义本身。
 *
 * 当前也显式补上 `actorRole`，让“改配任务”能进入本地权限矩阵：
 * - 谁可以改普通任务；
 * - 谁只能改自己负责的任务；
 * - 谁完全不能改，只能看或审计。
 */
@Data
public class UpdateSyncTaskRequest {

    @NotBlank(message = "任务名称不能为空")
    private String name;

    private String description;

    @NotBlank(message = "priority 不能为空")
    private String priority;

    @NotBlank(message = "runMode 不能为空")
    private String runMode;

    @NotBlank(message = "triggerType 不能为空")
    private String triggerType;

    private String scheduleConfig;

    @NotNull(message = "ownerId 不能为空")
    private Long ownerId;

    @NotNull(message = "enabled 不能为空")
    private Boolean enabled;

    @NotNull(message = "operatorAttentionRequired 不能为空")
    private Boolean operatorAttentionRequired;

    @NotNull(message = "timeoutSeconds 不能为空")
    @Min(value = 1, message = "timeoutSeconds 必须大于 0")
    private Integer timeoutSeconds;

    @NotNull(message = "maxRetryCount 不能为空")
    @Min(value = 0, message = "maxRetryCount 不能小于 0")
    private Integer maxRetryCount;

    private String incidentNote;

    /**
     * 更新动作发起人。
     */
    @NotNull(message = "updatedBy 不能为空")
    private Long updatedBy;

    /**
     * 更新动作发起人的角色。
     */
    @NotBlank(message = "actorRole 不能为空")
    private String actorRole;
}

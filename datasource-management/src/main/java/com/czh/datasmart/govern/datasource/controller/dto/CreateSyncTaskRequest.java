package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/19 20:28
 * @Description DataSmart Govern Backend - CreateSyncTaskRequest.java
 * @Version:1.0.0
 *
 * 创建同步任务请求。
 * 与模板不同，任务请求必须携带运行层面需要的治理信息，例如负责人、优先级、触发方式和超时限制。
 *
 * 这一轮额外补上了 `actorRole`，原因是：
 * 1. 任务创建本身就是一种需要被授权的控制面动作；
 * 2. 如果创建接口不带角色，权限治理就只能覆盖后续动作，前面的“入口动作”仍然是裸奔的；
 * 3. 这也更符合 permission-admin 规划里“按钮/动作级权限”的产品方向。
 */
@Data
public class CreateSyncTaskRequest {

    @NotNull(message = "tenantId 不能为空")
    private Long tenantId;

    @NotNull(message = "templateId 不能为空")
    private Long templateId;

    @NotBlank(message = "任务名称不能为空")
    private String name;

    private String description;

    /**
     * 是否需要审批。
     * 当前先由调用方显式指定，后续可以演进成按模板风险等级、数据敏感级别自动决定。
     */
    @NotNull(message = "approvalRequired 不能为空")
    private Boolean approvalRequired;

    @NotBlank(message = "priority 不能为空")
    private String priority;

    @NotBlank(message = "runMode 不能为空")
    private String runMode;

    @NotBlank(message = "triggerType 不能为空")
    private String triggerType;

    private String scheduleConfig;

    @NotNull(message = "ownerId 不能为空")
    private Long ownerId;

    private Boolean enabled;

    private Boolean operatorAttentionRequired;

    @NotNull(message = "timeoutSeconds 不能为空")
    @Min(value = 1, message = "timeoutSeconds 必须大于 0")
    private Integer timeoutSeconds;

    @NotNull(message = "maxRetryCount 不能为空")
    @Min(value = 0, message = "maxRetryCount 不能小于 0")
    private Integer maxRetryCount;

    private String incidentNote;

    /**
     * 创建动作发起人。
     */
    @NotNull(message = "createdBy 不能为空")
    private Long createdBy;

    /**
     * 创建动作发起人的角色。
     */
    @NotBlank(message = "actorRole 不能为空")
    private String actorRole;

    /**
     * 创建动作发起人所属租户。
     * 这让“能不能在这个租户下建任务”这件事可以在业务层显式判断，而不是只能依赖上游口头约定。
     */
    @NotNull(message = "actorTenantId 不能为空")
    private Long actorTenantId;
}

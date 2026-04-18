package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:18
 * @Description DataSmart Govern Backend - CreateSyncTaskRequest.java
 * @Version:1.0.0
 *
 * 创建同步任务请求。
 * 与模板不同，任务请求必须带上运行层面需要的信息，例如负责人、优先级、触发类型、超时等。
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
     * 当前先通过这个布尔值决定初始审批状态，后续可升级为按模板风险等级、数据级别自动判定。
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

    @NotNull(message = "createdBy 不能为空")
    private Long createdBy;
}

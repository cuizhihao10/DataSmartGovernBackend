package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/4/24 23:18
 * @Description DataSmart Govern Backend - CreateSyncPermissionApprovalDelegateRuleRequest.java
 * @Version:1.0.0
 *
 * 创建权限审批委托规则请求。
 * 这里不是泛化授权模型，而是明确聚焦在“权限策略变更审批”的委托关系上。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CreateSyncPermissionApprovalDelegateRuleRequest extends SyncActionRequest {

    /**
     * 委托生效的目标租户范围。
     * 为空表示平台全局审批委托，只允许平台管理员创建。
     */
    private Long targetTenantId;

    /**
     * 原始审批人 ID。
     */
    @NotNull(message = "delegatorId 不能为空")
    private Long delegatorId;

    /**
     * 原始审批人角色。
     */
    @NotBlank(message = "delegatorRole 不能为空")
    private String delegatorRole;

    /**
     * 被委托审批的人 ID。
     */
    @NotNull(message = "delegateId 不能为空")
    private Long delegateId;

    /**
     * 被委托审批的人角色。
     */
    @NotBlank(message = "delegateRole 不能为空")
    private String delegateRole;

    /**
     * 委托开始时间。
     * 为空时，系统默认按“当前立即生效”解释。
     */
    private LocalDateTime effectiveFrom;

    /**
     * 委托结束时间。
     * 为空表示长期有效，直到被人工禁用。
     */
    private LocalDateTime effectiveTo;

    /**
     * 委托原因说明。
     */
    @NotBlank(message = "delegateReason 不能为空")
    private String delegateReason;
}

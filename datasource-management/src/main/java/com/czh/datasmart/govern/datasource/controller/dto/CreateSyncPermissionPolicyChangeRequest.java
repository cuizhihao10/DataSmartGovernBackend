package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/24 22:03
 * @Description DataSmart Govern Backend - CreateSyncPermissionPolicyChangeRequest.java
 * @Version:1.0.0
 *
 * 创建权限绑定变更申请请求。
 * 这类请求不是直接改策略，而是先把“想怎么改”登记为审批对象。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CreateSyncPermissionPolicyChangeRequest extends SyncActionRequest {

    /**
     * 目标租户。
     * 为空时表示平台全局权限变更，只允许平台管理员发起。
     */
    private Long targetTenantId;

    /**
     * 被修改绑定的目标角色。
     */
    @NotBlank(message = "targetRole 不能为空")
    private String targetRole;

    /**
     * 绑定类型。
     */
    @NotBlank(message = "bindingType 不能为空")
    private String bindingType;

    /**
     * 期望替换后的绑定值列表。
     */
    private List<String> bindingValues = new ArrayList<>();

    /**
     * 期望优先级。
     */
    private Integer priority = 100;

    /**
     * 期望绑定来源。
     */
    private String bindingSource = "MANUAL";

    /**
     * 申请原因。
     * 相比普通 note，这里更强调为什么需要调整权限策略。
     */
    @NotBlank(message = "requestReason 不能为空")
    private String requestReason;
}

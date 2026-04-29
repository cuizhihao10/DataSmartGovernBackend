package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:18
 * @Description DataSmart Govern Backend - SyncActionRequest.java
 * @Version:1.0.0
 *
 * 通用动作请求。
 * 适用于暂停、恢复、取消、提交审批、强制动作等只需要表达“谁做了什么、为什么做”的场景。
 */
@Data
public class SyncActionRequest {

    @NotNull(message = "actorId 不能为空")
    private Long actorId;

    @NotBlank(message = "actorRole 不能为空")
    private String actorRole;

    /**
     * 操作者所属租户。
     * 本字段是本地权限矩阵向 permission-admin 的“租户范围”能力收敛时补上的最小必要上下文。
     * 后续即使接入统一权限中心，这类上下文也仍然需要在网关或认证层被解析出来并传递给业务层。
     */
    @NotNull(message = "actorTenantId 不能为空")
    private Long actorTenantId;

    /**
     * 附加说明。
     * 可用于记录故障处理原因、审批流说明、取消原因等。
     */
    private String note;
}

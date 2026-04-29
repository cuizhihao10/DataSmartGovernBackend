package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/4/24 21:46
 * @Description DataSmart Govern Backend - SyncPermissionBindingView.java
 * @Version:1.0.0
 *
 * 权限绑定视图对象。
 * 相比直接把数据库实体原样返回，这个对象更强调“给人看”的治理含义：
 * - 它会明确告诉前端和运维当前绑定属于平台全局还是租户覆盖；
 * - 它会把绑定类型和值组织成统一结构，方便做矩阵化展示；
 * - 它也保留了来源、备注和更新时间，便于做策略回溯。
 */
@Data
public class SyncPermissionBindingView {

    /**
     * 绑定主键。
     */
    private Long id;

    /**
     * 目标租户。
     * 为空时表示平台全局绑定。
     */
    private Long tenantId;

    /**
     * 绑定作用域类型。
     * 当前返回 `PLATFORM_GLOBAL` 或 `TENANT_OVERRIDE` 两种语义，方便前端直接分组显示。
     */
    private String scopeType;

    /**
     * 被绑定的角色。
     */
    private String actorRole;

    /**
     * 绑定类型。
     */
    private String bindingType;

    /**
     * 绑定值。
     */
    private String bindingValue;

    /**
     * 绑定来源。
     */
    private String bindingSource;

    /**
     * 是否启用。
     */
    private Boolean enabled;

    /**
     * 绑定优先级。
     */
    private Integer priority;

    /**
     * 绑定说明。
     */
    private String note;

    /**
     * 创建人 ID。
     */
    private Long createdBy;

    /**
     * 更新人 ID。
     */
    private Long updatedBy;

    /**
     * 创建时间。
     */
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    private LocalDateTime updateTime;
}

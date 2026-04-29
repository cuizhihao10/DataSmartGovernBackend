package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/24 21:46
 * @Description DataSmart Govern Backend - SyncPermissionBindingReplaceResult.java
 * @Version:1.0.0
 *
 * 权限绑定批量替换结果。
 * 结果里除了返回操作计数，还返回替换后的当前可见绑定清单，
 * 这样前端或运维平台不需要再额外发一轮查询请求才能刷新界面。
 */
@Data
public class SyncPermissionBindingReplaceResult {

    /**
     * 目标租户。
     * 为空时表示本次变更作用于平台全局默认绑定。
     */
    private Long targetTenantId;

    /**
     * 被修改的目标角色。
     */
    private String targetRole;

    /**
     * 本次替换的绑定类型。
     */
    private String bindingType;

    /**
     * 被停用的旧绑定数量。
     */
    private Integer disabledCount;

    /**
     * 新建的绑定数量。
     */
    private Integer createdCount;

    /**
     * 当前对该角色和绑定类型可见的绑定列表。
     * 当目标租户不为空时，这里会同时带回平台全局绑定和租户覆盖绑定，方便理解继承关系。
     */
    private List<SyncPermissionBindingView> activeBindings;

    /**
     * 人类可读摘要。
     */
    private String summary;
}

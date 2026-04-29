package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/20 23:04
 * @Description DataSmart Govern Backend - SyncPermissionPolicySnapshot.java
 * @Version:1.0.0
 *
 * 本地权限策略快照。
 * 这不是最终统一权限中心的完整返回，而是 datasource-management 当前阶段
 * 面向学习、联调和运营排障提供的一份“权限总览”。
 *
 * 快照同时覆盖：
 * - 当前操作者是谁；
 * - 默认数据范围是什么；
 * - 能看到哪些菜单；
 * - 能访问哪些治理路由；
 * - 哪些动作属于管理员动作；
 * - 哪些动作天然更适合审批治理。
 */
@Data
public class SyncPermissionPolicySnapshot {

    /**
     * 操作者 ID。
     */
    private Long actorId;

    /**
     * 操作者角色。
     */
    private String actorRole;

    /**
     * 操作者所属租户。
     */
    private Long actorTenantId;

    /**
     * 当前视角下目标租户。
     * 平台管理员可以切换查看不同租户的策略视角，其他角色通常与 actorTenantId 一致。
     */
    private Long targetTenantId;

    /**
     * 默认数据范围级别。
     */
    private String dataScopeLevel;

    /**
     * 可见菜单清单。
     */
    private List<SyncMenuPolicyView> menus;

    /**
     * 路由策略清单。
     */
    private List<SyncRoutePolicyView> routes;

    /**
     * 管理员动作清单。
     */
    private List<String> adminOnlyActions;

    /**
     * 建议纳入审批治理的动作清单。
     */
    private List<String> approvalRecommendedActions;

    /**
     * 当前快照的人类可读总结。
     */
    private String summary;
}

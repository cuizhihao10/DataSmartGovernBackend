package com.czh.datasmart.govern.datasource.support;

/**
 * @Author : Cui
 * @Date: 2026/4/20 09:18
 * @Description DataSmart Govern Backend - SyncPermissionResource.java
 * @Version:1.0.0
 *
 * 同步域权限资源枚举。
 * 这里不是菜单枚举，而是“后端真正要保护什么”的资源分类。
 *
 * 这样拆的目的有两个：
 * 1. 让当前本地权限矩阵更接近 permission-admin PRD 里“资源 + 动作”的表达方式；
 * 2. 让后续接统一权限中心时，可以把本地资源名直接映射到路由、按钮或数据范围策略。
 */
public enum SyncPermissionResource {
    DATASOURCE_METADATA,
    DATASOURCE_READONLY_QUERY,
    SYNC_TEMPLATE,
    SYNC_TASK,
    SYNC_APPROVAL,
    SYNC_ADMIN,
    SYNC_PERMISSION_POLICY,
    SYNC_QUEUE,
    SYNC_ALERT,
    SYNC_ALERT_DELIVERY,
    SYNC_EXECUTOR
}

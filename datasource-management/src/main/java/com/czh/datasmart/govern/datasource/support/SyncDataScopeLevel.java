package com.czh.datasmart.govern.datasource.support;

/**
 * @Author : Cui
 * @Date: 2026/4/20 23:03
 * @Description DataSmart Govern Backend - SyncDataScopeLevel.java
 * @Version:1.0.0
 *
 * 同步治理域的数据范围级别。
 * 这里不是为了替代未来统一 permission-admin 的完整数据权限模型，
 * 而是先把 datasource-management 已经实际存在的三类边界显式沉淀出来：
 * 1. OWNED：只能处理自己负责或自己创建的对象；
 * 2. TENANT：可以在租户范围内做统一治理；
 * 3. PLATFORM：可以跨租户查看和治理全局对象。
 *
 * 当前它主要被用于：
 * - 权限策略快照返回；
 * - 路由与菜单策略说明；
 * - 后续接入统一权限中心时的本地语义映射。
 */
public enum SyncDataScopeLevel {
    OWNED,
    TENANT,
    PLATFORM
}

package com.czh.datasmart.govern.datasource.support;

import java.util.Arrays;

/**
 * @Author : Cui
 * @Date: 2026/4/24 21:46
 * @Description DataSmart Govern Backend - SyncPermissionBindingType.java
 * @Version:1.0.0
 *
 * 同步权限绑定类型枚举。
 * 这层抽象的目标，是把“角色到底绑定了什么东西”显式分类出来，
 * 这样数据库层、配置层、快照层和未来 permission-admin 对接层都能使用统一语义。
 *
 * 当前先支持五类：
 * 1. `MENU`：菜单可见性绑定。
 * 2. `ROUTE`：路由访问绑定。
 * 3. `DATA_SCOPE`：默认数据范围绑定。
 * 4. `ADMIN_ONLY_ACTION`：管理员专属动作绑定。
 * 5. `APPROVAL_RECOMMENDED_ACTION`：建议纳入审批的高风险动作绑定。
 *
 * 其中 `DATA_SCOPE` 是单值绑定，其余类型天然适合多值列表。
 */
public enum SyncPermissionBindingType {
    MENU(true),
    ROUTE(true),
    DATA_SCOPE(false),
    ADMIN_ONLY_ACTION(true),
    APPROVAL_RECOMMENDED_ACTION(true);

    /**
     * 当前绑定类型是否允许一次同时绑定多个值。
     * 比如菜单和路由通常是列表，而数据范围通常只能有一个默认值。
     */
    private final boolean allowMultipleValues;

    SyncPermissionBindingType(boolean allowMultipleValues) {
        this.allowMultipleValues = allowMultipleValues;
    }

    public boolean isAllowMultipleValues() {
        return allowMultipleValues;
    }

    public static SyncPermissionBindingType fromValue(String value) {
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的权限绑定类型: " + value));
    }
}

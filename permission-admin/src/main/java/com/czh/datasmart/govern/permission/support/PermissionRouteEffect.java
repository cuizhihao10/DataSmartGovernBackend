/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionRouteEffect.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.support;

/**
 * 路由策略效果。
 *
 * <p>企业权限系统通常需要显式区分允许和拒绝。
 * 只有 ALLOW 不够，因为当多个策略同时命中时，安全系统需要表达“显式拒绝优先于允许”的规则。
 */
public enum PermissionRouteEffect {
    /**
     * 允许访问。通常来自角色授权、菜单授权或默认只读策略。
     */
    ALLOW,

    /**
     * 拒绝访问。后续策略冲突时应优先于 ALLOW，避免权限被宽松策略意外放大。
     */
    DENY
}

/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionMatrixView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import com.czh.datasmart.govern.permission.entity.PermissionDataScopePolicy;
import com.czh.datasmart.govern.permission.entity.PermissionMenu;
import com.czh.datasmart.govern.permission.entity.PermissionRole;
import com.czh.datasmart.govern.permission.entity.PermissionRoutePolicy;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 权限矩阵视图。
 *
 * <p>这个对象用于管理端或调试端一次性理解某个租户下的权限配置全貌：
 * 有哪些角色、有哪些菜单、有哪些路由策略、有哪些数据范围策略。
 * 后续可以继续扩展按钮权限、审批策略、服务账号策略和 AI runtime 访问策略。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PermissionMatrixView {

    private Long tenantId;
    private List<PermissionRole> roles;
    private List<PermissionMenu> menus;
    private List<PermissionRoutePolicy> routePolicies;
    private List<PermissionDataScopePolicy> dataScopePolicies;
}

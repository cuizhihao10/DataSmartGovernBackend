package com.czh.datasmart.govern.datasource.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @Author : Cui
 * @Date: 2026/4/24 11:12
 * @Description DataSmart Govern Backend - SyncPermissionPolicyProperties.java
 * @Version:1.0.0
 *
 * 本地权限策略配置。
 * 这一组配置不是为了永久替代独立 permission-admin 模块，
 * 而是为了让 datasource-management 在当前阶段先具备“可配置绑定”的能力，
 * 不至于把菜单、路由、动作建议全部硬编码死在 Java 里。
 *
 * 当前重点覆盖三类绑定：
 * 1. 角色 -> 菜单可见性绑定；
 * 2. 角色 -> 路由可访问绑定；
 * 3. 角色 -> 数据范围覆盖，以及平台级管理员动作/建议审批动作清单。
 *
 * 这样做之后，哪怕后面还没真正引入数据库驱动的权限中心，
 * 也已经可以通过配置模拟不同租户、不同角色和不同运营策略下的权限面板效果。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.datasource.sync-permission-policy")
public class SyncPermissionPolicyProperties {

    /**
     * 角色到菜单编码列表的覆盖绑定。
     * key 使用角色枚举名，value 使用菜单编码，例如：
     * PROJECT_OWNER -> ["sync:template-center", "sync:task-center"]
     */
    private Map<String, List<String>> roleMenuBindings = new LinkedHashMap<>();

    /**
     * 角色到路由策略编码列表的覆盖绑定。
     * value 使用 SyncAdminRoutePolicy 的枚举名，
     * 便于后端直接做强类型映射。
     */
    private Map<String, List<String>> roleRouteBindings = new LinkedHashMap<>();

    /**
     * 角色到数据范围级别的覆盖配置。
     * 这允许在不改代码的前提下，临时把某些角色从 OWNED 提升到 TENANT，
     * 或反过来收紧权限范围。
     */
    private Map<String, String> roleDataScopeBindings = new LinkedHashMap<>();

    /**
     * 管理员动作清单覆盖。
     * 当为空时，系统回落到代码内置默认值。
     */
    private List<String> adminOnlyActions = new ArrayList<>();

    /**
     * 建议走审批的高风险动作清单覆盖。
     * 当为空时，同样回落到代码内置默认值。
     */
    private List<String> approvalRecommendedActions = new ArrayList<>();
}

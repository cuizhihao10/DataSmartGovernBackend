/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionAdminService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service;

import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.PermissionDecisionRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionDecisionResult;
import com.czh.datasmart.govern.permission.controller.dto.PermissionMatrixView;
import com.czh.datasmart.govern.permission.controller.dto.PermissionRoutePolicyEnabledRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionRoutePolicyMutationRequest;
import com.czh.datasmart.govern.permission.entity.PermissionDataScopePolicy;
import com.czh.datasmart.govern.permission.entity.PermissionMenu;
import com.czh.datasmart.govern.permission.entity.PermissionRole;
import com.czh.datasmart.govern.permission.entity.PermissionRoutePolicy;

import java.util.List;

/**
 * 权限管理服务接口。
 *
 * <p>接口层刻意表达“权限中心对外提供哪些能力”，而不是暴露底层 Mapper。
 * 后续如果要把权限策略缓存到 Redis、把变更事件投递到 Kafka、或者引入审批流，
 * Controller 和调用方都不需要知道底层实现细节。
 */
public interface PermissionAdminService {

    /**
     * 查询租户可用角色。
     */
    List<PermissionRole> listRoles(Long tenantId);

    /**
     * 查询指定角色可见菜单。
     */
    List<PermissionMenu> listMenus(Long tenantId, String roleCode);

    /**
     * 查询指定角色的路由策略。
     */
    List<PermissionRoutePolicy> listRoutePolicies(Long tenantId, String roleCode);

    /**
     * 查询路由策略，支持管理后台按角色和启用状态筛选。
     *
     * <p>相比只给 gateway 使用的精确查询，管理后台更需要看到禁用策略、待调整策略和某租户下的全部策略。
     */
    List<PermissionRoutePolicy> listRoutePolicies(Long tenantId, String roleCode, Boolean includeDisabled);

    /**
     * 创建路由策略。
     *
     * <p>这是高风险管理动作，必须记录审计，并且后续应发布权限策略变更事件让 gateway 缓存失效。
     */
    PermissionRoutePolicy createRoutePolicy(PermissionRoutePolicyMutationRequest request, PermissionActorContext actorContext);

    /**
     * 更新路由策略。
     *
     * <p>更新既可能扩大权限，也可能收紧权限，因此同样必须审计。
     */
    PermissionRoutePolicy updateRoutePolicy(Long policyId,
                                            PermissionRoutePolicyMutationRequest request,
                                            PermissionActorContext actorContext);

    /**
     * 启用或禁用路由策略。
     *
     * <p>当前不提供物理删除，避免丢失权限历史和排障证据。
     */
    PermissionRoutePolicy changeRoutePolicyEnabled(Long policyId,
                                                   PermissionRoutePolicyEnabledRequest request,
                                                   PermissionActorContext actorContext);

    /**
     * 查询指定角色和资源类型的数据范围策略。
     */
    List<PermissionDataScopePolicy> listDataScopePolicies(Long tenantId, String roleCode, String resourceType);

    /**
     * 查询权限矩阵总览。
     */
    PermissionMatrixView loadMatrix(Long tenantId);

    /**
     * 判定一次访问是否允许。
     */
    PermissionDecisionResult evaluate(PermissionDecisionRequest request, String traceId);
}

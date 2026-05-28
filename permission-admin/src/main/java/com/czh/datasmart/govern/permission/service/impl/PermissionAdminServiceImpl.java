/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionAdminServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

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
import com.czh.datasmart.govern.permission.service.PermissionAdminService;
import com.czh.datasmart.govern.permission.service.support.PermissionDecisionSupport;
import com.czh.datasmart.govern.permission.service.support.PermissionQuerySupport;
import com.czh.datasmart.govern.permission.service.support.PermissionRoutePolicyMutationSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 权限管理服务实现门面。
 *
 * <p>这个类现在只承担三类责任：
 * 1. 实现 `PermissionAdminService` 对外契约；
 * 2. 标注需要事务保护的策略变更和访问判定方法；
 * 3. 把具体工作委托给查询、判定、策略变更三个 support 组件。
 *
 * <p>这样拆分后，permission-admin 的核心边界更接近商业化权限中心：
 * 权限事实查询、授权判定、策略变更、审计写入和事件发布不再混杂在一个 Impl 中。
 * 后续接入 Redis 授权缓存、Kafka 缓存失效、审批流、租户级数据范围和审计中心时，
 * 可以分别增强对应组件，而不是继续让主服务膨胀。
 */
@Service
@RequiredArgsConstructor
public class PermissionAdminServiceImpl implements PermissionAdminService {

    /**
     * 权限事实查询组件。
     */
    private final PermissionQuerySupport querySupport;

    /**
     * 访问判定组件。
     */
    private final PermissionDecisionSupport decisionSupport;

    /**
     * 路由策略变更组件。
     */
    private final PermissionRoutePolicyMutationSupport routePolicyMutationSupport;

    @Override
    public List<PermissionRole> listRoles(Long tenantId) {
        return querySupport.listRoles(tenantId);
    }

    @Override
    public List<PermissionMenu> listMenus(Long tenantId, String roleCode) {
        return querySupport.listMenus(tenantId, roleCode);
    }

    @Override
    public List<PermissionRoutePolicy> listRoutePolicies(Long tenantId, String roleCode) {
        return querySupport.listRoutePolicies(tenantId, roleCode);
    }

    @Override
    public List<PermissionRoutePolicy> listRoutePolicies(Long tenantId, String roleCode, Boolean includeDisabled) {
        return querySupport.listRoutePolicies(tenantId, roleCode, includeDisabled);
    }

    @Override
    @Transactional
    public PermissionRoutePolicy createRoutePolicy(PermissionRoutePolicyMutationRequest request,
                                                   PermissionActorContext actorContext) {
        return routePolicyMutationSupport.createRoutePolicy(request, actorContext);
    }

    @Override
    @Transactional
    public PermissionRoutePolicy updateRoutePolicy(Long policyId,
                                                   PermissionRoutePolicyMutationRequest request,
                                                   PermissionActorContext actorContext) {
        return routePolicyMutationSupport.updateRoutePolicy(policyId, request, actorContext);
    }

    @Override
    @Transactional
    public PermissionRoutePolicy changeRoutePolicyEnabled(Long policyId,
                                                          PermissionRoutePolicyEnabledRequest request,
                                                          PermissionActorContext actorContext) {
        return routePolicyMutationSupport.changeRoutePolicyEnabled(policyId, request, actorContext);
    }

    @Override
    public List<PermissionDataScopePolicy> listDataScopePolicies(Long tenantId, String roleCode, String resourceType) {
        return querySupport.listDataScopePolicies(tenantId, roleCode, resourceType);
    }

    @Override
    public PermissionMatrixView loadMatrix(Long tenantId) {
        return querySupport.loadMatrix(tenantId);
    }

    @Override
    @Transactional
    public PermissionDecisionResult evaluate(PermissionDecisionRequest request, String traceId) {
        return decisionSupport.evaluate(request, traceId);
    }
}

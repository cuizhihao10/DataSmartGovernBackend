package com.czh.datasmart.govern.permission.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.PermissionRoutePolicyEnabledRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionRoutePolicyMutationRequest;
import com.czh.datasmart.govern.permission.entity.PermissionRole;
import com.czh.datasmart.govern.permission.entity.PermissionRoutePolicy;
import com.czh.datasmart.govern.permission.event.PermissionPolicyChangedEventPublisher;
import com.czh.datasmart.govern.permission.mapper.PermissionRoleMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionRoutePolicyMapper;
import com.czh.datasmart.govern.permission.support.PermissionRoleCode;
import com.czh.datasmart.govern.permission.support.PermissionRouteEffect;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.isPlatformTenant;
import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.normalizeCode;
import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.normalizeTenantId;
import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.platformAndTenantIds;
import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.valueForAudit;

/**
 * @Author : Cui
 * @Date: 2026/05/06 00:22
 * @Description DataSmart Govern Backend - PermissionRoutePolicyMutationSupport.java
 * @Version:1.0.0
 *
 * 路由权限策略变更支持组件。
 *
 * <p>路由策略是 gateway 和后端服务共同依赖的安全配置。
 * 创建、更新、启停策略都可能扩大或收紧系统权限，因此本组件统一处理：
 * 1. 操作者是否有权管理目标租户策略；
 * 2. 策略字段归一化；
 * 3. 角色存在性、路径格式、效果枚举、重复策略等业务规则；
 * 4. before/after 审计；
 * 5. 权限策略变更事件发布，用于后续网关缓存失效。
 */
@Component
@RequiredArgsConstructor
public class PermissionRoutePolicyMutationSupport {

    private static final String AUDIT_RESULT_SUCCESS = "SUCCESS";
    private static final String AUDIT_RESULT_FAILED = "FAILED";
    private static final String EVENT_ROUTE_POLICY_CREATED = "ROUTE_POLICY_CREATED";
    private static final String EVENT_ROUTE_POLICY_UPDATED = "ROUTE_POLICY_UPDATED";
    private static final String EVENT_ROUTE_POLICY_ENABLED = "ROUTE_POLICY_ENABLED";
    private static final String EVENT_ROUTE_POLICY_DISABLED = "ROUTE_POLICY_DISABLED";

    private final PermissionRoleMapper roleMapper;
    private final PermissionRoutePolicyMapper routePolicyMapper;
    private final PermissionAuditSupport auditSupport;
    private final PermissionPolicyChangedEventPublisher policyChangedEventPublisher;
    private final PermissionPolicyFactCache policyFactCache;

    /**
     * 创建路由策略。
     *
     * <p>策略创建成功后发布权限变更事件，让 gateway 或其他缓存节点可以失效旧策略。
     * 当前事件发布器已经采用 outbox 思路，避免策略事务提交成功但消息丢失。
     */
    public PermissionRoutePolicy createRoutePolicy(PermissionRoutePolicyMutationRequest request,
                                                   PermissionActorContext actorContext) {
        PermissionRoutePolicy policy = buildRoutePolicy(request);
        try {
            validateRoutePolicyMutationPermission(actorContext, policy.getTenantId());
            validateRoutePolicyBusinessRules(policy, null);

            LocalDateTime now = LocalDateTime.now();
            policy.setCreateTime(now);
            policy.setUpdateTime(now);
            routePolicyMapper.insert(policy);
            auditSupport.saveRoutePolicyMutationAudit(actorContext, "CREATE_ROUTE_POLICY", AUDIT_RESULT_SUCCESS,
                    "创建路由策略: " + policy.getPolicyName(), policy, null);
            policyChangedEventPublisher.publishRoutePolicyChanged(EVENT_ROUTE_POLICY_CREATED, policy, actorContext,
                    "创建路由策略: " + policy.getPolicyName());
            policyFactCache.evictTenantAfterCommit(policy.getTenantId(), "route-policy-created:" + policy.getId());
            return policy;
        } catch (RuntimeException exception) {
            auditSupport.saveRoutePolicyMutationAudit(actorContext, "CREATE_ROUTE_POLICY", AUDIT_RESULT_FAILED,
                    "创建路由策略失败: " + exception.getMessage(), policy, null);
            throw exception;
        }
    }

    /**
     * 更新路由策略。
     *
     * <p>更新时同时校验原策略租户和目标策略租户，防止租户管理员借更新接口把策略迁移到其他租户。
     */
    public PermissionRoutePolicy updateRoutePolicy(Long policyId,
                                                   PermissionRoutePolicyMutationRequest request,
                                                   PermissionActorContext actorContext) {
        PermissionRoutePolicy existingPolicy = findRoutePolicyOrThrow(policyId);
        PermissionRoutePolicy updatedPolicy = buildRoutePolicy(request);
        updatedPolicy.setId(policyId);
        updatedPolicy.setCreateTime(existingPolicy.getCreateTime());

        try {
            validateRoutePolicyMutationPermission(actorContext, existingPolicy.getTenantId());
            validateRoutePolicyMutationPermission(actorContext, updatedPolicy.getTenantId());
            validateRoutePolicyBusinessRules(updatedPolicy, policyId);

            updatedPolicy.setUpdateTime(LocalDateTime.now());
            routePolicyMapper.updateById(updatedPolicy);
            auditSupport.saveRoutePolicyMutationAudit(actorContext, "UPDATE_ROUTE_POLICY", AUDIT_RESULT_SUCCESS,
                    "更新路由策略: " + updatedPolicy.getPolicyName(), updatedPolicy, existingPolicy);
            PermissionRoutePolicy refreshedPolicy = findRoutePolicyOrThrow(policyId);
            policyChangedEventPublisher.publishRoutePolicyChanged(EVENT_ROUTE_POLICY_UPDATED, refreshedPolicy, actorContext,
                    "更新路由策略: " + refreshedPolicy.getPolicyName());
            policyFactCache.evictTenantAfterCommit(existingPolicy.getTenantId(), "route-policy-updated-before:" + policyId);
            if (!existingPolicy.getTenantId().equals(refreshedPolicy.getTenantId())) {
                policyFactCache.evictTenantAfterCommit(refreshedPolicy.getTenantId(), "route-policy-updated-after:" + policyId);
            }
            return refreshedPolicy;
        } catch (RuntimeException exception) {
            auditSupport.saveRoutePolicyMutationAudit(actorContext, "UPDATE_ROUTE_POLICY", AUDIT_RESULT_FAILED,
                    "更新路由策略失败: " + exception.getMessage(), updatedPolicy, existingPolicy);
            throw exception;
        }
    }

    /**
     * 启用或禁用路由策略。
     *
     * <p>这里不做物理删除，是为了保留策略历史和审计证据。
     */
    public PermissionRoutePolicy changeRoutePolicyEnabled(Long policyId,
                                                          PermissionRoutePolicyEnabledRequest request,
                                                          PermissionActorContext actorContext) {
        PermissionRoutePolicy policy = findRoutePolicyOrThrow(policyId);
        PermissionRoutePolicy before = cloneRoutePolicy(policy);
        try {
            validateRoutePolicyMutationPermission(actorContext, policy.getTenantId());
            policy.setEnabled(request.getEnabled());
            policy.setUpdateTime(LocalDateTime.now());
            routePolicyMapper.updateById(policy);
            boolean enabled = Boolean.TRUE.equals(request.getEnabled());
            policyFactCache.evictTenantAfterCommit(policy.getTenantId(), "route-policy-enabled-change:" + policyId);
            auditSupport.saveRoutePolicyMutationAudit(actorContext,
                    enabled ? "ENABLE_ROUTE_POLICY" : "DISABLE_ROUTE_POLICY",
                    AUDIT_RESULT_SUCCESS,
                    (enabled ? "启用" : "禁用") + "路由策略: " + policy.getPolicyName()
                            + valueForAudit(", reason=", request.getReason()),
                    policy,
                    before);
            PermissionRoutePolicy refreshedPolicy = findRoutePolicyOrThrow(policyId);
            policyChangedEventPublisher.publishRoutePolicyChanged(
                    enabled ? EVENT_ROUTE_POLICY_ENABLED : EVENT_ROUTE_POLICY_DISABLED,
                    refreshedPolicy,
                    actorContext,
                    (enabled ? "启用" : "禁用") + "路由策略: " + refreshedPolicy.getPolicyName());
            return refreshedPolicy;
        } catch (RuntimeException exception) {
            auditSupport.saveRoutePolicyMutationAudit(actorContext,
                    Boolean.TRUE.equals(request.getEnabled()) ? "ENABLE_ROUTE_POLICY" : "DISABLE_ROUTE_POLICY",
                    AUDIT_RESULT_FAILED,
                    "启停路由策略失败: " + exception.getMessage(),
                    policy,
                    before);
            throw exception;
        }
    }

    private PermissionRoutePolicy buildRoutePolicy(PermissionRoutePolicyMutationRequest request) {
        PermissionRoutePolicy policy = new PermissionRoutePolicy();
        policy.setTenantId(normalizeTenantId(request.getTenantId()));
        policy.setPolicyName(request.getPolicyName().trim());
        policy.setRoleCode(normalizeCode(request.getRoleCode()));
        policy.setHttpMethod(normalizeCode(request.getHttpMethod()));
        policy.setPathPattern(request.getPathPattern().trim());
        policy.setResourceType(normalizeOptionalCode(request.getResourceType()));
        policy.setAction(normalizeOptionalCode(request.getAction()));
        policy.setEffect(normalizeCode(request.getEffect()));
        policy.setPriority(request.getPriority() == null ? 100 : request.getPriority());
        policy.setEnabled(request.getEnabled() == null || request.getEnabled());
        policy.setDescription(request.getDescription() == null ? null : request.getDescription().trim());
        return policy;
    }

    /**
     * 校验当前操作者是否可以修改目标租户策略。
     *
     * <p>当前第一版规则：
     * 平台管理员可管理全局和任意租户策略；
     * 租户管理员只能管理自己租户的非全局策略；
     * 普通用户、项目负责人、运营、审计员、服务账号默认不能直接修改路由策略。
     */
    private void validateRoutePolicyMutationPermission(PermissionActorContext actorContext, Long targetTenantId) {
        if (actorContext == null || actorContext.actorRole() == null || actorContext.actorRole().isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, "缺少可信操作角色，不能修改路由策略");
        }

        String actorRole = normalizeCode(actorContext.actorRole());
        Long actorTenantId = normalizeTenantId(actorContext.tenantId());
        Long normalizedTargetTenantId = normalizeTenantId(targetTenantId);

        if (PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(actorRole)) {
            return;
        }

        if (PermissionRoleCode.TENANT_ADMINISTRATOR.name().equals(actorRole)
                && !isPlatformTenant(normalizedTargetTenantId)
                && normalizedTargetTenantId.equals(actorTenantId)) {
            return;
        }

        throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                "当前角色无权修改目标租户的路由策略，actorRole=" + actorRole + ", targetTenantId=" + normalizedTargetTenantId);
    }

    private void validateRoutePolicyBusinessRules(PermissionRoutePolicy policy, Long ignoredPolicyId) {
        if (!policy.getPathPattern().startsWith("/")) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "pathPattern 必须以 / 开头");
        }
        if (!PermissionRouteEffect.ALLOW.name().equals(policy.getEffect())
                && !PermissionRouteEffect.DENY.name().equals(policy.getEffect())) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "effect 必须是 ALLOW 或 DENY");
        }
        if (!roleExists(policy.getTenantId(), policy.getRoleCode())) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "路由策略绑定的角色不存在或未启用: " + policy.getRoleCode());
        }

        LambdaQueryWrapper<PermissionRoutePolicy> duplicateWrapper = new LambdaQueryWrapper<PermissionRoutePolicy>()
                .eq(PermissionRoutePolicy::getTenantId, policy.getTenantId())
                .eq(PermissionRoutePolicy::getRoleCode, policy.getRoleCode())
                .eq(PermissionRoutePolicy::getHttpMethod, policy.getHttpMethod())
                .eq(PermissionRoutePolicy::getPathPattern, policy.getPathPattern())
                .eq(PermissionRoutePolicy::getEffect, policy.getEffect());
        nullSafeEq(duplicateWrapper, PermissionRoutePolicy::getResourceType, policy.getResourceType());
        nullSafeEq(duplicateWrapper, PermissionRoutePolicy::getAction, policy.getAction());
        if (ignoredPolicyId != null) {
            duplicateWrapper.ne(PermissionRoutePolicy::getId, ignoredPolicyId);
        }

        Long duplicateCount = routePolicyMapper.selectCount(duplicateWrapper);
        if (duplicateCount != null && duplicateCount > 0) {
            throw new PlatformBusinessException(PlatformErrorCode.DUPLICATE_OPERATION,
                    "同一租户、角色、方法、路径和效果下已存在路由策略");
        }
    }

    private boolean roleExists(Long tenantId, String roleCode) {
        Long count = roleMapper.selectCount(new LambdaQueryWrapper<PermissionRole>()
                .in(PermissionRole::getTenantId, platformAndTenantIds(tenantId))
                .eq(PermissionRole::getRoleCode, roleCode)
                .eq(PermissionRole::getEnabled, true));
        return count != null && count > 0;
    }

    private PermissionRoutePolicy findRoutePolicyOrThrow(Long policyId) {
        PermissionRoutePolicy policy = routePolicyMapper.selectById(policyId);
        if (policy == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "路由策略不存在: " + policyId);
        }
        return policy;
    }

    private PermissionRoutePolicy cloneRoutePolicy(PermissionRoutePolicy source) {
        PermissionRoutePolicy target = new PermissionRoutePolicy();
        target.setId(source.getId());
        target.setTenantId(source.getTenantId());
        target.setPolicyName(source.getPolicyName());
        target.setRoleCode(source.getRoleCode());
        target.setHttpMethod(source.getHttpMethod());
        target.setPathPattern(source.getPathPattern());
        target.setResourceType(source.getResourceType());
        target.setAction(source.getAction());
        target.setEffect(source.getEffect());
        target.setPriority(source.getPriority());
        target.setEnabled(source.getEnabled());
        target.setDescription(source.getDescription());
        target.setCreateTime(source.getCreateTime());
        target.setUpdateTime(source.getUpdateTime());
        return target;
    }

    /**
     * 规范化可选编码字段。
     *
     * <p>resourceType/action 允许为空以兼容旧式路径级策略；但只要用户传了值，就统一转成大写编码。
     * 这样 gateway 传入 `callback`、`Callback` 或 `CALLBACK` 时都可以稳定命中数据库中的 `CALLBACK`。
     */
    private String normalizeOptionalCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return normalizeCode(value);
    }

    /**
     * 针对可空字段构建重复策略查询。
     *
     * <p>SQL 三值逻辑中 `NULL = NULL` 不成立，如果直接使用 eq(null) 容易漏掉历史通配策略。
     * 这里显式使用 `IS NULL`，保证“同一租户、角色、方法、路径、资源、动作、效果”维度下不会重复。
     */
    private void nullSafeEq(LambdaQueryWrapper<PermissionRoutePolicy> wrapper,
                            com.baomidou.mybatisplus.core.toolkit.support.SFunction<PermissionRoutePolicy, ?> column,
                            String value) {
        if (value == null || value.isBlank()) {
            wrapper.isNull(column);
        } else {
            wrapper.eq(column, value);
        }
    }
}

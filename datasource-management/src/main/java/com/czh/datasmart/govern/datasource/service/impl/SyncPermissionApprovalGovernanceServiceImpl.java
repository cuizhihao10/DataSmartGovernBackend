package com.czh.datasmart.govern.datasource.service.impl;

import com.czh.datasmart.govern.datasource.config.SyncPermissionApprovalProperties;
import com.czh.datasmart.govern.datasource.entity.SyncPermissionApprovalDelegateRule;
import com.czh.datasmart.govern.datasource.entity.SyncPermissionPolicyChangeRequest;
import com.czh.datasmart.govern.datasource.service.SyncPermissionApprovalDelegateRuleService;
import com.czh.datasmart.govern.datasource.service.SyncPermissionApprovalGovernanceService;
import com.czh.datasmart.govern.datasource.support.ActorRole;
import com.czh.datasmart.govern.datasource.support.SyncPermissionAction;
import com.czh.datasmart.govern.datasource.support.SyncPermissionApprovalDecision;
import com.czh.datasmart.govern.datasource.support.SyncPermissionApprovalMode;
import com.czh.datasmart.govern.datasource.support.SyncPermissionContext;
import com.czh.datasmart.govern.datasource.support.SyncPermissionEvaluator;
import com.czh.datasmart.govern.datasource.support.SyncPermissionResource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * @Author : Cui
 * @Date: 2026/4/24 23:18
 * @Description DataSmart Govern Backend - SyncPermissionApprovalGovernanceServiceImpl.java
 * @Version:1.0.0
 *
 * 权限审批治理服务实现。
 * 这一层专门把审批矩阵解释逻辑从具体申请单服务里拆出来，避免“提交流程”和“审批资格决策”耦合在一起。
 *
 * 当前的审批资格判定顺序如下：
 * 1. 先检查当前角色是否具备基础审批动作权限；
 * 2. 再检查是否命中了申请单记录下来的审批角色快照；
 * 3. 如果没有直接命中，则继续检查是否存在有效的审批委托规则；
 * 4. 如果开启了防自审，还会在更早阶段阻止申请人自己审批自己的申请。
 *
 * 这种拆法的好处是，后续无论接配置中心、数据库审批矩阵还是独立工作流引擎，
 * 都只需要演进这一层的“审批资格解析器”，而不是回到每个业务服务里散改 if/else。
 */
@Service
@RequiredArgsConstructor
public class SyncPermissionApprovalGovernanceServiceImpl implements SyncPermissionApprovalGovernanceService {

    private final SyncPermissionApprovalProperties approvalProperties;
    private final SyncPermissionApprovalDelegateRuleService delegateRuleService;
    private final SyncPermissionEvaluator syncPermissionEvaluator;
    private final ObjectMapper objectMapper;

    @Override
    public List<String> resolveRequiredApproverRoles(Long targetTenantId, String bindingType, String requesterRole) {
        LinkedHashSet<String> requiredRoles = new LinkedHashSet<>(resolveScopeBasedRoles(targetTenantId));

        List<String> bindingTypeRoles = readConfiguredRoles(
                approvalProperties.getBindingTypeApproverRoles().get(normalizeKey(bindingType)));
        if (!bindingTypeRoles.isEmpty()) {
            requiredRoles = new LinkedHashSet<>(bindingTypeRoles);
        }

        List<String> escalationRoles = readConfiguredRoles(
                approvalProperties.getRequesterRoleEscalationApproverRoles().get(normalizeKey(requesterRole)));
        if (!escalationRoles.isEmpty()) {
            LinkedHashSet<String> intersection = new LinkedHashSet<>(requiredRoles);
            intersection.retainAll(escalationRoles);
            requiredRoles = intersection.isEmpty() ? new LinkedHashSet<>(escalationRoles) : intersection;
        }

        if (requiredRoles.isEmpty()) {
            throw new IllegalStateException("No approver role was resolved from sync-permission-approval configuration");
        }
        return List.copyOf(requiredRoles);
    }

    @Override
    public SyncPermissionApprovalDecision assertCanApprove(SyncPermissionPolicyChangeRequest entity,
                                                           Long actorId,
                                                           String actorRole,
                                                           Long actorTenantId) {
        SyncPermissionContext context = SyncPermissionContext.builder()
                .actorId(actorId)
                .actorRole(actorRole)
                .actorTenantId(actorTenantId)
                .resourceTenantId(fromStoredTenantId(entity.getTargetTenantId()))
                .build();
        syncPermissionEvaluator.assertAllowed(context,
                SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.APPROVE);

        if (!approvalProperties.isAllowSelfApproval() && Objects.equals(actorId, entity.getRequesterId())) {
            throw new IllegalStateException("Current governance policy forbids self approval for permission policy change requests");
        }

        List<String> requiredApproverRoles = resolveStoredOrLiveRequiredRoles(entity);
        String normalizedActorRole = ActorRole.fromValue(actorRole).name();
        if (requiredApproverRoles.contains(normalizedActorRole)) {
            return SyncPermissionApprovalDecision.builder()
                    .approvalMode(SyncPermissionApprovalMode.DIRECT_ROLE)
                    .requiredApproverRoles(requiredApproverRoles)
                    .build();
        }

        if (!approvalProperties.isDelegateApprovalEnabled()) {
            throw new IllegalStateException("Current approver did not match required roles and delegate approval is disabled");
        }

        List<SyncPermissionApprovalDelegateRule> delegateRules = delegateRuleService.findEffectiveDelegateRules(
                fromStoredTenantId(entity.getTargetTenantId()),
                actorId,
                actorRole,
                LocalDateTime.now());
        for (SyncPermissionApprovalDelegateRule rule : delegateRules) {
            if (requiredApproverRoles.contains(rule.getDelegatorRole())) {
                return SyncPermissionApprovalDecision.builder()
                        .approvalMode(SyncPermissionApprovalMode.DELEGATED_ROLE)
                        .requiredApproverRoles(requiredApproverRoles)
                        .delegatedFromApproverId(rule.getDelegatorId())
                        .delegatedFromApproverRole(rule.getDelegatorRole())
                        .build();
            }
        }

        throw new IllegalStateException("Current approver matches neither direct approval matrix nor effective delegate rules");
    }

    private List<String> resolveStoredOrLiveRequiredRoles(SyncPermissionPolicyChangeRequest entity) {
        if (entity.getRequiredApproverRolesJson() != null && !entity.getRequiredApproverRolesJson().isBlank()) {
            try {
                return objectMapper.readValue(entity.getRequiredApproverRolesJson(), new TypeReference<>() {
                });
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("requiredApproverRoles deserialize failed", exception);
            }
        }
        return resolveRequiredApproverRoles(
                fromStoredTenantId(entity.getTargetTenantId()),
                entity.getBindingType(),
                entity.getRequesterRole());
    }

    private List<String> resolveScopeBasedRoles(Long targetTenantId) {
        List<String> configuredRoles = targetTenantId == null
                ? approvalProperties.getPlatformGlobalApproverRoles()
                : approvalProperties.getTenantScopedApproverRoles();
        List<String> normalizedConfiguredRoles = readConfiguredRoles(configuredRoles);
        if (!normalizedConfiguredRoles.isEmpty()) {
            return normalizedConfiguredRoles;
        }
        return targetTenantId == null
                ? List.of(ActorRole.PLATFORM_ADMINISTRATOR.name())
                : List.of(ActorRole.TENANT_ADMINISTRATOR.name(), ActorRole.PLATFORM_ADMINISTRATOR.name());
    }

    private List<String> readConfiguredRoles(List<String> configuredRoles) {
        if (configuredRoles == null) {
            return List.of();
        }
        Set<String> normalizedRoles = new LinkedHashSet<>();
        for (String role : configuredRoles) {
            if (role == null || role.isBlank()) {
                continue;
            }
            normalizedRoles.add(ActorRole.fromValue(role).name());
        }
        return List.copyOf(normalizedRoles);
    }

    private String normalizeKey(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    private Long fromStoredTenantId(Long tenantId) {
        return Objects.equals(tenantId, 0L) ? null : tenantId;
    }
}

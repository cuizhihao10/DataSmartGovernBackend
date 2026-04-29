package com.czh.datasmart.govern.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.datasource.controller.dto.CreateSyncPermissionApprovalDelegateRuleRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionApprovalDelegateRuleView;
import com.czh.datasmart.govern.datasource.entity.SyncAuditRecord;
import com.czh.datasmart.govern.datasource.entity.SyncPermissionApprovalDelegateRule;
import com.czh.datasmart.govern.datasource.mapper.SyncAuditRecordMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncPermissionApprovalDelegateRuleMapper;
import com.czh.datasmart.govern.datasource.service.SyncPermissionApprovalDelegateRuleService;
import com.czh.datasmart.govern.datasource.support.ActorRole;
import com.czh.datasmart.govern.datasource.support.SyncAuditAction;
import com.czh.datasmart.govern.datasource.support.SyncPermissionAction;
import com.czh.datasmart.govern.datasource.support.SyncPermissionContext;
import com.czh.datasmart.govern.datasource.support.SyncPermissionEvaluator;
import com.czh.datasmart.govern.datasource.support.SyncPermissionResource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * @Author : Cui
 * @Date: 2026/4/24 23:18
 * @Description DataSmart Govern Backend - SyncPermissionApprovalDelegateRuleServiceImpl.java
 * @Version:1.0.0
 *
 * 权限审批委托规则服务实现。
 * 这一层把“谁可以配置委托、哪些委托是有效的、哪些委托需要被审计”收敛成明确流程。
 *
 * 设计重点：
 * 1. 委托规则是高风险治理对象，因此创建和禁用都必须走显式权限校验；
 * 2. 委托不是泛化代理，而是限定在权限审批场景，避免语义漂移；
 * 3. 所有关键动作都会写审计，便于后续回答“谁授权了谁代批权限策略”。
 */
@Service
@RequiredArgsConstructor
public class SyncPermissionApprovalDelegateRuleServiceImpl implements SyncPermissionApprovalDelegateRuleService {

    private static final long PLATFORM_SCOPE_TENANT_ID = 0L;

    private final SyncPermissionApprovalDelegateRuleMapper delegateRuleMapper;
    private final SyncPermissionEvaluator syncPermissionEvaluator;
    private final SyncAuditRecordMapper syncAuditRecordMapper;

    @Override
    @Transactional
    public SyncPermissionApprovalDelegateRuleView createDelegateRule(CreateSyncPermissionApprovalDelegateRuleRequest request) {
        Long resolvedTargetTenantId = resolveReadableTargetTenantId(
                request.getActorRole(), request.getActorTenantId(), request.getTargetTenantId());
        SyncPermissionContext context = buildContext(
                request.getActorId(), request.getActorRole(), request.getActorTenantId(), resolvedTargetTenantId);
        syncPermissionEvaluator.assertAllowed(context,
                SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.MANAGE_POLICY);

        ActorRole delegatorRole = ActorRole.fromValue(request.getDelegatorRole());
        ActorRole delegateRole = ActorRole.fromValue(request.getDelegateRole());

        validateDelegateRuleRequest(request, resolvedTargetTenantId, delegatorRole, delegateRole);
        ensureNoDuplicatedEnabledRule(resolvedTargetTenantId, request.getDelegatorId(),
                delegatorRole.name(), request.getDelegateId(), delegateRole.name());

        SyncPermissionApprovalDelegateRule entity = new SyncPermissionApprovalDelegateRule();
        entity.setTargetTenantId(toStoredTenantId(resolvedTargetTenantId));
        entity.setDelegatorId(request.getDelegatorId());
        entity.setDelegatorRole(delegatorRole.name());
        entity.setDelegateId(request.getDelegateId());
        entity.setDelegateRole(delegateRole.name());
        entity.setEffectiveFrom(request.getEffectiveFrom());
        entity.setEffectiveTo(request.getEffectiveTo());
        entity.setEnabled(Boolean.TRUE);
        entity.setDelegateReason(request.getDelegateReason());
        entity.setCreatedBy(request.getActorId());
        entity.setUpdatedBy(request.getActorId());
        delegateRuleMapper.insert(entity);

        recordAudit(resolvedTargetTenantId,
                SyncAuditAction.CREATE_PERMISSION_APPROVAL_DELEGATE_RULE,
                request.getActorId(),
                request.getActorRole(),
                buildPayload(
                        "delegateRuleId", entity.getId(),
                        "delegatorId", entity.getDelegatorId(),
                        "delegatorRole", entity.getDelegatorRole(),
                        "delegateId", entity.getDelegateId(),
                        "delegateRole", entity.getDelegateRole(),
                        "targetTenantId", resolvedTargetTenantId,
                        "delegateReason", entity.getDelegateReason()
                ));
        return toView(entity);
    }

    @Override
    public List<SyncPermissionApprovalDelegateRuleView> listDelegateRules(Long actorId,
                                                                          String actorRole,
                                                                          Long actorTenantId,
                                                                          Long targetTenantId,
                                                                          Long delegatorId,
                                                                          Long delegateId,
                                                                          Boolean activeOnly) {
        Long resolvedTargetTenantId = resolveReadableTargetTenantId(actorRole, actorTenantId, targetTenantId);
        SyncPermissionContext context = buildContext(actorId, actorRole, actorTenantId, resolvedTargetTenantId);
        syncPermissionEvaluator.assertAllowed(context,
                SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.VIEW_POLICY);

        LambdaQueryWrapper<SyncPermissionApprovalDelegateRule> wrapper =
                new LambdaQueryWrapper<SyncPermissionApprovalDelegateRule>()
                        .orderByDesc(SyncPermissionApprovalDelegateRule::getCreateTime)
                        .orderByDesc(SyncPermissionApprovalDelegateRule::getId);

        if (resolvedTargetTenantId == null) {
            wrapper.eq(SyncPermissionApprovalDelegateRule::getTargetTenantId, PLATFORM_SCOPE_TENANT_ID);
        } else {
            wrapper.eq(SyncPermissionApprovalDelegateRule::getTargetTenantId, toStoredTenantId(resolvedTargetTenantId));
        }
        if (delegatorId != null) {
            wrapper.eq(SyncPermissionApprovalDelegateRule::getDelegatorId, delegatorId);
        }
        if (delegateId != null) {
            wrapper.eq(SyncPermissionApprovalDelegateRule::getDelegateId, delegateId);
        }

        List<SyncPermissionApprovalDelegateRuleView> rules = delegateRuleMapper.selectList(wrapper).stream()
                .map(this::toView)
                .toList();
        if (!Boolean.TRUE.equals(activeOnly)) {
            return rules;
        }
        LocalDateTime now = LocalDateTime.now();
        return rules.stream()
                .filter(rule -> Boolean.TRUE.equals(rule.getEnabled()))
                .filter(rule -> rule.getEffectiveFrom() == null || !rule.getEffectiveFrom().isAfter(now))
                .filter(rule -> rule.getEffectiveTo() == null || !rule.getEffectiveTo().isBefore(now))
                .toList();
    }

    @Override
    @Transactional
    public SyncPermissionApprovalDelegateRuleView disableDelegateRule(Long ruleId, SyncActionRequest request) {
        SyncPermissionApprovalDelegateRule entity = getRequiredRule(ruleId);
        Long targetTenantId = fromStoredTenantId(entity.getTargetTenantId());
        SyncPermissionContext context = buildContext(
                request.getActorId(), request.getActorRole(), request.getActorTenantId(), targetTenantId);
        syncPermissionEvaluator.assertAllowed(context,
                SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.MANAGE_POLICY);

        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            throw new IllegalStateException("Approval delegate rule is already disabled");
        }

        entity.setEnabled(Boolean.FALSE);
        entity.setUpdatedBy(request.getActorId());
        delegateRuleMapper.updateById(entity);

        recordAudit(targetTenantId,
                SyncAuditAction.DISABLE_PERMISSION_APPROVAL_DELEGATE_RULE,
                request.getActorId(),
                request.getActorRole(),
                buildPayload(
                        "delegateRuleId", entity.getId(),
                        "delegatorId", entity.getDelegatorId(),
                        "delegateId", entity.getDelegateId(),
                        "note", request.getNote()
                ));
        return toView(entity);
    }

    @Override
    public List<SyncPermissionApprovalDelegateRule> findEffectiveDelegateRules(Long targetTenantId,
                                                                               Long delegateId,
                                                                               String delegateRole,
                                                                               LocalDateTime decisionTime) {
        ActorRole.fromValue(delegateRole);
        LocalDateTime resolvedDecisionTime = decisionTime == null ? LocalDateTime.now() : decisionTime;

        LambdaQueryWrapper<SyncPermissionApprovalDelegateRule> wrapper =
                new LambdaQueryWrapper<SyncPermissionApprovalDelegateRule>()
                        .eq(SyncPermissionApprovalDelegateRule::getDelegateId, delegateId)
                        .eq(SyncPermissionApprovalDelegateRule::getDelegateRole, ActorRole.fromValue(delegateRole).name())
                        .eq(SyncPermissionApprovalDelegateRule::getEnabled, Boolean.TRUE)
                        .and(condition -> condition
                                .eq(SyncPermissionApprovalDelegateRule::getTargetTenantId, PLATFORM_SCOPE_TENANT_ID)
                                .or()
                                .eq(SyncPermissionApprovalDelegateRule::getTargetTenantId, toStoredTenantId(targetTenantId)))
                        .and(condition -> condition
                                .isNull(SyncPermissionApprovalDelegateRule::getEffectiveFrom)
                                .or()
                                .le(SyncPermissionApprovalDelegateRule::getEffectiveFrom, resolvedDecisionTime))
                        .and(condition -> condition
                                .isNull(SyncPermissionApprovalDelegateRule::getEffectiveTo)
                                .or()
                                .ge(SyncPermissionApprovalDelegateRule::getEffectiveTo, resolvedDecisionTime))
                        .orderByDesc(SyncPermissionApprovalDelegateRule::getTargetTenantId)
                        .orderByDesc(SyncPermissionApprovalDelegateRule::getCreateTime);
        return delegateRuleMapper.selectList(wrapper);
    }

    private void validateDelegateRuleRequest(CreateSyncPermissionApprovalDelegateRuleRequest request,
                                             Long resolvedTargetTenantId,
                                             ActorRole delegatorRole,
                                             ActorRole delegateRole) {
        ActorRole actingRole = ActorRole.fromValue(request.getActorRole());
        if (Objects.equals(request.getDelegatorId(), request.getDelegateId())) {
            throw new IllegalStateException("Approval delegate rule cannot delegate approval privilege to self");
        }
        if (actingRole != ActorRole.PLATFORM_ADMINISTRATOR
                && !Objects.equals(request.getActorId(), request.getDelegatorId())) {
            throw new IllegalStateException("Only the approver themself can configure a personal delegate rule unless a platform administrator performs the action");
        }
        if (!syncPermissionEvaluator.canAccess(delegatorRole.name(),
                SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.APPROVE)) {
            throw new IllegalStateException("Delegator role does not have approval privilege: " + delegatorRole.name());
        }
        if (!syncPermissionEvaluator.canAccess(delegateRole.name(),
                SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.APPROVE)) {
            throw new IllegalStateException("Delegate role does not have base approval privilege: " + delegateRole.name());
        }
        if (request.getEffectiveFrom() != null && request.getEffectiveTo() != null
                && request.getEffectiveTo().isBefore(request.getEffectiveFrom())) {
            throw new IllegalStateException("Approval delegate rule effectiveTo cannot be earlier than effectiveFrom");
        }
        if (resolvedTargetTenantId == null && delegatorRole != ActorRole.PLATFORM_ADMINISTRATOR) {
            throw new IllegalStateException("Platform-global permission approval can only be delegated from a platform administrator");
        }
        if (delegatorRole == ActorRole.PLATFORM_ADMINISTRATOR && actingRole != ActorRole.PLATFORM_ADMINISTRATOR) {
            throw new IllegalStateException("Only platform administrators can configure delegate rules for platform administrators");
        }
    }

    private void ensureNoDuplicatedEnabledRule(Long targetTenantId,
                                               Long delegatorId,
                                               String delegatorRole,
                                               Long delegateId,
                                               String delegateRole) {
        LambdaQueryWrapper<SyncPermissionApprovalDelegateRule> wrapper =
                new LambdaQueryWrapper<SyncPermissionApprovalDelegateRule>()
                        .eq(SyncPermissionApprovalDelegateRule::getTargetTenantId, toStoredTenantId(targetTenantId))
                        .eq(SyncPermissionApprovalDelegateRule::getDelegatorId, delegatorId)
                        .eq(SyncPermissionApprovalDelegateRule::getDelegatorRole, delegatorRole)
                        .eq(SyncPermissionApprovalDelegateRule::getDelegateId, delegateId)
                        .eq(SyncPermissionApprovalDelegateRule::getDelegateRole, delegateRole)
                        .eq(SyncPermissionApprovalDelegateRule::getEnabled, Boolean.TRUE);
        if (delegateRuleMapper.selectCount(wrapper) > 0) {
            throw new IllegalStateException("An enabled delegate rule for the same scope and relation already exists");
        }
    }

    private SyncPermissionApprovalDelegateRule getRequiredRule(Long ruleId) {
        SyncPermissionApprovalDelegateRule entity = delegateRuleMapper.selectById(ruleId);
        if (entity == null) {
            throw new IllegalStateException("Approval delegate rule not found: " + ruleId);
        }
        return entity;
    }

    private SyncPermissionContext buildContext(Long actorId,
                                               String actorRole,
                                               Long actorTenantId,
                                               Long targetTenantId) {
        return SyncPermissionContext.builder()
                .actorId(actorId)
                .actorRole(actorRole)
                .actorTenantId(actorTenantId)
                .resourceTenantId(targetTenantId)
                .build();
    }

    private Long resolveReadableTargetTenantId(String actorRole, Long actorTenantId, Long targetTenantId) {
        ActorRole role = ActorRole.fromValue(actorRole);
        if (role == ActorRole.PLATFORM_ADMINISTRATOR) {
            return targetTenantId;
        }
        if (targetTenantId == null) {
            throw new IllegalStateException("Current actor cannot view or manage platform-global approval delegate rules");
        }
        if (!Objects.equals(actorTenantId, targetTenantId)) {
            throw new IllegalStateException("Current actor cannot manage approval delegate rules across tenants");
        }
        return targetTenantId;
    }

    private SyncPermissionApprovalDelegateRuleView toView(SyncPermissionApprovalDelegateRule entity) {
        SyncPermissionApprovalDelegateRuleView view = new SyncPermissionApprovalDelegateRuleView();
        view.setId(entity.getId());
        view.setTargetTenantId(fromStoredTenantId(entity.getTargetTenantId()));
        view.setTargetScopeType(Objects.equals(entity.getTargetTenantId(), PLATFORM_SCOPE_TENANT_ID)
                ? "PLATFORM_GLOBAL" : "TENANT_OVERRIDE");
        view.setDelegatorId(entity.getDelegatorId());
        view.setDelegatorRole(entity.getDelegatorRole());
        view.setDelegateId(entity.getDelegateId());
        view.setDelegateRole(entity.getDelegateRole());
        view.setEffectiveFrom(entity.getEffectiveFrom());
        view.setEffectiveTo(entity.getEffectiveTo());
        view.setEnabled(entity.getEnabled());
        view.setActiveNow(isRuleActive(entity, LocalDateTime.now()));
        view.setDelegateReason(entity.getDelegateReason());
        view.setCreatedBy(entity.getCreatedBy());
        view.setUpdatedBy(entity.getUpdatedBy());
        view.setCreateTime(entity.getCreateTime());
        view.setUpdateTime(entity.getUpdateTime());
        return view;
    }

    private boolean isRuleActive(SyncPermissionApprovalDelegateRule entity, LocalDateTime now) {
        return Boolean.TRUE.equals(entity.getEnabled())
                && (entity.getEffectiveFrom() == null || !entity.getEffectiveFrom().isAfter(now))
                && (entity.getEffectiveTo() == null || !entity.getEffectiveTo().isBefore(now));
    }

    private String buildPayload(Object... keyValues) {
        StringBuilder builder = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append('"').append(keyValues[i]).append('"').append(':').append('"')
                    .append(i + 1 < keyValues.length ? String.valueOf(keyValues[i + 1]) : "")
                    .append('"');
        }
        builder.append('}');
        return builder.toString();
    }

    private void recordAudit(Long tenantId,
                             SyncAuditAction action,
                             Long actorId,
                             String actorRole,
                             String payload) {
        SyncAuditRecord record = new SyncAuditRecord();
        record.setTenantId(tenantId == null ? PLATFORM_SCOPE_TENANT_ID : tenantId);
        record.setActionType(action.name());
        record.setActorId(actorId);
        record.setActorRole(ActorRole.fromValue(actorRole).name());
        record.setActionPayload(payload);
        syncAuditRecordMapper.insert(record);
    }

    private Long toStoredTenantId(Long tenantId) {
        return tenantId == null ? PLATFORM_SCOPE_TENANT_ID : tenantId;
    }

    private Long fromStoredTenantId(Long tenantId) {
        return Objects.equals(tenantId, PLATFORM_SCOPE_TENANT_ID) ? null : tenantId;
    }
}

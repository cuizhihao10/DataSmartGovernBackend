package com.czh.datasmart.govern.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.datasource.controller.dto.ApproveSyncPermissionPolicyChangeRequest;
import com.czh.datasmart.govern.datasource.controller.dto.CreateSyncPermissionPolicyChangeRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionBindingReplaceRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionBindingReplaceResult;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionPolicyChangeRequestView;
import com.czh.datasmart.govern.datasource.entity.SyncAuditRecord;
import com.czh.datasmart.govern.datasource.entity.SyncPermissionPolicyChangeRequest;
import com.czh.datasmart.govern.datasource.mapper.SyncAuditRecordMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncPermissionPolicyChangeRequestMapper;
import com.czh.datasmart.govern.datasource.service.SyncPermissionApprovalGovernanceService;
import com.czh.datasmart.govern.datasource.service.SyncPermissionBindingService;
import com.czh.datasmart.govern.datasource.service.SyncPermissionNotificationService;
import com.czh.datasmart.govern.datasource.service.SyncPermissionPolicyChangeRequestService;
import com.czh.datasmart.govern.datasource.support.ActorRole;
import com.czh.datasmart.govern.datasource.support.SyncAuditAction;
import com.czh.datasmart.govern.datasource.support.SyncPermissionAction;
import com.czh.datasmart.govern.datasource.support.SyncPermissionApprovalDecision;
import com.czh.datasmart.govern.datasource.support.SyncPermissionApprovalMode;
import com.czh.datasmart.govern.datasource.support.SyncPermissionBindingType;
import com.czh.datasmart.govern.datasource.support.SyncPermissionContext;
import com.czh.datasmart.govern.datasource.support.SyncPermissionEvaluator;
import com.czh.datasmart.govern.datasource.support.SyncPermissionPolicyChangeRequestStatus;
import com.czh.datasmart.govern.datasource.support.SyncPermissionResource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * @Author : Cui
 * @Date: 2026/4/24 23:18
 * @Description DataSmart Govern Backend - SyncPermissionPolicyChangeRequestServiceImpl.java
 * @Version:1.0.0
 *
 * 权限绑定变更申请服务实现。
 * 这一层负责串起“提交申请 -> 查询申请 -> 审批并执行”这条治理链路，
 * 同时把审批矩阵快照、防自审和委托代批能力真正接到现有绑定替换逻辑上。
 *
 * 当前设计强调三点：
 * 1. 提交时就把审批角色快照固化，避免后续审批策略变化影响历史解释；
 * 2. 审批时由专门的审批治理服务给出决策结果，而不是散落在 if/else 里；
 * 3. 审批通过后的执行仍然复用现有绑定替换服务，确保治理链路和实际落库链路一致。
 */
@Service
@RequiredArgsConstructor
public class SyncPermissionPolicyChangeRequestServiceImpl implements SyncPermissionPolicyChangeRequestService {

    private static final long PLATFORM_SCOPE_TENANT_ID = 0L;

    private final SyncPermissionPolicyChangeRequestMapper changeRequestMapper;
    private final SyncPermissionBindingService syncPermissionBindingService;
    private final SyncPermissionApprovalGovernanceService syncPermissionApprovalGovernanceService;
    private final SyncPermissionNotificationService syncPermissionNotificationService;
    private final SyncPermissionEvaluator syncPermissionEvaluator;
    private final SyncAuditRecordMapper syncAuditRecordMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public SyncPermissionPolicyChangeRequestView submitChangeRequest(CreateSyncPermissionPolicyChangeRequest request) {
        Long resolvedTargetTenantId = resolveReadableTargetTenantId(
                request.getActorRole(), request.getActorTenantId(), request.getTargetTenantId());
        SyncPermissionContext context = buildContext(
                request.getActorId(), request.getActorRole(), request.getActorTenantId(), resolvedTargetTenantId);
        syncPermissionEvaluator.assertAllowed(context,
                SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.MANAGE_POLICY);

        ActorRole requesterRole = ActorRole.fromValue(request.getActorRole());
        ActorRole targetRole = ActorRole.fromValue(request.getTargetRole());
        SyncPermissionBindingType bindingType = SyncPermissionBindingType.fromValue(request.getBindingType());
        List<String> requiredApproverRoles = syncPermissionApprovalGovernanceService.resolveRequiredApproverRoles(
                resolvedTargetTenantId, bindingType.name(), requesterRole.name());

        SyncPermissionPolicyChangeRequest entity = new SyncPermissionPolicyChangeRequest();
        entity.setTargetTenantId(toStoredTenantId(resolvedTargetTenantId));
        entity.setRequesterId(request.getActorId());
        entity.setRequesterRole(requesterRole.name());
        entity.setRequesterTenantId(request.getActorTenantId());
        entity.setTargetRole(targetRole.name());
        entity.setBindingType(bindingType.name());
        entity.setBindingValuesJson(writeStringList(request.getBindingValues(), "bindingValues"));
        entity.setRequestedPriority(request.getPriority());
        entity.setRequestedBindingSource(request.getBindingSource());
        entity.setRequestReason(request.getRequestReason());
        entity.setRequiredApproverRolesJson(writeStringList(requiredApproverRoles, "requiredApproverRoles"));
        entity.setRequestStatus(SyncPermissionPolicyChangeRequestStatus.PENDING_APPROVAL.name());
        changeRequestMapper.insert(entity);
        syncPermissionNotificationService.createPendingApprovalNotifications(entity, requiredApproverRoles);

        recordAudit(fromStoredTenantId(entity.getTargetTenantId()),
                SyncAuditAction.SUBMIT_PERMISSION_POLICY_CHANGE_REQUEST,
                request.getActorId(),
                request.getActorRole(),
                buildPayload(
                        "changeRequestId", entity.getId(),
                        "targetRole", entity.getTargetRole(),
                        "bindingType", entity.getBindingType(),
                        "requiredApproverRoles", requiredApproverRoles,
                        "requestReason", request.getRequestReason()
                ));
        return toView(entity);
    }

    @Override
    public List<SyncPermissionPolicyChangeRequestView> listChangeRequests(Long actorId,
                                                                          String actorRole,
                                                                          Long actorTenantId,
                                                                          Long targetTenantId,
                                                                          String targetRole,
                                                                          String bindingType,
                                                                          String requestStatus) {
        Long resolvedTargetTenantId = resolveReadableTargetTenantId(actorRole, actorTenantId, targetTenantId);
        SyncPermissionContext context = buildContext(actorId, actorRole, actorTenantId, resolvedTargetTenantId);
        syncPermissionEvaluator.assertAllowed(context,
                SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.VIEW_POLICY);

        LambdaQueryWrapper<SyncPermissionPolicyChangeRequest> wrapper =
                new LambdaQueryWrapper<SyncPermissionPolicyChangeRequest>()
                        .orderByDesc(SyncPermissionPolicyChangeRequest::getCreateTime)
                        .orderByDesc(SyncPermissionPolicyChangeRequest::getId);

        if (resolvedTargetTenantId == null) {
            wrapper.eq(SyncPermissionPolicyChangeRequest::getTargetTenantId, PLATFORM_SCOPE_TENANT_ID);
        } else {
            wrapper.eq(SyncPermissionPolicyChangeRequest::getTargetTenantId, toStoredTenantId(resolvedTargetTenantId));
        }
        if (targetRole != null && !targetRole.isBlank()) {
            wrapper.eq(SyncPermissionPolicyChangeRequest::getTargetRole, ActorRole.fromValue(targetRole).name());
        }
        if (bindingType != null && !bindingType.isBlank()) {
            wrapper.eq(SyncPermissionPolicyChangeRequest::getBindingType,
                    SyncPermissionBindingType.fromValue(bindingType).name());
        }
        if (requestStatus != null && !requestStatus.isBlank()) {
            wrapper.eq(SyncPermissionPolicyChangeRequest::getRequestStatus,
                    SyncPermissionPolicyChangeRequestStatus.fromValue(requestStatus).name());
        }
        return changeRequestMapper.selectList(wrapper).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional
    public SyncPermissionPolicyChangeRequestView approveChangeRequest(Long requestId,
                                                                     ApproveSyncPermissionPolicyChangeRequest request) {
        SyncPermissionPolicyChangeRequest entity = getRequiredRequest(requestId);
        if (!SyncPermissionPolicyChangeRequestStatus.PENDING_APPROVAL.name().equals(entity.getRequestStatus())) {
            throw new IllegalStateException("Current permission policy change request is not pending approval");
        }

        SyncPermissionApprovalDecision decision = syncPermissionApprovalGovernanceService.assertCanApprove(
                entity, request.getActorId(), request.getActorRole(), request.getActorTenantId());

        entity.setApproverId(request.getActorId());
        entity.setApproverRole(ActorRole.fromValue(request.getActorRole()).name());
        entity.setApprovalMode(decision.getApprovalMode().name());
        entity.setDelegatedFromApproverId(decision.getDelegatedFromApproverId());
        entity.setDelegatedFromApproverRole(decision.getDelegatedFromApproverRole());
        entity.setApprovalComment(request.getApprovalComment());
        entity.setApprovedAt(LocalDateTime.now());

        if (Boolean.TRUE.equals(request.getApproved())) {
            SyncPermissionBindingReplaceRequest replaceRequest = new SyncPermissionBindingReplaceRequest();
            replaceRequest.setActorId(request.getActorId());
            replaceRequest.setActorRole(request.getActorRole());
            replaceRequest.setActorTenantId(request.getActorTenantId());
            replaceRequest.setNote(buildApprovalExecutionNote(entity, decision, request.getApprovalComment()));
            replaceRequest.setTargetTenantId(fromStoredTenantId(entity.getTargetTenantId()));
            replaceRequest.setTargetRole(entity.getTargetRole());
            replaceRequest.setBindingType(entity.getBindingType());
            replaceRequest.setBindingValues(readStringList(entity.getBindingValuesJson(), "bindingValues"));
            replaceRequest.setPriority(entity.getRequestedPriority());
            replaceRequest.setBindingSource(entity.getRequestedBindingSource());

            SyncPermissionBindingReplaceResult executionResult = syncPermissionBindingService.replaceBindings(replaceRequest);
            entity.setRequestStatus(SyncPermissionPolicyChangeRequestStatus.EXECUTED.name());
            entity.setExecutedAt(LocalDateTime.now());
            entity.setExecutionSummary(executionResult.getSummary());
            changeRequestMapper.updateById(entity);
            syncPermissionNotificationService.createDecisionNotification(entity);

            recordAudit(fromStoredTenantId(entity.getTargetTenantId()),
                    SyncAuditAction.APPROVE_PERMISSION_POLICY_CHANGE_REQUEST,
                    request.getActorId(),
                    request.getActorRole(),
                    buildPayload(
                            "changeRequestId", entity.getId(),
                            "approved", true,
                            "approvalMode", decision.getApprovalMode().name(),
                            "delegatedFromApproverId", decision.getDelegatedFromApproverId(),
                            "delegatedFromApproverRole", decision.getDelegatedFromApproverRole(),
                            "executionSummary", executionResult.getSummary()
                    ));
        } else {
            entity.setRequestStatus(SyncPermissionPolicyChangeRequestStatus.REJECTED.name());
            entity.setExecutionSummary("Approval rejected, no permission binding replacement executed");
            changeRequestMapper.updateById(entity);
            syncPermissionNotificationService.createDecisionNotification(entity);

            recordAudit(fromStoredTenantId(entity.getTargetTenantId()),
                    SyncAuditAction.REJECT_PERMISSION_POLICY_CHANGE_REQUEST,
                    request.getActorId(),
                    request.getActorRole(),
                    buildPayload(
                            "changeRequestId", entity.getId(),
                            "approved", false,
                            "approvalMode", decision.getApprovalMode().name(),
                            "delegatedFromApproverId", decision.getDelegatedFromApproverId(),
                            "delegatedFromApproverRole", decision.getDelegatedFromApproverRole(),
                            "approvalComment", request.getApprovalComment()
                    ));
        }
        return toView(entity);
    }

    private SyncPermissionPolicyChangeRequest getRequiredRequest(Long requestId) {
        SyncPermissionPolicyChangeRequest entity = changeRequestMapper.selectById(requestId);
        if (entity == null) {
            throw new IllegalStateException("Permission policy change request not found: " + requestId);
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
            return actorTenantId;
        }
        if (!Objects.equals(actorTenantId, targetTenantId)) {
            throw new IllegalStateException("Current actor cannot submit or query permission policy change requests across tenants");
        }
        return targetTenantId;
    }

    private SyncPermissionPolicyChangeRequestView toView(SyncPermissionPolicyChangeRequest entity) {
        SyncPermissionPolicyChangeRequestView view = new SyncPermissionPolicyChangeRequestView();
        view.setId(entity.getId());
        view.setTargetTenantId(fromStoredTenantId(entity.getTargetTenantId()));
        view.setTargetScopeType(Objects.equals(entity.getTargetTenantId(), PLATFORM_SCOPE_TENANT_ID)
                ? "PLATFORM_GLOBAL" : "TENANT_OVERRIDE");
        view.setRequesterId(entity.getRequesterId());
        view.setRequesterRole(entity.getRequesterRole());
        view.setRequesterTenantId(entity.getRequesterTenantId());
        view.setTargetRole(entity.getTargetRole());
        view.setBindingType(entity.getBindingType());
        view.setBindingValues(readStringList(entity.getBindingValuesJson(), "bindingValues"));
        view.setRequestedPriority(entity.getRequestedPriority());
        view.setRequestedBindingSource(entity.getRequestedBindingSource());
        view.setRequestReason(entity.getRequestReason());
        view.setRequiredApproverRoles(readStringList(entity.getRequiredApproverRolesJson(), "requiredApproverRoles"));
        view.setRequestStatus(entity.getRequestStatus());
        view.setApproverId(entity.getApproverId());
        view.setApproverRole(entity.getApproverRole());
        view.setApprovalMode(entity.getApprovalMode());
        view.setDelegatedFromApproverId(entity.getDelegatedFromApproverId());
        view.setDelegatedFromApproverRole(entity.getDelegatedFromApproverRole());
        view.setApprovalComment(entity.getApprovalComment());
        view.setApprovedAt(entity.getApprovedAt());
        view.setExecutedAt(entity.getExecutedAt());
        view.setExecutionSummary(entity.getExecutionSummary());
        view.setCreateTime(entity.getCreateTime());
        view.setUpdateTime(entity.getUpdateTime());
        return view;
    }

    private String buildApprovalExecutionNote(SyncPermissionPolicyChangeRequest entity,
                                              SyncPermissionApprovalDecision decision,
                                              String approvalComment) {
        StringBuilder builder = new StringBuilder("APPROVED_CHANGE_REQUEST:")
                .append(entity.getId())
                .append(" | approvalMode=").append(decision.getApprovalMode().name());
        if (decision.getApprovalMode() == SyncPermissionApprovalMode.DELEGATED_ROLE) {
            builder.append(" | delegatedFromApproverId=").append(decision.getDelegatedFromApproverId())
                    .append(" | delegatedFromApproverRole=").append(decision.getDelegatedFromApproverRole());
        }
        if (approvalComment != null && !approvalComment.isBlank()) {
            builder.append(" | ").append(approvalComment);
        }
        return builder.toString();
    }

    private String writeStringList(List<String> values, String fieldName) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(fieldName + " serialize failed", exception);
        }
    }

    private List<String> readStringList(String json, String fieldName) {
        try {
            return objectMapper.readValue(json == null ? "[]" : json, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(fieldName + " deserialize failed", exception);
        }
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

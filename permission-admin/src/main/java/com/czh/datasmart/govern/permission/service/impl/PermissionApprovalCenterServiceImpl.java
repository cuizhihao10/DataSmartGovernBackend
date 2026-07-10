/**
 * @Author : Cui
 * @Date: 2026/07/10 11:39
 * @Description DataSmart Govern Backend - PermissionApprovalCenterServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.permission.controller.dto.ApprovalCenterItemView;
import com.czh.datasmart.govern.permission.controller.dto.ApprovalCenterMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.ApprovalCenterQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.ApprovalCenterReviewRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.ProjectCreationRequestMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.ProjectCreationRequestReviewRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectJoinRequestMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.ProjectJoinRequestReviewRequest;
import com.czh.datasmart.govern.permission.mapper.PermissionApprovalCenterMapper;
import com.czh.datasmart.govern.permission.service.PermissionApprovalCenterService;
import com.czh.datasmart.govern.permission.service.PermissionProjectCreationRequestService;
import com.czh.datasmart.govern.permission.service.PermissionProjectJoinRequestService;
import com.czh.datasmart.govern.permission.support.PermissionRoleCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Unified approval-center service.
 *
 * <p>Queries are aggregated, but mutations are delegated to the concrete workflow service. This preserves each
 * workflow's tenant checks, status transitions, audit behavior and side effects while giving the console one stable
 * approval entry point.</p>
 */
@Service
@RequiredArgsConstructor
public class PermissionApprovalCenterServiceImpl implements PermissionApprovalCenterService {

    private static final long DEFAULT_CURRENT = 1L;
    private static final long DEFAULT_PAGE_SIZE = 20L;
    private static final long MAX_PAGE_SIZE = 100L;
    private static final String STATUS_PENDING = "PENDING";
    private static final String TYPE_PROJECT_CREATION = "PROJECT_CREATION";
    private static final String TYPE_PROJECT_JOIN = "PROJECT_JOIN";
    private static final Set<String> SUPPORTED_TYPES = Set.of(TYPE_PROJECT_CREATION, TYPE_PROJECT_JOIN);
    private static final Set<String> SUPPORTED_STATUSES = Set.of("PENDING", "APPROVED", "REJECTED", "CANCELLED");
    private static final Set<String> REVIEW_ROLES = Set.of(
            PermissionRoleCode.TENANT_ADMINISTRATOR.name(),
            PermissionRoleCode.PLATFORM_ADMINISTRATOR.name()
    );

    private final PermissionApprovalCenterMapper approvalCenterMapper;
    private final PermissionProjectCreationRequestService creationRequestService;
    private final PermissionProjectJoinRequestService joinRequestService;

    @Override
    public PlatformPageResponse<ApprovalCenterItemView> pageMyRequests(ApprovalCenterQueryCriteria criteria,
                                                                       PermissionActorContext actorContext) {
        Long actorId = requirePositive(actorContext == null ? null : actorContext.actorId(), "actorId");
        String actorRole = requireRole(actorContext);
        ApprovalCenterQueryCriteria safeCriteria = safeCriteria(criteria);
        Long tenantId = resolveQueryTenant(safeCriteria.tenantId(), actorContext, actorRole);
        return page(tenantId, actorId, safeCriteria, List.of("CANCEL"));
    }

    @Override
    public PlatformPageResponse<ApprovalCenterItemView> pagePendingApprovals(ApprovalCenterQueryCriteria criteria,
                                                                             PermissionActorContext actorContext) {
        String actorRole = requireRole(actorContext);
        requireReviewerRole(actorRole);
        ApprovalCenterQueryCriteria safeCriteria = safeCriteria(criteria);
        Long tenantId = resolveQueryTenant(safeCriteria.tenantId(), actorContext, actorRole);
        ApprovalCenterQueryCriteria pendingCriteria = new ApprovalCenterQueryCriteria(
                tenantId,
                safeCriteria.requestType(),
                defaultText(normalizeStatus(safeCriteria.status()), STATUS_PENDING),
                safeCriteria.current(),
                safeCriteria.size());
        return page(tenantId, null, pendingCriteria, List.of("APPROVE", "REJECT"));
    }

    @Override
    public ApprovalCenterMutationResult approve(String requestType,
                                                Long requestId,
                                                ApprovalCenterReviewRequest request,
                                                PermissionActorContext actorContext) {
        String type = normalizeType(requestType);
        requireReviewerRole(requireRole(actorContext));
        if (TYPE_PROJECT_CREATION.equals(type)) {
            ProjectCreationRequestMutationResult result = creationRequestService.approve(
                    requestId,
                    new ProjectCreationRequestReviewRequest(
                            request == null ? null : request.projectCode(),
                            request == null ? null : request.projectName(),
                            request == null ? null : request.projectType(),
                            request == null ? null : request.applicationId(),
                            request == null ? null : request.ownerActorId(),
                            request == null ? null : request.description(),
                            request == null ? null : request.reviewComment()),
                    actorContext);
            return new ApprovalCenterMutationResult(type, result.requestId(), result.status(),
                    result.createdProjectId(), result.message());
        }
        ProjectJoinRequestMutationResult result = joinRequestService.approve(
                requestId,
                new ProjectJoinRequestReviewRequest(
                        request == null ? null : request.approvedProjectRole(),
                        request == null ? null : request.reviewComment()),
                actorContext);
        return new ApprovalCenterMutationResult(type, result.requestId(), result.status(),
                result.membershipId(), result.message());
    }

    @Override
    public ApprovalCenterMutationResult reject(String requestType,
                                               Long requestId,
                                               ApprovalCenterReviewRequest request,
                                               PermissionActorContext actorContext) {
        String type = normalizeType(requestType);
        requireReviewerRole(requireRole(actorContext));
        if (TYPE_PROJECT_CREATION.equals(type)) {
            ProjectCreationRequestMutationResult result = creationRequestService.reject(
                    requestId,
                    new ProjectCreationRequestReviewRequest(null, null, null, null, null, null,
                            request == null ? null : request.reviewComment()),
                    actorContext);
            return new ApprovalCenterMutationResult(type, result.requestId(), result.status(),
                    result.createdProjectId(), result.message());
        }
        ProjectJoinRequestMutationResult result = joinRequestService.reject(
                requestId,
                new ProjectJoinRequestReviewRequest(null, request == null ? null : request.reviewComment()),
                actorContext);
        return new ApprovalCenterMutationResult(type, result.requestId(), result.status(),
                result.membershipId(), result.message());
    }

    @Override
    public ApprovalCenterMutationResult cancel(String requestType,
                                               Long requestId,
                                               PermissionActorContext actorContext) {
        String type = normalizeType(requestType);
        if (TYPE_PROJECT_CREATION.equals(type)) {
            ProjectCreationRequestMutationResult result = creationRequestService.cancel(requestId, actorContext);
            return new ApprovalCenterMutationResult(type, result.requestId(), result.status(),
                    result.createdProjectId(), result.message());
        }
        ProjectJoinRequestMutationResult result = joinRequestService.cancel(requestId, actorContext);
        return new ApprovalCenterMutationResult(type, result.requestId(), result.status(),
                result.membershipId(), result.message());
    }

    private PlatformPageResponse<ApprovalCenterItemView> page(Long tenantId,
                                                              Long applicantActorId,
                                                              ApprovalCenterQueryCriteria criteria,
                                                              List<String> pendingActions) {
        long current = safeCurrent(criteria.current());
        long size = safeSize(criteria.size());
        String requestType = normalizeOptionalType(criteria.requestType());
        String status = normalizeStatus(criteria.status());
        List<ApprovalCenterItemView> records = approvalCenterMapper.selectApprovalPage(
                tenantId, applicantActorId, requestType, status, size, (current - 1) * size);
        records.forEach(item -> item.setAvailableActions(
                STATUS_PENDING.equals(normalizeStatus(item.getStatus())) ? pendingActions : List.of()));
        long total = approvalCenterMapper.countApprovals(tenantId, applicantActorId, requestType, status);
        return PlatformPageResponse.of(current, size, total, records);
    }

    private Long resolveQueryTenant(Long requestedTenantId,
                                    PermissionActorContext actorContext,
                                    String actorRole) {
        Long actorTenantId = requirePositive(actorContext == null ? null : actorContext.tenantId(), "actorTenantId");
        if (PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(actorRole)) {
            return requestedTenantId;
        }
        if (requestedTenantId != null && !actorTenantId.equals(requestedTenantId)) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "Cannot query approvals from another tenant");
        }
        return actorTenantId;
    }

    private void requireReviewerRole(String actorRole) {
        if (!REVIEW_ROLES.contains(actorRole)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Only tenant or platform administrators can review unified approval-center pending work");
        }
    }

    private String requireRole(PermissionActorContext actorContext) {
        if (actorContext == null || actorContext.actorRole() == null || actorContext.actorRole().isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, "Missing trusted actor role");
        }
        return actorContext.actorRole().trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeType(String requestType) {
        String normalized = normalizeOptionalType(requestType);
        if (normalized == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "requestType cannot be blank");
        }
        return normalized;
    }

    private String normalizeOptionalType(String requestType) {
        String normalized = normalizeCode(requestType);
        if (normalized != null && !SUPPORTED_TYPES.contains(normalized)) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "Unsupported approval requestType: " + requestType);
        }
        return normalized;
    }

    private String normalizeStatus(String status) {
        String normalized = normalizeCode(status);
        if (normalized != null && !SUPPORTED_STATUSES.contains(normalized)) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "Unsupported approval status: " + status);
        }
        return normalized;
    }

    private ApprovalCenterQueryCriteria safeCriteria(ApprovalCenterQueryCriteria criteria) {
        return criteria == null ? new ApprovalCenterQueryCriteria(null, null, null, null, null) : criteria;
    }

    private long safeCurrent(Long current) {
        return current == null || current <= 0 ? DEFAULT_CURRENT : current;
    }

    private long safeSize(Long size) {
        return size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
    }

    private Long requirePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    fieldName + " must be positive");
        }
        return value;
    }

    private String normalizeCode(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

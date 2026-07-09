/**
 * @Author : Cui
 * @Date: 2026/07/10 20:42
 * @Description DataSmart Govern Backend - PermissionProjectJoinRequestServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.ProjectJoinRequestApplyRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectJoinRequestMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.ProjectJoinRequestQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.ProjectJoinRequestReviewRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectJoinRequestView;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipCreateRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipMutationResult;
import com.czh.datasmart.govern.permission.entity.PermissionProject;
import com.czh.datasmart.govern.permission.entity.PermissionProjectJoinRequest;
import com.czh.datasmart.govern.permission.entity.PermissionProjectMembership;
import com.czh.datasmart.govern.permission.mapper.PermissionProjectJoinRequestMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionProjectMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionProjectMembershipMapper;
import com.czh.datasmart.govern.permission.service.PermissionProjectJoinRequestService;
import com.czh.datasmart.govern.permission.service.PermissionProjectMembershipService;
import com.czh.datasmart.govern.permission.support.PermissionRoleCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Implementation of the project join approval workflow.
 *
 * <p>The important product boundary is that project visibility is still granted only by
 * {@code permission_project_membership}. A pending join request never becomes a data-scope claim by itself. Only after
 * approval do we upsert the membership row with {@code grantSource=APPROVAL}; gateway can then rebuild the trusted
 * project-role headers in the same way it already does for manually granted memberships.</p>
 */
@Service
@RequiredArgsConstructor
public class PermissionProjectJoinRequestServiceImpl implements PermissionProjectJoinRequestService {

    private static final long PLATFORM_TENANT_ID = 0L;
    private static final long DEFAULT_CURRENT = 1L;
    private static final long DEFAULT_PAGE_SIZE = 20L;
    private static final long MAX_PAGE_SIZE = 100L;
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String PROJECT_STATUS_ACTIVE = "ACTIVE";
    private static final String ROLE_OWNER = "OWNER";
    private static final String ROLE_MANAGER = "MANAGER";
    private static final String ROLE_READER = "READER";
    private static final Set<String> REVIEW_GLOBAL_ROLES = Set.of(
            PermissionRoleCode.PROJECT_OWNER.name(),
            PermissionRoleCode.TENANT_ADMINISTRATOR.name(),
            PermissionRoleCode.PLATFORM_ADMINISTRATOR.name()
    );

    private final PermissionProjectJoinRequestMapper joinRequestMapper;
    private final PermissionProjectMapper projectMapper;
    private final PermissionProjectMembershipMapper membershipMapper;
    private final PermissionProjectMembershipService membershipService;

    @Override
    @Transactional
    public ProjectJoinRequestMutationResult apply(ProjectJoinRequestApplyRequest request,
                                                  PermissionActorContext actorContext) {
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "Project join request cannot be null");
        }
        Long applicantActorId = requirePositive(actorContext == null ? null : actorContext.actorId(), "actorId");
        String actorRole = requireRole(actorContext);
        Long targetTenantId = resolveTargetTenantId(request.tenantId(), actorContext, actorRole);
        Long projectId = requirePositive(request.projectId(), "projectId");
        PermissionProject project = findActiveProject(targetTenantId, projectId);
        String requestedRole = normalizeApplicantRole(request.requestedProjectRole());

        if (hasEnabledMembership(targetTenantId, applicantActorId, projectId)) {
            throw new PlatformBusinessException(PlatformErrorCode.DUPLICATE_OPERATION,
                    "You are already a member of this project and do not need to apply again");
        }
        if (hasPendingRequest(targetTenantId, applicantActorId, projectId)) {
            throw new PlatformBusinessException(PlatformErrorCode.DUPLICATE_OPERATION,
                    "A pending project join request already exists; please wait for review or cancel it first");
        }

        LocalDateTime now = LocalDateTime.now();
        PermissionProjectJoinRequest joinRequest = new PermissionProjectJoinRequest();
        joinRequest.setTenantId(project.getTenantId());
        joinRequest.setProjectId(project.getProjectId());
        joinRequest.setApplicantActorId(applicantActorId);
        joinRequest.setApplicantName(trimToNull(request.applicantName()));
        joinRequest.setRequestedProjectRole(requestedRole);
        joinRequest.setRequestReason(trimToNull(request.requestReason()));
        joinRequest.setStatus(STATUS_PENDING);
        joinRequest.setCreateTime(now);
        joinRequest.setUpdateTime(now);
        joinRequestMapper.insert(joinRequest);
        return result(joinRequest, "Project join request submitted and waiting for approval");
    }

    @Override
    public PlatformPageResponse<ProjectJoinRequestView> pageMyRequests(ProjectJoinRequestQueryCriteria criteria,
                                                                       PermissionActorContext actorContext) {
        Long actorId = requirePositive(actorContext == null ? null : actorContext.actorId(), "actorId");
        ProjectJoinRequestQueryCriteria safeCriteria = safeCriteria(criteria);
        LambdaQueryWrapper<PermissionProjectJoinRequest> wrapper = basePageWrapper(safeCriteria)
                .eq(PermissionProjectJoinRequest::getApplicantActorId, actorId);
        Long tenantId = safeCriteria.tenantId() == null ? normalizeTenantId(actorContext.tenantId()) : safeCriteria.tenantId();
        wrapper.eq(tenantId != null, PermissionProjectJoinRequest::getTenantId, tenantId);
        return page(wrapper, safeCriteria.current(), safeCriteria.size());
    }

    @Override
    public PlatformPageResponse<ProjectJoinRequestView> pageApprovalRequests(ProjectJoinRequestQueryCriteria criteria,
                                                                            PermissionActorContext actorContext) {
        String actorRole = requireRole(actorContext);
        if (!REVIEW_GLOBAL_ROLES.contains(actorRole)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Current role cannot review project join requests: " + actorRole);
        }
        ProjectJoinRequestQueryCriteria safeCriteria = safeCriteria(criteria);
        LambdaQueryWrapper<PermissionProjectJoinRequest> wrapper = basePageWrapper(safeCriteria);
        applyReviewerScope(wrapper, safeCriteria, actorContext, actorRole);
        eqIfPresent(wrapper, PermissionProjectJoinRequest::getApplicantActorId, safeCriteria.applicantActorId());
        return page(wrapper, safeCriteria.current(), safeCriteria.size());
    }

    @Override
    @Transactional
    public ProjectJoinRequestMutationResult approve(Long requestId,
                                                    ProjectJoinRequestReviewRequest request,
                                                    PermissionActorContext actorContext) {
        PermissionProjectJoinRequest joinRequest = findPendingRequest(requestId);
        String actorRole = requireRole(actorContext);
        validateReviewerCanMutate(joinRequest, actorContext, actorRole);
        String approvedRole = normalizeApprovedRole(request == null ? null : request.approvedProjectRole(), actorRole);

        ProjectMembershipMutationResult membership = membershipService.grantOrUpdateProjectMembership(
                new ProjectMembershipCreateRequest(
                        joinRequest.getTenantId(),
                        joinRequest.getApplicantActorId(),
                        joinRequest.getProjectId(),
                        approvedRole,
                        "APPROVAL",
                        true,
                        defaultText(request == null ? null : request.reviewComment(), "Approved project join request")),
                actorContext);

        LocalDateTime now = LocalDateTime.now();
        joinRequest.setStatus(STATUS_APPROVED);
        joinRequest.setReviewerActorId(actorContext.actorId());
        joinRequest.setReviewerActorRole(actorRole);
        joinRequest.setReviewComment(trimToNull(request == null ? null : request.reviewComment()));
        joinRequest.setReviewTime(now);
        joinRequest.setMembershipId(membership.membershipId());
        joinRequest.setUpdateTime(now);
        joinRequestMapper.updateById(joinRequest);
        return result(joinRequest, "Project join request approved and membership granted");
    }

    @Override
    @Transactional
    public ProjectJoinRequestMutationResult reject(Long requestId,
                                                   ProjectJoinRequestReviewRequest request,
                                                   PermissionActorContext actorContext) {
        PermissionProjectJoinRequest joinRequest = findPendingRequest(requestId);
        String actorRole = requireRole(actorContext);
        validateReviewerCanMutate(joinRequest, actorContext, actorRole);
        LocalDateTime now = LocalDateTime.now();
        joinRequest.setStatus(STATUS_REJECTED);
        joinRequest.setReviewerActorId(actorContext.actorId());
        joinRequest.setReviewerActorRole(actorRole);
        joinRequest.setReviewComment(trimToNull(request == null ? null : request.reviewComment()));
        joinRequest.setReviewTime(now);
        joinRequest.setUpdateTime(now);
        joinRequestMapper.updateById(joinRequest);
        return result(joinRequest, "Project join request rejected");
    }

    @Override
    @Transactional
    public ProjectJoinRequestMutationResult cancel(Long requestId, PermissionActorContext actorContext) {
        PermissionProjectJoinRequest joinRequest = findPendingRequest(requestId);
        Long actorId = requirePositive(actorContext == null ? null : actorContext.actorId(), "actorId");
        if (!actorId.equals(joinRequest.getApplicantActorId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Only the applicant can cancel this pending join request");
        }
        joinRequest.setStatus(STATUS_CANCELLED);
        joinRequest.setUpdateTime(LocalDateTime.now());
        joinRequestMapper.updateById(joinRequest);
        return result(joinRequest, "Project join request cancelled");
    }

    private LambdaQueryWrapper<PermissionProjectJoinRequest> basePageWrapper(ProjectJoinRequestQueryCriteria criteria) {
        LambdaQueryWrapper<PermissionProjectJoinRequest> wrapper = new LambdaQueryWrapper<PermissionProjectJoinRequest>()
                .orderByDesc(PermissionProjectJoinRequest::getUpdateTime)
                .orderByDesc(PermissionProjectJoinRequest::getId);
        eqIfPresent(wrapper, PermissionProjectJoinRequest::getProjectId, criteria.projectId());
        String status = normalizeCode(criteria.status());
        if (status != null) {
            if (!Set.of(STATUS_PENDING, STATUS_APPROVED, STATUS_REJECTED, STATUS_CANCELLED).contains(status)) {
                throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "Unsupported request status: " + status);
            }
            wrapper.eq(PermissionProjectJoinRequest::getStatus, status);
        }
        return wrapper;
    }

    private void applyReviewerScope(LambdaQueryWrapper<PermissionProjectJoinRequest> wrapper,
                                    ProjectJoinRequestQueryCriteria criteria,
                                    PermissionActorContext actorContext,
                                    String actorRole) {
        if (PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(actorRole)) {
            eqIfPresent(wrapper, PermissionProjectJoinRequest::getTenantId, criteria.tenantId());
            return;
        }
        Long actorTenantId = requirePositive(normalizeTenantId(actorContext.tenantId()), "actorTenantId");
        Long requestedTenantId = criteria.tenantId() == null ? actorTenantId : criteria.tenantId();
        if (!actorTenantId.equals(requestedTenantId)) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "Reviewer cannot query join requests from another tenant");
        }
        wrapper.eq(PermissionProjectJoinRequest::getTenantId, actorTenantId);
        if (PermissionRoleCode.TENANT_ADMINISTRATOR.name().equals(actorRole)) {
            return;
        }
        Set<Long> ownerProjects = ownerProjectIds(actorTenantId, actorContext.actorId());
        if (ownerProjects.isEmpty()) {
            wrapper.eq(PermissionProjectJoinRequest::getProjectId, -1L);
            return;
        }
        if (criteria.projectId() != null && !ownerProjects.contains(criteria.projectId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Project owner can only review requests for projects they own");
        }
        wrapper.in(PermissionProjectJoinRequest::getProjectId, ownerProjects);
    }

    private void validateReviewerCanMutate(PermissionProjectJoinRequest request,
                                           PermissionActorContext actorContext,
                                           String actorRole) {
        if (!REVIEW_GLOBAL_ROLES.contains(actorRole)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Current role cannot review project join requests: " + actorRole);
        }
        if (PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(actorRole)) {
            return;
        }
        Long actorTenantId = requirePositive(normalizeTenantId(actorContext.tenantId()), "actorTenantId");
        if (!actorTenantId.equals(request.getTenantId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "Reviewer cannot review project join requests from another tenant");
        }
        if (PermissionRoleCode.TENANT_ADMINISTRATOR.name().equals(actorRole)) {
            return;
        }
        if (!ownerProjectIds(actorTenantId, actorContext.actorId()).contains(request.getProjectId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Project owner can only review join requests for projects they own");
        }
    }

    private PermissionProject findActiveProject(Long tenantId, Long projectId) {
        PermissionProject project = projectMapper.selectById(projectId);
        if (project == null || !tenantId.equals(project.getTenantId())) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "Target project does not exist");
        }
        if (!PROJECT_STATUS_ACTIVE.equals(normalizeCode(project.getStatus()))) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "Only ACTIVE projects can accept join requests");
        }
        return project;
    }

    private PermissionProjectJoinRequest findPendingRequest(Long requestId) {
        Long id = requirePositive(requestId, "requestId");
        PermissionProjectJoinRequest request = joinRequestMapper.selectById(id);
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "Project join request does not exist: " + id);
        }
        if (!STATUS_PENDING.equals(normalizeCode(request.getStatus()))) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "Only PENDING project join requests can be changed");
        }
        return request;
    }

    private boolean hasEnabledMembership(Long tenantId, Long actorId, Long projectId) {
        Long count = membershipMapper.selectCount(new LambdaQueryWrapper<PermissionProjectMembership>()
                .eq(PermissionProjectMembership::getTenantId, tenantId)
                .eq(PermissionProjectMembership::getActorId, actorId)
                .eq(PermissionProjectMembership::getProjectId, projectId)
                .eq(PermissionProjectMembership::getEnabled, true));
        return count != null && count > 0;
    }

    private boolean hasPendingRequest(Long tenantId, Long actorId, Long projectId) {
        Long count = joinRequestMapper.selectCount(new LambdaQueryWrapper<PermissionProjectJoinRequest>()
                .eq(PermissionProjectJoinRequest::getTenantId, tenantId)
                .eq(PermissionProjectJoinRequest::getApplicantActorId, actorId)
                .eq(PermissionProjectJoinRequest::getProjectId, projectId)
                .eq(PermissionProjectJoinRequest::getStatus, STATUS_PENDING));
        return count != null && count > 0;
    }

    private Set<Long> ownerProjectIds(Long tenantId, Long actorId) {
        if (actorId == null) {
            return Set.of();
        }
        return membershipMapper.selectList(new LambdaQueryWrapper<PermissionProjectMembership>()
                        .eq(PermissionProjectMembership::getTenantId, normalizeTenantId(tenantId))
                        .eq(PermissionProjectMembership::getActorId, actorId)
                        .eq(PermissionProjectMembership::getEnabled, true)
                        .eq(PermissionProjectMembership::getProjectRole, ROLE_OWNER)
                        .isNotNull(PermissionProjectMembership::getProjectId))
                .stream()
                .map(PermissionProjectMembership::getProjectId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private PlatformPageResponse<ProjectJoinRequestView> page(LambdaQueryWrapper<PermissionProjectJoinRequest> wrapper,
                                                              Long current,
                                                              Long size) {
        Page<PermissionProjectJoinRequest> page = joinRequestMapper.selectPage(pageRequest(current, size), wrapper);
        List<ProjectJoinRequestView> records = page.getRecords().stream()
                .map(ProjectJoinRequestView::from)
                .toList();
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    private ProjectJoinRequestMutationResult result(PermissionProjectJoinRequest request, String message) {
        return new ProjectJoinRequestMutationResult(
                request.getId(),
                request.getTenantId(),
                request.getProjectId(),
                request.getApplicantActorId(),
                request.getStatus(),
                request.getMembershipId(),
                message
        );
    }

    private String normalizeApplicantRole(String value) {
        String role = defaultText(normalizeCode(value), ROLE_READER);
        if (!Set.of(ROLE_READER, ROLE_MANAGER).contains(role)) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "Join request role can only be READER or MANAGER; OWNER must be granted by administrators directly");
        }
        return role;
    }

    private String normalizeApprovedRole(String value, String reviewerRole) {
        String role = defaultText(normalizeCode(value), ROLE_READER);
        if (!Set.of(ROLE_READER, ROLE_MANAGER, ROLE_OWNER).contains(role)) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "Approved project role must be READER, MANAGER or OWNER");
        }
        if (ROLE_OWNER.equals(role)
                && PermissionRoleCode.PROJECT_OWNER.name().equals(reviewerRole)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Project owners cannot approve another OWNER; tenant or platform administrator is required");
        }
        return role;
    }

    private Long resolveTargetTenantId(Long requestedTenantId, PermissionActorContext actorContext, String actorRole) {
        Long actorTenantId = normalizeTenantId(actorContext.tenantId());
        Long targetTenantId = requestedTenantId == null ? actorTenantId : requestedTenantId;
        requirePositive(targetTenantId, "tenantId");
        if (PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(actorRole)) {
            return targetTenantId;
        }
        if (!targetTenantId.equals(actorTenantId)) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "Cannot apply to join a project from another tenant");
        }
        return targetTenantId;
    }

    private String requireRole(PermissionActorContext actorContext) {
        if (actorContext == null || actorContext.actorRole() == null || actorContext.actorRole().isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, "Missing trusted actor role");
        }
        return normalizeCode(actorContext.actorRole());
    }

    private Long requirePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, fieldName + " must be positive");
        }
        return value;
    }

    private ProjectJoinRequestQueryCriteria safeCriteria(ProjectJoinRequestQueryCriteria criteria) {
        return criteria == null
                ? new ProjectJoinRequestQueryCriteria(null, null, null, null, null, null)
                : criteria;
    }

    private Page<PermissionProjectJoinRequest> pageRequest(Long current, Long size) {
        long safeCurrent = current == null || current <= 0 ? DEFAULT_CURRENT : current;
        long safeSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return new Page<>(safeCurrent, safeSize);
    }

    private Long normalizeTenantId(Long tenantId) {
        return tenantId == null ? PLATFORM_TENANT_ID : tenantId;
    }

    private String normalizeCode(String value) {
        String text = trimToNull(value);
        return text == null ? null : text.toUpperCase(Locale.ROOT);
    }

    private String defaultText(String value, String defaultValue) {
        String text = trimToNull(value);
        return text == null ? defaultValue : text;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private <T> void eqIfPresent(LambdaQueryWrapper<T> wrapper,
                                 com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> column,
                                 Long value) {
        if (value != null) {
            wrapper.eq(column, value);
        }
    }
}

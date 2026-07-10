/**
 * @Author : Cui
 * @Date: 2026/07/10 04:29
 * @Description DataSmart Govern Backend - PermissionProjectCreationRequestServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectCreateRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.ProjectCreationRequestApplyRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectCreationRequestMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.ProjectCreationRequestQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.ProjectCreationRequestReviewRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectCreationRequestView;
import com.czh.datasmart.govern.permission.entity.PermissionProject;
import com.czh.datasmart.govern.permission.entity.PermissionProjectCreationRequest;
import com.czh.datasmart.govern.permission.mapper.PermissionProjectCreationRequestMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionProjectMapper;
import com.czh.datasmart.govern.permission.service.PermissionProjectCreationRequestService;
import com.czh.datasmart.govern.permission.service.PermissionProjectService;
import com.czh.datasmart.govern.permission.service.support.PermissionIdentityDisplaySupport;
import com.czh.datasmart.govern.permission.support.PermissionRoleCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.regex.Pattern;

/**
 * Implementation of the project creation approval workflow.
 *
 * <p>The key product rule is that a user's request to create a project is not a project yet. It must not grant project
 * data scope, must not expose datasource or sync-task visibility, and must not appear in the project switcher. Approval
 * turns the request into a real project by calling {@link PermissionProjectService#createProject}, which keeps owner
 * membership, audit and downstream gateway headers on the existing project-control-plane path.</p>
 */
@Service
@RequiredArgsConstructor
public class PermissionProjectCreationRequestServiceImpl implements PermissionProjectCreationRequestService {

    private static final long PLATFORM_TENANT_ID = 0L;
    private static final long DEFAULT_CURRENT = 1L;
    private static final long DEFAULT_PAGE_SIZE = 20L;
    private static final long MAX_PAGE_SIZE = 100L;
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_CANCELLED = "CANCELLED";
    private static final String DEFAULT_PROJECT_TYPE = "DATA_GOVERNANCE";
    private static final Pattern PROJECT_CODE_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,63}$");
    private static final Set<String> APPLY_ROLES = Set.of(
            PermissionRoleCode.ORDINARY_USER.name(),
            PermissionRoleCode.PROJECT_OWNER.name(),
            PermissionRoleCode.OPERATOR.name()
    );
    private static final Set<String> REVIEW_ROLES = Set.of(
            PermissionRoleCode.TENANT_ADMINISTRATOR.name(),
            PermissionRoleCode.PLATFORM_ADMINISTRATOR.name()
    );

    private final PermissionProjectCreationRequestMapper creationRequestMapper;
    private final PermissionProjectMapper projectMapper;
    private final PermissionProjectService projectService;
    private final PermissionIdentityDisplaySupport identityDisplaySupport;

    @Override
    @Transactional
    public ProjectCreationRequestMutationResult apply(ProjectCreationRequestApplyRequest request,
                                                      PermissionActorContext actorContext) {
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "Project creation request cannot be null");
        }
        String actorRole = requireRole(actorContext);
        if (!APPLY_ROLES.contains(actorRole)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Current role should use administrator project management instead of creation request: " + actorRole);
        }
        Long applicantActorId = requirePositive(actorContext.actorId(), "actorId");
        Long targetTenantId = resolveTargetTenantId(request.tenantId(), actorContext, actorRole);
        Long ownerActorId = request.ownerActorId() == null ? applicantActorId : request.ownerActorId();
        if (!ownerActorId.equals(applicantActorId)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Normal users can only request projects for themselves as owner");
        }
        Long applicationId = normalizeOptionalPositive(request.applicationId(), "applicationId");
        validateApplicationIfPresent(targetTenantId, applicationId);
        String projectCode = normalizeProjectCode(request.projectCode());
        ensureProjectCodeAvailable(targetTenantId, projectCode);
        ensureNoDuplicatePendingRequest(targetTenantId, applicationId, applicantActorId, projectCode, request.projectName());

        LocalDateTime now = LocalDateTime.now();
        PermissionProjectCreationRequest creationRequest = new PermissionProjectCreationRequest();
        creationRequest.setTenantId(targetTenantId);
        creationRequest.setApplicationId(applicationId);
        creationRequest.setProjectCode(projectCode);
        creationRequest.setProjectName(requiredText(request.projectName(), "projectName"));
        creationRequest.setProjectType(defaultText(normalizeCode(request.projectType()), DEFAULT_PROJECT_TYPE));
        creationRequest.setApplicantActorId(applicantActorId);
        creationRequest.setApplicantName(trimToNull(request.applicantName()));
        creationRequest.setOwnerActorId(ownerActorId);
        creationRequest.setDescription(trimToNull(request.description()));
        creationRequest.setRequestReason(trimToNull(request.requestReason()));
        creationRequest.setStatus(STATUS_PENDING);
        creationRequest.setCreateTime(now);
        creationRequest.setUpdateTime(now);
        creationRequestMapper.insert(creationRequest);
        return result(creationRequest, "Project creation request submitted and waiting for tenant administrator review");
    }

    @Override
    public PlatformPageResponse<ProjectCreationRequestView> pageMyRequests(ProjectCreationRequestQueryCriteria criteria,
                                                                          PermissionActorContext actorContext) {
        Long actorId = requirePositive(actorContext == null ? null : actorContext.actorId(), "actorId");
        ProjectCreationRequestQueryCriteria safeCriteria = safeCriteria(criteria);
        LambdaQueryWrapper<PermissionProjectCreationRequest> wrapper = basePageWrapper(safeCriteria)
                .eq(PermissionProjectCreationRequest::getApplicantActorId, actorId);
        Long tenantId = safeCriteria.tenantId() == null ? normalizeTenantId(actorContext.tenantId()) : safeCriteria.tenantId();
        wrapper.eq(tenantId != null, PermissionProjectCreationRequest::getTenantId, tenantId);
        return page(wrapper, safeCriteria.current(), safeCriteria.size());
    }

    @Override
    public PlatformPageResponse<ProjectCreationRequestView> pageApprovalRequests(ProjectCreationRequestQueryCriteria criteria,
                                                                                PermissionActorContext actorContext) {
        String actorRole = requireRole(actorContext);
        if (!REVIEW_ROLES.contains(actorRole)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Current role cannot review project creation requests: " + actorRole);
        }
        ProjectCreationRequestQueryCriteria safeCriteria = safeCriteria(criteria);
        LambdaQueryWrapper<PermissionProjectCreationRequest> wrapper = basePageWrapper(safeCriteria);
        applyReviewerScope(wrapper, safeCriteria, actorContext, actorRole);
        eqIfPresent(wrapper, PermissionProjectCreationRequest::getApplicantActorId, safeCriteria.applicantActorId());
        return page(wrapper, safeCriteria.current(), safeCriteria.size());
    }

    @Override
    @Transactional
    public ProjectCreationRequestMutationResult approve(Long requestId,
                                                        ProjectCreationRequestReviewRequest request,
                                                        PermissionActorContext actorContext) {
        PermissionProjectCreationRequest creationRequest = findPendingRequest(requestId);
        String actorRole = requireRole(actorContext);
        validateReviewerCanMutate(creationRequest, actorContext, actorRole);

        Long targetApplicationId = firstNonNull(request == null ? null : request.applicationId(),
                creationRequest.getApplicationId());
        Long ownerActorId = firstNonNull(request == null ? null : request.ownerActorId(),
                creationRequest.getOwnerActorId());
        String projectCode = defaultText(normalizeProjectCode(request == null ? null : request.projectCode()),
                creationRequest.getProjectCode());
        String projectName = defaultText(trimToNull(request == null ? null : request.projectName()),
                creationRequest.getProjectName());
        String projectType = defaultText(normalizeCode(request == null ? null : request.projectType()),
                creationRequest.getProjectType());
        String description = request != null && request.description() != null
                ? trimToNull(request.description())
                : creationRequest.getDescription();

        PermissionProjectMutationResult project = projectService.createProject(
                new PermissionProjectCreateRequest(
                        creationRequest.getTenantId(),
                        targetApplicationId,
                        projectCode,
                        projectName,
                        projectType,
                        ownerActorId,
                        description,
                        defaultText(request == null ? null : request.reviewComment(),
                                "Approved project creation request #" + creationRequest.getId())),
                actorContext);

        LocalDateTime now = LocalDateTime.now();
        creationRequest.setStatus(STATUS_APPROVED);
        creationRequest.setReviewerActorId(actorContext.actorId());
        creationRequest.setReviewerActorRole(actorRole);
        creationRequest.setReviewComment(trimToNull(request == null ? null : request.reviewComment()));
        creationRequest.setReviewTime(now);
        creationRequest.setCreatedProjectId(project.projectId());
        creationRequest.setUpdateTime(now);
        creationRequestMapper.updateById(creationRequest);
        return result(creationRequest, "Project creation request approved and project created");
    }

    @Override
    @Transactional
    public ProjectCreationRequestMutationResult reject(Long requestId,
                                                       ProjectCreationRequestReviewRequest request,
                                                       PermissionActorContext actorContext) {
        PermissionProjectCreationRequest creationRequest = findPendingRequest(requestId);
        String actorRole = requireRole(actorContext);
        validateReviewerCanMutate(creationRequest, actorContext, actorRole);
        LocalDateTime now = LocalDateTime.now();
        creationRequest.setStatus(STATUS_REJECTED);
        creationRequest.setReviewerActorId(actorContext.actorId());
        creationRequest.setReviewerActorRole(actorRole);
        creationRequest.setReviewComment(trimToNull(request == null ? null : request.reviewComment()));
        creationRequest.setReviewTime(now);
        creationRequest.setUpdateTime(now);
        creationRequestMapper.updateById(creationRequest);
        return result(creationRequest, "Project creation request rejected");
    }

    @Override
    @Transactional
    public ProjectCreationRequestMutationResult cancel(Long requestId, PermissionActorContext actorContext) {
        PermissionProjectCreationRequest creationRequest = findPendingRequest(requestId);
        Long actorId = requirePositive(actorContext == null ? null : actorContext.actorId(), "actorId");
        if (!actorId.equals(creationRequest.getApplicantActorId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Only the applicant can cancel this pending project creation request");
        }
        creationRequest.setStatus(STATUS_CANCELLED);
        creationRequest.setUpdateTime(LocalDateTime.now());
        creationRequestMapper.updateById(creationRequest);
        return result(creationRequest, "Project creation request cancelled");
    }

    private LambdaQueryWrapper<PermissionProjectCreationRequest> basePageWrapper(
            ProjectCreationRequestQueryCriteria criteria) {
        LambdaQueryWrapper<PermissionProjectCreationRequest> wrapper =
                new LambdaQueryWrapper<PermissionProjectCreationRequest>()
                        .orderByDesc(PermissionProjectCreationRequest::getUpdateTime)
                        .orderByDesc(PermissionProjectCreationRequest::getId);
        eqIfPresent(wrapper, PermissionProjectCreationRequest::getApplicationId, criteria.applicationId());
        eqIfPresent(wrapper, PermissionProjectCreationRequest::getCreatedProjectId, criteria.createdProjectId());
        String status = normalizeCode(criteria.status());
        if (status != null) {
            if (!Set.of(STATUS_PENDING, STATUS_APPROVED, STATUS_REJECTED, STATUS_CANCELLED).contains(status)) {
                throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                        "Unsupported request status: " + status);
            }
            wrapper.eq(PermissionProjectCreationRequest::getStatus, status);
        }
        return wrapper;
    }

    private void applyReviewerScope(LambdaQueryWrapper<PermissionProjectCreationRequest> wrapper,
                                    ProjectCreationRequestQueryCriteria criteria,
                                    PermissionActorContext actorContext,
                                    String actorRole) {
        if (PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(actorRole)) {
            eqIfPresent(wrapper, PermissionProjectCreationRequest::getTenantId, criteria.tenantId());
            return;
        }
        Long actorTenantId = requirePositive(normalizeTenantId(actorContext.tenantId()), "actorTenantId");
        Long requestedTenantId = criteria.tenantId() == null ? actorTenantId : criteria.tenantId();
        if (!actorTenantId.equals(requestedTenantId)) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "Reviewer cannot query project creation requests from another tenant");
        }
        wrapper.eq(PermissionProjectCreationRequest::getTenantId, actorTenantId);
    }

    private void validateReviewerCanMutate(PermissionProjectCreationRequest request,
                                           PermissionActorContext actorContext,
                                           String actorRole) {
        if (!REVIEW_ROLES.contains(actorRole)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Current role cannot review project creation requests: " + actorRole);
        }
        if (PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(actorRole)) {
            return;
        }
        Long actorTenantId = requirePositive(normalizeTenantId(actorContext.tenantId()), "actorTenantId");
        if (!actorTenantId.equals(request.getTenantId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "Reviewer cannot review project creation requests from another tenant");
        }
    }

    private PermissionProjectCreationRequest findPendingRequest(Long requestId) {
        Long id = requirePositive(requestId, "requestId");
        PermissionProjectCreationRequest request = creationRequestMapper.selectById(id);
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "Project creation request does not exist: " + id);
        }
        if (!STATUS_PENDING.equals(normalizeCode(request.getStatus()))) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "Only PENDING project creation requests can be changed");
        }
        return request;
    }

    private void validateApplicationIfPresent(Long tenantId, Long applicationId) {
        if (applicationId == null) {
            return;
        }
        if (projectMapper.countActiveApplication(tenantId, applicationId) <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "Target application does not exist, is disabled, or does not belong to tenant: " + applicationId);
        }
    }

    private void ensureProjectCodeAvailable(Long tenantId, String projectCode) {
        if (projectCode == null) {
            return;
        }
        Long existing = projectMapper.selectCount(new LambdaQueryWrapper<PermissionProject>()
                .eq(PermissionProject::getTenantId, tenantId)
                .eq(PermissionProject::getProjectCode, projectCode));
        if (existing != null && existing > 0) {
            throw new PlatformBusinessException(PlatformErrorCode.DUPLICATE_OPERATION,
                    "Project code already exists in this tenant: " + projectCode);
        }
        Long pending = creationRequestMapper.selectCount(new LambdaQueryWrapper<PermissionProjectCreationRequest>()
                .eq(PermissionProjectCreationRequest::getTenantId, tenantId)
                .eq(PermissionProjectCreationRequest::getProjectCode, projectCode)
                .eq(PermissionProjectCreationRequest::getStatus, STATUS_PENDING));
        if (pending != null && pending > 0) {
            throw new PlatformBusinessException(PlatformErrorCode.DUPLICATE_OPERATION,
                    "A pending project creation request already uses projectCode: " + projectCode);
        }
    }

    private void ensureNoDuplicatePendingRequest(Long tenantId,
                                                 Long applicationId,
                                                 Long applicantActorId,
                                                 String projectCode,
                                                 String projectName) {
        LambdaQueryWrapper<PermissionProjectCreationRequest> wrapper =
                new LambdaQueryWrapper<PermissionProjectCreationRequest>()
                        .eq(PermissionProjectCreationRequest::getTenantId, tenantId)
                        .eq(PermissionProjectCreationRequest::getApplicantActorId, applicantActorId)
                        .eq(PermissionProjectCreationRequest::getStatus, STATUS_PENDING);
        if (applicationId == null) {
            wrapper.isNull(PermissionProjectCreationRequest::getApplicationId);
        } else {
            wrapper.eq(PermissionProjectCreationRequest::getApplicationId, applicationId);
        }
        if (projectCode != null) {
            wrapper.eq(PermissionProjectCreationRequest::getProjectCode, projectCode);
        } else {
            wrapper.eq(PermissionProjectCreationRequest::getProjectName, requiredText(projectName, "projectName"));
        }
        Long pending = creationRequestMapper.selectCount(wrapper);
        if (pending != null && pending > 0) {
            throw new PlatformBusinessException(PlatformErrorCode.DUPLICATE_OPERATION,
                    "A pending project creation request already exists; please wait for review or cancel it first");
        }
    }

    private PlatformPageResponse<ProjectCreationRequestView> page(
            LambdaQueryWrapper<PermissionProjectCreationRequest> wrapper,
            Long current,
            Long size) {
        Page<PermissionProjectCreationRequest> page =
                creationRequestMapper.selectPage(pageRequest(current, size), wrapper);
        Map<Long, String> usernames = identityDisplaySupport.usernames(page.getRecords().stream()
                .flatMap(request -> Stream.of(
                        request.getApplicantActorId(), request.getOwnerActorId(), request.getReviewerActorId()))
                .toList());
        return PlatformPageResponse.of(
                page.getCurrent(),
                page.getSize(),
                page.getTotal(),
                page.getRecords().stream()
                        .map(request -> ProjectCreationRequestView.from(request, usernames))
                        .toList());
    }

    private ProjectCreationRequestMutationResult result(PermissionProjectCreationRequest request, String message) {
        return new ProjectCreationRequestMutationResult(
                request.getId(),
                request.getTenantId(),
                request.getApplicationId(),
                request.getApplicantActorId(),
                request.getStatus(),
                request.getCreatedProjectId(),
                message
        );
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
                    "Cannot request project creation for another tenant");
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

    private Long normalizeOptionalPositive(Long value, String fieldName) {
        if (value == null) {
            return null;
        }
        return requirePositive(value, fieldName);
    }

    private String normalizeProjectCode(String value) {
        String code = normalizeCode(value);
        if (code != null && !PROJECT_CODE_PATTERN.matcher(code).matches()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "projectCode can only contain uppercase letters, digits, underscore or dash, max length 64: " + value);
        }
        return code;
    }

    private ProjectCreationRequestQueryCriteria safeCriteria(ProjectCreationRequestQueryCriteria criteria) {
        return criteria == null
                ? new ProjectCreationRequestQueryCriteria(null, null, null, null, null, null, null)
                : criteria;
    }

    private Page<PermissionProjectCreationRequest> pageRequest(Long current, Long size) {
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

    private String requiredText(String value, String fieldName) {
        String text = trimToNull(value);
        if (text == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, fieldName + " cannot be blank");
        }
        return text;
    }

    private String defaultText(String value, String defaultValue) {
        String text = trimToNull(value);
        return text == null ? defaultValue : text;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Long firstNonNull(Long first, Long second) {
        return first == null ? second : first;
    }

    private <T> void eqIfPresent(LambdaQueryWrapper<T> wrapper,
                                 SFunction<T, ?> column,
                                 Long value) {
        if (value != null) {
            wrapper.eq(column, value);
        }
    }
}

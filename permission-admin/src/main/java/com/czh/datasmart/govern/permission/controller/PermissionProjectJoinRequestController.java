/**
 * @Author : Cui
 * @Date: 2026/07/10 20:42
 * @Description DataSmart Govern Backend - PermissionProjectJoinRequestController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.ProjectJoinCandidateView;
import com.czh.datasmart.govern.permission.controller.dto.ProjectJoinRequestApplyRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectJoinRequestMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.ProjectJoinRequestQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.ProjectJoinRequestReviewRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectJoinRequestView;
import com.czh.datasmart.govern.permission.service.PermissionProjectJoinRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Project join request workflow controller.
 *
 * <p>Normal users use {@code apply} and {@code my}; administrators and project owners use {@code approvals},
 * {@code approve} and {@code reject}. The controller only assembles HTTP context. The service repeats all tenant,
 * role and project-owner checks so direct API calls cannot bypass the frontend.</p>
 */
@RestController
@RequestMapping({"/permissions/project-join-requests", "/api/permission/project-join-requests"})
@RequiredArgsConstructor
public class PermissionProjectJoinRequestController {

    private final PermissionProjectJoinRequestService joinRequestService;

    /**
     * Returns the active project-name directory used by the join application selector.
     *
     * <p>The service constrains non-platform users to their own tenant. Only low-sensitive project master-data
     * fields are returned, so an applicant can select a readable name without learning project business data.</p>
     */
    @GetMapping("/candidates")
    public PlatformApiResponse<PlatformPageResponse<ProjectJoinCandidateView>> pageJoinCandidates(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long current,
            @RequestParam(required = false) Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(joinRequestService.pageJoinCandidates(
                tenantId, keyword, current, size,
                actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    @PostMapping
    public PlatformApiResponse<ProjectJoinRequestMutationResult> apply(
            @Valid @RequestBody ProjectJoinRequestApplyRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("Project join request submitted",
                joinRequestService.apply(request, actorContext(actorTenantId, actorId, actorRole, traceId)),
                traceId);
    }

    @GetMapping("/my")
    public PlatformApiResponse<PlatformPageResponse<ProjectJoinRequestView>> pageMyRequests(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long current,
            @RequestParam(required = false) Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        ProjectJoinRequestQueryCriteria criteria =
                new ProjectJoinRequestQueryCriteria(tenantId, projectId, null, status, current, size);
        return PlatformApiResponse.success(joinRequestService.pageMyRequests(
                criteria, actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    @GetMapping("/approvals")
    public PlatformApiResponse<PlatformPageResponse<ProjectJoinRequestView>> pageApprovalRequests(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long applicantActorId,
            @RequestParam(required = false, defaultValue = "PENDING") String status,
            @RequestParam(required = false) Long current,
            @RequestParam(required = false) Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        ProjectJoinRequestQueryCriteria criteria =
                new ProjectJoinRequestQueryCriteria(tenantId, projectId, applicantActorId, status, current, size);
        return PlatformApiResponse.success(joinRequestService.pageApprovalRequests(
                criteria, actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    @PostMapping("/{requestId}/approve")
    public PlatformApiResponse<ProjectJoinRequestMutationResult> approve(
            @PathVariable Long requestId,
            @Valid @RequestBody(required = false) ProjectJoinRequestReviewRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("Project join request approved",
                joinRequestService.approve(requestId, request, actorContext(actorTenantId, actorId, actorRole, traceId)),
                traceId);
    }

    @PostMapping("/{requestId}/reject")
    public PlatformApiResponse<ProjectJoinRequestMutationResult> reject(
            @PathVariable Long requestId,
            @Valid @RequestBody(required = false) ProjectJoinRequestReviewRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("Project join request rejected",
                joinRequestService.reject(requestId, request, actorContext(actorTenantId, actorId, actorRole, traceId)),
                traceId);
    }

    @PostMapping("/{requestId}/cancel")
    public PlatformApiResponse<ProjectJoinRequestMutationResult> cancel(
            @PathVariable Long requestId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("Project join request cancelled",
                joinRequestService.cancel(requestId, actorContext(actorTenantId, actorId, actorRole, traceId)),
                traceId);
    }

    private PermissionActorContext actorContext(Long tenantId, Long actorId, String actorRole, String traceId) {
        return new PermissionActorContext(tenantId, actorId, actorRole, traceId);
    }
}

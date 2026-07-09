/**
 * @Author : Cui
 * @Date: 2026/07/10 04:29
 * @Description DataSmart Govern Backend - PermissionProjectCreationRequestController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.ProjectCreationRequestApplyRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectCreationRequestMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.ProjectCreationRequestQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.ProjectCreationRequestReviewRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectCreationRequestView;
import com.czh.datasmart.govern.permission.service.PermissionProjectCreationRequestService;
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
 * Project creation request workflow controller.
 *
 * <p>Normal users submit requests here instead of directly creating projects. Tenant and platform administrators review
 * the requests. The service layer repeats all role and tenant checks, so direct API calls cannot bypass hidden or
 * disabled frontend buttons.</p>
 */
@RestController
@RequestMapping({"/permissions/project-creation-requests", "/api/permission/project-creation-requests"})
@RequiredArgsConstructor
public class PermissionProjectCreationRequestController {

    private final PermissionProjectCreationRequestService creationRequestService;

    @PostMapping
    public PlatformApiResponse<ProjectCreationRequestMutationResult> apply(
            @Valid @RequestBody ProjectCreationRequestApplyRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("Project creation request submitted",
                creationRequestService.apply(request, actorContext(actorTenantId, actorId, actorRole, traceId)),
                traceId);
    }

    @GetMapping("/my")
    public PlatformApiResponse<PlatformPageResponse<ProjectCreationRequestView>> pageMyRequests(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long applicationId,
            @RequestParam(required = false) Long createdProjectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long current,
            @RequestParam(required = false) Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        ProjectCreationRequestQueryCriteria criteria =
                new ProjectCreationRequestQueryCriteria(tenantId, applicationId, null, createdProjectId,
                        status, current, size);
        return PlatformApiResponse.success(creationRequestService.pageMyRequests(
                criteria, actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    @GetMapping("/approvals")
    public PlatformApiResponse<PlatformPageResponse<ProjectCreationRequestView>> pageApprovalRequests(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long applicationId,
            @RequestParam(required = false) Long applicantActorId,
            @RequestParam(required = false) Long createdProjectId,
            @RequestParam(required = false, defaultValue = "PENDING") String status,
            @RequestParam(required = false) Long current,
            @RequestParam(required = false) Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        ProjectCreationRequestQueryCriteria criteria =
                new ProjectCreationRequestQueryCriteria(tenantId, applicationId, applicantActorId, createdProjectId,
                        status, current, size);
        return PlatformApiResponse.success(creationRequestService.pageApprovalRequests(
                criteria, actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    @PostMapping("/{requestId}/approve")
    public PlatformApiResponse<ProjectCreationRequestMutationResult> approve(
            @PathVariable Long requestId,
            @Valid @RequestBody(required = false) ProjectCreationRequestReviewRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("Project creation request approved",
                creationRequestService.approve(requestId, request,
                        actorContext(actorTenantId, actorId, actorRole, traceId)),
                traceId);
    }

    @PostMapping("/{requestId}/reject")
    public PlatformApiResponse<ProjectCreationRequestMutationResult> reject(
            @PathVariable Long requestId,
            @Valid @RequestBody(required = false) ProjectCreationRequestReviewRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("Project creation request rejected",
                creationRequestService.reject(requestId, request,
                        actorContext(actorTenantId, actorId, actorRole, traceId)),
                traceId);
    }

    @PostMapping("/{requestId}/cancel")
    public PlatformApiResponse<ProjectCreationRequestMutationResult> cancel(
            @PathVariable Long requestId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("Project creation request cancelled",
                creationRequestService.cancel(requestId, actorContext(actorTenantId, actorId, actorRole, traceId)),
                traceId);
    }

    private PermissionActorContext actorContext(Long tenantId, Long actorId, String actorRole, String traceId) {
        return new PermissionActorContext(tenantId, actorId, actorRole, traceId);
    }
}

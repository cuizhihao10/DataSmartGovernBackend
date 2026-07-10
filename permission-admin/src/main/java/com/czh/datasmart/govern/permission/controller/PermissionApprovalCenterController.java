/**
 * @Author : Cui
 * @Date: 2026/07/10 11:39
 * @Description DataSmart Govern Backend - PermissionApprovalCenterController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.permission.controller.dto.ApprovalCenterItemView;
import com.czh.datasmart.govern.permission.controller.dto.ApprovalCenterMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.ApprovalCenterQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.ApprovalCenterReviewRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.service.PermissionApprovalCenterService;
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
 * Unified approval-center HTTP API.
 *
 * <p>All authenticated users can query only their own applications. Tenant and platform administrators can query and
 * process pending work. Concrete workflow services still enforce their own status and tenant constraints.</p>
 */
@RestController
@RequestMapping({"/permissions/approval-center", "/api/permission/approval-center"})
@RequiredArgsConstructor
public class PermissionApprovalCenterController {

    private final PermissionApprovalCenterService approvalCenterService;

    @GetMapping("/my")
    public PlatformApiResponse<PlatformPageResponse<ApprovalCenterItemView>> pageMyRequests(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String requestType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long current,
            @RequestParam(required = false) Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        ApprovalCenterQueryCriteria criteria = new ApprovalCenterQueryCriteria(
                tenantId, requestType, status, current, size);
        return PlatformApiResponse.success(approvalCenterService.pageMyRequests(
                criteria, actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    @GetMapping("/pending")
    public PlatformApiResponse<PlatformPageResponse<ApprovalCenterItemView>> pagePendingApprovals(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String requestType,
            @RequestParam(required = false, defaultValue = "PENDING") String status,
            @RequestParam(required = false) Long current,
            @RequestParam(required = false) Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        ApprovalCenterQueryCriteria criteria = new ApprovalCenterQueryCriteria(
                tenantId, requestType, status, current, size);
        return PlatformApiResponse.success(approvalCenterService.pagePendingApprovals(
                criteria, actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    @PostMapping("/{requestType}/{requestId}/approve")
    public PlatformApiResponse<ApprovalCenterMutationResult> approve(
            @PathVariable String requestType,
            @PathVariable Long requestId,
            @Valid @RequestBody(required = false) ApprovalCenterReviewRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("Approval completed",
                approvalCenterService.approve(requestType, requestId, request,
                        actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    @PostMapping("/{requestType}/{requestId}/reject")
    public PlatformApiResponse<ApprovalCenterMutationResult> reject(
            @PathVariable String requestType,
            @PathVariable Long requestId,
            @Valid @RequestBody(required = false) ApprovalCenterReviewRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("Request rejected",
                approvalCenterService.reject(requestType, requestId, request,
                        actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    @PostMapping("/{requestType}/{requestId}/cancel")
    public PlatformApiResponse<ApprovalCenterMutationResult> cancel(
            @PathVariable String requestType,
            @PathVariable Long requestId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("Request cancelled",
                approvalCenterService.cancel(requestType, requestId,
                        actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    private PermissionActorContext actorContext(Long tenantId, Long actorId, String actorRole, String traceId) {
        return new PermissionActorContext(tenantId, actorId, actorRole, traceId);
    }
}

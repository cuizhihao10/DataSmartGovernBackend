/**
 * @Author : Cui
 * @Date: 2026/07/10 14:20
 * @Description DataSmart Govern Backend - PermissionTenantController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.PermissionTenantOpenRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionTenantQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.PermissionTenantStatusChangeRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionTenantUpdateRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionTenantView;
import com.czh.datasmart.govern.permission.service.PermissionTenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台租户开通和生命周期管理 API。
 *
 * <p>所有接口都要求平台超级管理员。Controller 只负责协议转换，Service 会再次校验可信 Header 中的角色，
 * 因此前端隐藏菜单或直接伪造 tenantId 都不能绕过平台级权限边界。</p>
 */
@RestController
@RequestMapping({"/permissions/tenants", "/api/permission/tenants"})
@RequiredArgsConstructor
public class PermissionTenantController {

    private final PermissionTenantService tenantService;

    @GetMapping
    public PlatformApiResponse<PlatformPageResponse<PermissionTenantView>> pageTenants(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) String tenantCode,
            @RequestParam(required = false) String tenantName,
            @RequestParam(required = false) String tenantType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long current,
            @RequestParam(required = false) Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        PermissionTenantQueryCriteria criteria = new PermissionTenantQueryCriteria(
                tenantId, tenantCode, tenantName, tenantType, status, current, size);
        return PlatformApiResponse.success(tenantService.pageTenants(
                criteria, actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    @GetMapping("/{tenantId}")
    public PlatformApiResponse<PermissionTenantView> getTenant(
            @PathVariable Long tenantId,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(tenantService.getTenant(
                tenantId, actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    @PostMapping
    public PlatformApiResponse<PermissionTenantView> openTenant(
            @Valid @RequestBody PermissionTenantOpenRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("租户和 FlashSync 应用开通成功", tenantService.openTenant(
                request, actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    @PutMapping("/{tenantId}")
    public PlatformApiResponse<PermissionTenantView> updateTenant(
            @PathVariable Long tenantId,
            @Valid @RequestBody PermissionTenantUpdateRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("租户资料更新成功", tenantService.updateTenant(
                tenantId, request, actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    @PostMapping("/{tenantId}/activate")
    public PlatformApiResponse<PermissionTenantView> activateTenant(
            @PathVariable Long tenantId,
            @Valid @RequestBody PermissionTenantStatusChangeRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("租户已恢复启用", tenantService.activateTenant(
                tenantId, request, actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    @PostMapping("/{tenantId}/suspend")
    public PlatformApiResponse<PermissionTenantView> suspendTenant(
            @PathVariable Long tenantId,
            @Valid @RequestBody PermissionTenantStatusChangeRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("租户已暂停", tenantService.suspendTenant(
                tenantId, request, actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    @PostMapping("/{tenantId}/close")
    public PlatformApiResponse<PermissionTenantView> closeTenant(
            @PathVariable Long tenantId,
            @Valid @RequestBody PermissionTenantStatusChangeRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("租户已关闭并保留审计事实", tenantService.closeTenant(
                tenantId, request, actorContext(actorTenantId, actorId, actorRole, traceId)), traceId);
    }

    private PermissionActorContext actorContext(Long tenantId, Long actorId, String actorRole, String traceId) {
        return new PermissionActorContext(tenantId, actorId, actorRole, traceId);
    }
}

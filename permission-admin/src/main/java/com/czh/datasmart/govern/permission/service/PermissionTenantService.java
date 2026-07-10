/**
 * @Author : Cui
 * @Date: 2026/07/10 14:10
 * @Description DataSmart Govern Backend - PermissionTenantService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service;

import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.PermissionTenantOpenRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionTenantQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.PermissionTenantStatusChangeRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionTenantUpdateRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionTenantView;

/**
 * 平台租户开通与生命周期管理服务。
 */
public interface PermissionTenantService {

    PlatformPageResponse<PermissionTenantView> pageTenants(PermissionTenantQueryCriteria criteria,
                                                           PermissionActorContext actorContext);

    PermissionTenantView getTenant(Long tenantId, PermissionActorContext actorContext);

    PermissionTenantView openTenant(PermissionTenantOpenRequest request, PermissionActorContext actorContext);

    PermissionTenantView updateTenant(Long tenantId,
                                      PermissionTenantUpdateRequest request,
                                      PermissionActorContext actorContext);

    PermissionTenantView activateTenant(Long tenantId,
                                        PermissionTenantStatusChangeRequest request,
                                        PermissionActorContext actorContext);

    PermissionTenantView suspendTenant(Long tenantId,
                                       PermissionTenantStatusChangeRequest request,
                                       PermissionActorContext actorContext);

    PermissionTenantView closeTenant(Long tenantId,
                                     PermissionTenantStatusChangeRequest request,
                                     PermissionActorContext actorContext);
}

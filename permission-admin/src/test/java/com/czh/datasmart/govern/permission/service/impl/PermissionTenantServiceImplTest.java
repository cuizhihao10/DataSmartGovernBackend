/**
 * @Author : Cui
 * @Date: 2026/07/10 14:25
 * @Description DataSmart Govern Backend - PermissionTenantServiceImplTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.PermissionTenantOpenRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionTenantStatusChangeRequest;
import com.czh.datasmart.govern.permission.entity.PermissionApplication;
import com.czh.datasmart.govern.permission.entity.PermissionTenant;
import com.czh.datasmart.govern.permission.mapper.PermissionApplicationMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionTenantMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionIdentityUserMapper;
import com.czh.datasmart.govern.permission.service.IdentityProvisioningService;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserProvisionResult;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserRegisterRequest;
import com.czh.datasmart.govern.permission.service.support.PermissionTenantAuditSupport;
import com.czh.datasmart.govern.permission.support.PermissionRoleCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台开租与租户生命周期安全边界测试。
 */
class PermissionTenantServiceImplTest {

    private PermissionTenantMapper tenantMapper;
    private PermissionApplicationMapper applicationMapper;
    private PermissionIdentityUserMapper identityUserMapper;
    private IdentityProvisioningService identityProvisioningService;
    private PermissionTenantAuditSupport auditSupport;
    private PermissionTenantServiceImpl service;

    @BeforeEach
    void setUp() {
        tenantMapper = mock(PermissionTenantMapper.class);
        applicationMapper = mock(PermissionApplicationMapper.class);
        identityUserMapper = mock(PermissionIdentityUserMapper.class);
        identityProvisioningService = mock(IdentityProvisioningService.class);
        auditSupport = mock(PermissionTenantAuditSupport.class);
        service = new PermissionTenantServiceImpl(tenantMapper, applicationMapper, identityUserMapper,
                identityProvisioningService, auditSupport);
    }

    @Test
    void platformAdministratorOpensTenantAndFlashSyncApplicationWithoutWorkspace() {
        when(tenantMapper.selectCount(any())).thenReturn(0L);
        when(tenantMapper.nextTenantId()).thenReturn(1000L);
        when(applicationMapper.nextApplicationId()).thenReturn(100000L);
        when(identityProvisioningService.registerUser(any(), any())).thenReturn(new IdentityUserProvisionResult(
                "REGISTER_USER", "KEYCLOAK_ADMIN_API", "kc-admin-1000", 1L, "acme-admin", "a***@acme.test",
                1000L, 2001L, "TENANT_ADMINISTRATOR", "USER", null, "ACTIVE", "created", "low-sensitive", List.of()));

        var result = service.openTenant(
                new PermissionTenantOpenRequest(
                        "acme", "Acme 制造", "BUSINESS", "PROFESSIONAL", 2001L,
                        null, null, "acme-admin", "admin@acme.test", "Admin", "Acme", "DataSmart@123", true,
                        "制造业客户租户", "签约后开通"),
                actor(1L, 9001L, PermissionRoleCode.PLATFORM_ADMINISTRATOR));

        assertThat(result.tenantId()).isEqualTo(1000L);
        assertThat(result.applicationId()).isEqualTo(100000L);
        assertThat(result.applicationCode()).isEqualTo("FLASHSYNC");
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.administratorUsername()).isEqualTo("acme-admin");

        ArgumentCaptor<PermissionTenant> tenantCaptor = ArgumentCaptor.forClass(PermissionTenant.class);
        verify(tenantMapper).insert(tenantCaptor.capture());
        assertThat(tenantCaptor.getValue().getTenantCode()).isEqualTo("ACME");

        ArgumentCaptor<PermissionApplication> applicationCaptor = ArgumentCaptor.forClass(PermissionApplication.class);
        verify(applicationMapper).insert(applicationCaptor.capture());
        assertThat(applicationCaptor.getValue().getTenantId()).isEqualTo(1000L);
        assertThat(applicationCaptor.getValue().getApplicationName()).isEqualTo("FlashSync");
        assertThat(applicationCaptor.getValue().getOwnerActorId()).isEqualTo(2001L);
        assertThat(PermissionApplication.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("defaultWorkspaceId", "workspaceId");
        ArgumentCaptor<IdentityUserRegisterRequest> identityCaptor =
                ArgumentCaptor.forClass(IdentityUserRegisterRequest.class);
        verify(identityProvisioningService).registerUser(identityCaptor.capture(), any());
        assertThat(identityCaptor.getValue().tenantId()).isEqualTo(1000L);
        assertThat(identityCaptor.getValue().username()).isEqualTo("acme-admin");
        assertThat(identityCaptor.getValue().actorRole()).isEqualTo("TENANT_ADMINISTRATOR");
        assertThat(tenantCaptor.getValue().getOwnerActorId()).isEqualTo(2001L);
        verify(auditSupport).saveMutationAudit(any(), any(), any(), any(), any(), any());
    }

    @Test
    void tenantAdministratorCannotOpenTenant() {
        assertThatThrownBy(() -> service.openTenant(
                new PermissionTenantOpenRequest(
                        "ACME", "Acme", null, null, null, null, null,
                        "acme-admin", null, null, null, "DataSmart@123", true, null, "越权测试"),
                actor(10L, 1001L, PermissionRoleCode.TENANT_ADMINISTRATOR)))
                .isInstanceOfSatisfying(PlatformBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PlatformErrorCode.FORBIDDEN));

        verify(tenantMapper, never()).insert(any(PermissionTenant.class));
        verify(applicationMapper, never()).insert(any(PermissionApplication.class));
    }

    @Test
    void suspendingTenantAlsoDisablesFlashSyncApplication() {
        PermissionTenant tenant = tenant("ACTIVE");
        PermissionApplication application = application("ACTIVE");
        when(tenantMapper.selectById(1000L)).thenReturn(tenant);
        when(applicationMapper.selectOne(any())).thenReturn(application);

        var result = service.suspendTenant(1000L,
                new PermissionTenantStatusChangeRequest("客户合同暂停"),
                actor(1L, 9001L, PermissionRoleCode.PLATFORM_ADMINISTRATOR));

        assertThat(result.status()).isEqualTo("SUSPENDED");
        assertThat(result.applicationStatus()).isEqualTo("DISABLED");
        verify(tenantMapper).updateById(tenant);
        verify(applicationMapper).updateById(application);
        verify(auditSupport).saveMutationAudit(any(), any(), any(), any(), any(), any());
    }

    private PermissionActorContext actor(Long tenantId, Long actorId, PermissionRoleCode role) {
        return new PermissionActorContext(tenantId, actorId, role.name(), "trace-tenant-test");
    }

    private PermissionTenant tenant(String status) {
        PermissionTenant tenant = new PermissionTenant();
        tenant.setTenantId(1000L);
        tenant.setTenantCode("ACME");
        tenant.setTenantName("Acme 制造");
        tenant.setTenantType("BUSINESS");
        tenant.setPlanCode("PROFESSIONAL");
        tenant.setStatus(status);
        tenant.setDefaultApplicationCode("FLASHSYNC");
        return tenant;
    }

    private PermissionApplication application(String status) {
        PermissionApplication application = new PermissionApplication();
        application.setApplicationId(100000L);
        application.setTenantId(1000L);
        application.setApplicationCode("FLASHSYNC");
        application.setApplicationName("FlashSync");
        application.setStatus(status);
        return application;
    }
}

/**
 * @Author : Cui
 * @Date: 2026/07/05 04:18
 * @Description DataSmartGovernBackend - IdentityProvisioningServiceImplTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.permission.config.IdentityProvisioningProperties;
import com.czh.datasmart.govern.permission.controller.dto.AuthorizationSubjectCandidateQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.AuthorizationSubjectCandidateView;
import com.czh.datasmart.govern.permission.controller.dto.IdentityProvisioningCapabilityView;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserDisableRequest;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserPasswordResetRequest;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserProvisionResult;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserRegisterRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.entity.PermissionIdentityUser;
import com.czh.datasmart.govern.permission.entity.PermissionProjectMembership;
import com.czh.datasmart.govern.permission.mapper.PermissionIdentityUserMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionProjectMembershipMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionRoleMapper;
import com.czh.datasmart.govern.permission.service.identity.IdentityProviderAdminClient;
import com.czh.datasmart.govern.permission.service.identity.IdentityProviderOperationResult;
import com.czh.datasmart.govern.permission.service.identity.IdentityProviderUserCreateCommand;
import com.czh.datasmart.govern.permission.service.support.PermissionIdentityAuditSupport;
import com.czh.datasmart.govern.permission.support.PermissionRoleCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 身份供应服务测试。
 *
 * <p>这里验证的不是 Keycloak HTTP 协议细节，而是 permission-admin 自身必须守住的产品级契约：
 * 1. 登录认证归 Keycloak/企业 IdP，DataSmart 不保存密码、不签发登录 Token；
 * 2. permission-admin 只负责账号供应和本地影子身份映射；
 * 3. 租户管理员不能跨租户创建、禁用或重置账号；
 * 4. 普通用户、项目负责人、运营、审计员不能执行高风险账号管理动作；
 * 5. 响应与审计都只能携带低敏控制面事实，不能把密码、Token、client secret 带回业务层。
 *
 * <p>这些单元测试不启动 Spring 容器、不连接 PostgreSQL/Keycloak。
 * 原因是本阶段要优先固定业务边界，真实 Keycloak 联调交给 docker smoke 或集成测试处理。
 */
class IdentityProvisioningServiceImplTest {

    private IdentityProvisioningProperties properties;
    private IdentityProviderAdminClient identityProviderAdminClient;
    private PermissionIdentityUserMapper identityUserMapper;
    private PermissionRoleMapper roleMapper;
    private PermissionProjectMembershipMapper projectMembershipMapper;
    private PermissionIdentityAuditSupport auditSupport;
    private IdentityProvisioningServiceImpl service;

    /**
     * 每个测试重新创建 mock，避免上一个测试中的 MyBatis 查询桩影响当前测试。
     */
    @BeforeEach
    void setUp() {
        properties = new IdentityProvisioningProperties();
        properties.setEnabled(true);
        identityProviderAdminClient = mock(IdentityProviderAdminClient.class);
        identityUserMapper = mock(PermissionIdentityUserMapper.class);
        roleMapper = mock(PermissionRoleMapper.class);
        projectMembershipMapper = mock(PermissionProjectMembershipMapper.class);
        auditSupport = mock(PermissionIdentityAuditSupport.class);
        service = new IdentityProvisioningServiceImpl(
                properties,
                identityProviderAdminClient,
                identityUserMapper,
                roleMapper,
                projectMembershipMapper,
                auditSupport
        );
    }

    /**
     * 能力查询应告诉调用方“在哪里登录、DataSmart 保存什么”，但不能暴露任何 IdP 管理凭据。
     *
     * <p>这个接口未来会被管理后台或部署验收脚本调用。如果它误返回 admin endpoint、client secret、
     * admin password 或 access token，就会把平台最敏感的身份管理凭据扩散到前端和日志。
     */
    @Test
    void capabilitiesShouldExplainOidcLoginWithoutExposingSecrets() {
        properties.getKeycloak().setAdminPassword("SuperSecretAdminPassword");
        properties.getKeycloak().setAdminClientSecret("SuperSecretClientSecret");

        IdentityProvisioningCapabilityView capability = service.capabilities();
        String serializedLike = capability.toString();

        assertThat(capability.enabled()).isTrue();
        assertThat(capability.providerMode()).isEqualTo("KEYCLOAK_ADMIN_API");
        assertThat(capability.storesPasswordInDataSmart()).isFalse();
        assertThat(capability.storesShadowIdentityInDataSmart()).isTrue();
        assertThat(capability.note()).contains("Keycloak");
        assertThat(serializedLike)
                .doesNotContain("SuperSecretAdminPassword")
                .doesNotContain("SuperSecretClientSecret")
                .doesNotContain("access_token")
                .doesNotContain("refresh_token");
    }

    /**
     * 项目负责人查询候选时必须带 projectId。
     *
     * <p>这是为了避免“项目负责人给某条项目资源授权”退化成“项目负责人可以搜索整个租户用户”。
     * 真正商用场景中，用户候选列表本身也是权限信息：能看到谁、能把谁授权到资源上，都必须受项目边界约束。</p>
     */
    @Test
    void projectOwnerCannotQueryAuthorizationCandidatesWithoutProjectId() {
        AuthorizationSubjectCandidateQueryCriteria criteria = new AuthorizationSubjectCandidateQueryCriteria(
                10L, null, "USER", "alice", true, null, 1L, 10L);

        assertThatThrownBy(() -> service.pageAuthorizationSubjectCandidates(
                criteria,
                actor(10L, 1001L, PermissionRoleCode.PROJECT_OWNER)
        )).isInstanceOfSatisfying(PlatformBusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(PlatformErrorCode.FORBIDDEN));

        verify(projectMembershipMapper, never()).selectList(any());
        verify(identityUserMapper, never()).selectPage(any(), any());
    }

    /**
     * USER 候选应按项目成员收敛并返回可直接写入资源 ACL 的低敏字段。
     *
     * <p>本测试不验证 MyBatis 生成的 SQL，而是固定 service 层的业务合同：
     * 1. 先确认项目负责人确实属于该项目；
     * 2. 再读取该项目已启用成员 actorId；
     * 3. 最终返回 subjectType/subjectId/subjectName/subjectRole，前端可直接填充数据源授权请求；
     * 4. email 必须脱敏，避免授权弹窗泄露个人信息。</p>
     */
    @Test
    void userCandidatesShouldBeConstrainedByProjectMembersAndMaskEmail() {
        when(projectMembershipMapper.selectCount(any())).thenReturn(1L);
        PermissionProjectMembership owner = projectMembership(10L, 1001L, 101L);
        PermissionProjectMembership member = projectMembership(10L, 1004L, 101L);
        when(projectMembershipMapper.selectList(any())).thenReturn(List.of(owner, member));

        PermissionIdentityUser ordinaryUser = identityUser("kc-user-ordinary", 10L, 1004L);
        ordinaryUser.setUsername("ordinary-user");
        ordinaryUser.setEmail("ordinary-user@example.local");
        doAnswer(invocation -> {
            Page<PermissionIdentityUser> page = invocation.getArgument(0);
            page.setRecords(List.of(ordinaryUser));
            page.setTotal(1L);
            return page;
        }).when(identityUserMapper).selectPage(any(Page.class), any());

        PlatformPageResponse<AuthorizationSubjectCandidateView> response = service.pageAuthorizationSubjectCandidates(
                new AuthorizationSubjectCandidateQueryCriteria(10L, 101L, "USER", "ordinary", true, null, 1L, 20L),
                actor(10L, 1001L, PermissionRoleCode.PROJECT_OWNER)
        );

        assertThat(response.getTotal()).isEqualTo(1L);
        assertThat(response.getRecords()).hasSize(1);
        AuthorizationSubjectCandidateView candidate = response.getRecords().getFirst();
        assertThat(candidate.subjectType()).isEqualTo("USER");
        assertThat(candidate.subjectId()).isEqualTo("1004");
        assertThat(candidate.subjectName()).isEqualTo("ordinary-user");
        assertThat(candidate.subjectRole()).isEqualTo(PermissionRoleCode.ORDINARY_USER.name());
        assertThat(candidate.projectId()).isEqualTo(101L);
        assertThat(candidate.maskedEmail()).isEqualTo("o***@example.local");
        assertThat(candidate.selectable()).isTrue();
    }

    /**
     * 租户管理员在本租户内创建用户时，应调用 IdP 创建真实账号，并写入 DataSmart 影子身份。
     *
     * <p>这条测试体现当前登录注册设计的核心：请求里确实有初始密码，但密码只会出现在发给 IdP 的命令中。
     * 本地 `permission_identity_user` 表只保存 providerUserId、actorId、tenantId、role、workspace 等低敏映射。
     */
    @Test
    void tenantAdministratorCanRegisterUserInOwnTenantAndStoreOnlyShadowIdentity() {
        when(identityUserMapper.selectOne(any())).thenReturn(null).thenReturn(null);
        when(identityProviderAdminClient.createUser(any())).thenReturn(new IdentityProviderOperationResult(
                "kc-user-001",
                "CREATE_USER",
                "created",
                List.of("KEYCLOAK_USER_CREATED", "KEYCLOAK_ROLE_ASSIGNED")
        ));
        doAnswer(invocation -> {
            PermissionIdentityUser inserted = invocation.getArgument(0);
            inserted.setId(3001L);
            return 1;
        }).when(identityUserMapper).insert(any(PermissionIdentityUser.class));

        IdentityUserRegisterRequest request = new IdentityUserRegisterRequest(
                "alice",
                "alice@example.com",
                "Alice",
                "Chen",
                "TempPassword!123",
                true,
                10L,
                2001L,
                PermissionRoleCode.ORDINARY_USER.name(),
                "USER",
                "workspace-main",
                true,
                false,
                "新员工入职"
        );

        IdentityUserProvisionResult result = service.registerUser(
                request,
                actor(10L, 1001L, PermissionRoleCode.TENANT_ADMINISTRATOR)
        );

        ArgumentCaptor<IdentityProviderUserCreateCommand> commandCaptor =
                ArgumentCaptor.forClass(IdentityProviderUserCreateCommand.class);
        ArgumentCaptor<PermissionIdentityUser> entityCaptor =
                ArgumentCaptor.forClass(PermissionIdentityUser.class);
        verify(identityProviderAdminClient).createUser(commandCaptor.capture());
        verify(identityUserMapper).insert(entityCaptor.capture());
        verify(auditSupport).saveIdentityAudit(any(), any(), any(), any(), any(), any(), any());

        IdentityProviderUserCreateCommand command = commandCaptor.getValue();
        PermissionIdentityUser inserted = entityCaptor.getValue();
        assertThat(command.username()).isEqualTo("alice");
        assertThat(command.password()).isEqualTo("TempPassword!123");
        assertThat(command.tenantId()).isEqualTo(10L);
        assertThat(command.actorId()).isEqualTo(2001L);
        assertThat(command.realmRoleName()).isEqualTo("DATASMART_ORDINARY_USER");

        assertThat(inserted.getProviderUserId()).isEqualTo("kc-user-001");
        assertThat(inserted.getTenantId()).isEqualTo(10L);
        assertThat(inserted.getActorId()).isEqualTo(2001L);
        assertThat(inserted.getActorRole()).isEqualTo(PermissionRoleCode.ORDINARY_USER.name());
        assertThat(inserted.getWorkspaceId()).isEqualTo("workspace-main");
        assertThat(inserted.getStatus()).isEqualTo("ACTIVE");

        assertThat(result.providerUserId()).isEqualTo("kc-user-001");
        assertThat(result.localIdentityId()).isEqualTo(3001L);
        assertThat(result.emailMasked()).isEqualTo("a***@example.com");
        assertThat(result.payloadPolicy()).isEqualTo("NO_PASSWORD_NO_TOKEN_NO_SECRET");
        assertThat(result.evidenceCodes())
                .contains("KEYCLOAK_USER_CREATED", "KEYCLOAK_ROLE_ASSIGNED", "DATASMART_SHADOW_IDENTITY_UPDATED");
        assertThat(result.toString())
                .doesNotContain("TempPassword!123")
                .doesNotContain("access_token")
                .doesNotContain("client_secret");
    }

    /**
     * 租户管理员不能跨租户创建账号。
     *
     * <p>即使 gateway 的路由权限配置错误，permission-admin 服务内仍要做二次租户边界校验。
     * 这就是商用多租户系统常见的 defense-in-depth：入口拦截和业务服务校验同时存在。
     */
    @Test
    void tenantAdministratorCannotRegisterUserForAnotherTenant() {
        IdentityUserRegisterRequest request = registerRequest(99L, 2001L);

        assertThatThrownBy(() -> service.registerUser(
                request,
                actor(10L, 1001L, PermissionRoleCode.TENANT_ADMINISTRATOR)
        )).isInstanceOfSatisfying(PlatformBusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(PlatformErrorCode.TENANT_SCOPE_DENIED));

        verify(identityProviderAdminClient, never()).createUser(any());
        verify(identityUserMapper, never()).insert(any(PermissionIdentityUser.class));
    }

    /**
     * 普通用户不能调用账号供应写接口。
     */
    @Test
    void ordinaryUserCannotRegisterIdentityUser() {
        IdentityUserRegisterRequest request = registerRequest(10L, 2001L);

        assertThatThrownBy(() -> service.registerUser(
                request,
                actor(10L, 1001L, PermissionRoleCode.ORDINARY_USER)
        )).isInstanceOfSatisfying(PlatformBusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(PlatformErrorCode.FORBIDDEN));

        verify(identityProviderAdminClient, never()).createUser(any());
        verify(identityUserMapper, never()).insert(any(PermissionIdentityUser.class));
    }

    /**
     * 禁用用户时，应先定位本地影子身份，再调用外部 IdP 禁用真实账号，并同步本地 DISABLED 状态。
     */
    @Test
    void disableUserShouldDisableProviderAccountAndShadowIdentity() {
        PermissionIdentityUser existing = identityUser("kc-user-001", 10L, 2001L);
        when(identityUserMapper.selectOne(any())).thenReturn(existing);
        when(identityProviderAdminClient.disableUser("kc-user-001", "员工离职")).thenReturn(new IdentityProviderOperationResult(
                "kc-user-001",
                "DISABLE_USER",
                "disabled",
                List.of("KEYCLOAK_USER_DISABLED")
        ));

        IdentityUserProvisionResult result = service.disableUser(
                "kc-user-001",
                new IdentityUserDisableRequest("员工离职"),
                actor(10L, 1001L, PermissionRoleCode.TENANT_ADMINISTRATOR)
        );

        verify(identityProviderAdminClient).disableUser("kc-user-001", "员工离职");
        verify(identityUserMapper).updateById(existing);
        verify(auditSupport).saveIdentityAudit(any(), any(), any(), any(), any(), any(), any());
        assertThat(existing.getStatus()).isEqualTo("DISABLED");
        assertThat(existing.getDisabledReason()).isEqualTo("员工离职");
        assertThat(result.status()).isEqualTo("DISABLED");
        assertThat(result.evidenceCodes()).contains("KEYCLOAK_USER_DISABLED", "DATASMART_SHADOW_IDENTITY_UPDATED");
    }

    /**
     * 重置密码只应调用 IdP，不应把新密码写入本地实体或响应。
     */
    @Test
    void resetPasswordShouldDelegateToProviderWithoutLeakingPassword() {
        PermissionIdentityUser existing = identityUser("kc-user-001", 10L, 2001L);
        when(identityUserMapper.selectOne(any())).thenReturn(existing);
        when(identityProviderAdminClient.resetPassword("kc-user-001", "NewPassword!456", true))
                .thenReturn(new IdentityProviderOperationResult(
                        "kc-user-001",
                        "RESET_PASSWORD",
                        "reset",
                        List.of("KEYCLOAK_PASSWORD_RESET")
                ));

        IdentityUserProvisionResult result = service.resetPassword(
                "kc-user-001",
                new IdentityUserPasswordResetRequest("NewPassword!456", true, "用户忘记密码"),
                actor(10L, 1001L, PermissionRoleCode.TENANT_ADMINISTRATOR)
        );

        verify(identityProviderAdminClient).resetPassword("kc-user-001", "NewPassword!456", true);
        verify(identityUserMapper).updateById(existing);
        verify(auditSupport).saveIdentityAudit(any(), any(), any(), any(), any(), any(), any());
        assertThat(result.operation()).isEqualTo("RESET_PASSWORD");
        assertThat(result.payloadPolicy()).isEqualTo("NO_PASSWORD_NO_TOKEN_NO_SECRET");
        assertThat(result.toString())
                .doesNotContain("NewPassword!456")
                .doesNotContain("access_token")
                .doesNotContain("client_secret");
    }

    /**
     * 构造测试用注册请求。
     */
    private IdentityUserRegisterRequest registerRequest(Long tenantId, Long actorId) {
        return new IdentityUserRegisterRequest(
                "bob",
                "bob@example.com",
                "Bob",
                "Li",
                "TempPassword!123",
                true,
                tenantId,
                actorId,
                PermissionRoleCode.ORDINARY_USER.name(),
                "USER",
                "workspace-main",
                true,
                false,
                "测试创建账号"
        );
    }

    /**
     * 构造操作人上下文。
     */
    private PermissionActorContext actor(Long tenantId, Long actorId, PermissionRoleCode role) {
        return new PermissionActorContext(tenantId, actorId, role.name(), "trace-identity-test");
    }

    /**
     * 构造本地影子身份。
     */
    private PermissionIdentityUser identityUser(String providerUserId, Long tenantId, Long actorId) {
        PermissionIdentityUser identityUser = new PermissionIdentityUser();
        identityUser.setId(3001L);
        identityUser.setTenantId(tenantId);
        identityUser.setActorId(actorId);
        identityUser.setProviderMode("KEYCLOAK_ADMIN_API");
        identityUser.setProviderUserId(providerUserId);
        identityUser.setUsername("alice");
        identityUser.setEmail("alice@example.com");
        identityUser.setActorRole(PermissionRoleCode.ORDINARY_USER.name());
        identityUser.setActorType("USER");
        identityUser.setWorkspaceId("workspace-main");
        identityUser.setStatus("ACTIVE");
        return identityUser;
    }

    /**
     * 构造项目成员关系。
     *
     * <p>候选查询会先通过项目成员关系把可选用户收敛到当前项目内，因此测试需要显式表达：
     * 哪个 actor 属于哪个 project，以及该成员关系当前是否启用。</p>
     */
    private PermissionProjectMembership projectMembership(Long tenantId, Long actorId, Long projectId) {
        PermissionProjectMembership membership = new PermissionProjectMembership();
        membership.setTenantId(tenantId);
        membership.setActorId(actorId);
        membership.setProjectId(projectId);
        membership.setProjectRole("MEMBER");
        membership.setGrantSource("TEST");
        membership.setEnabled(true);
        return membership;
    }
}

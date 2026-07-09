/**
 * @Author : Cui
 * @Date: 2026/07/08 23:42
 * @Description DataSmart Govern Backend - PermissionProjectServiceImplTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectCreateRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectStatusChangeRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectUpdateRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectQueryCriteria;
import com.czh.datasmart.govern.permission.entity.PermissionProject;
import com.czh.datasmart.govern.permission.entity.PermissionProjectMembership;
import com.czh.datasmart.govern.permission.event.PermissionProjectMembershipChangedEventPublisher;
import com.czh.datasmart.govern.permission.mapper.PermissionProjectMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionProjectMembershipMapper;
import com.czh.datasmart.govern.permission.service.support.PermissionProjectAuditSupport;
import com.czh.datasmart.govern.permission.service.support.PermissionProjectMembershipAuditSupport;
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
 * 项目主数据服务测试。
 *
 * <p>这些测试关注“去 workspace 后项目控制面是否闭环”：
 * 1. 项目负责人可以自助创建项目；
 * 2. 创建项目会自动给负责人 OWNER 授权；
 * 3. 自动授权不再写 workspaceId=10001，而是项目级归属；
 * 4. 项目负责人不能把别人设为新项目负责人，避免绕过租户管理员扩权；
 * 5. 租户管理员不能跨租户创建项目。</p>
 */
class PermissionProjectServiceImplTest {

    private PermissionProjectMapper projectMapper;
    private PermissionProjectMembershipMapper membershipMapper;
    private PermissionProjectAuditSupport projectAuditSupport;
    private PermissionProjectMembershipAuditSupport membershipAuditSupport;
    private PermissionProjectMembershipChangedEventPublisher membershipChangedEventPublisher;
    private PermissionProjectServiceImpl service;

    @BeforeEach
    void setUp() {
        projectMapper = mock(PermissionProjectMapper.class);
        membershipMapper = mock(PermissionProjectMembershipMapper.class);
        projectAuditSupport = mock(PermissionProjectAuditSupport.class);
        membershipAuditSupport = mock(PermissionProjectMembershipAuditSupport.class);
        membershipChangedEventPublisher = mock(PermissionProjectMembershipChangedEventPublisher.class);
        service = new PermissionProjectServiceImpl(projectMapper, membershipMapper, projectAuditSupport,
                membershipAuditSupport, membershipChangedEventPublisher);
    }

    /**
     * 项目负责人自助创建项目时，系统应自动生成 projectId，并给本人授予 OWNER。
     */
    @Test
    void tenantAdministratorCanCreateProjectAndReceiveOwnerMembershipWithoutWorkspace() {
        when(projectMapper.nextProjectId()).thenReturn(100000L);
        when(projectMapper.selectDefaultApplicationId(10L)).thenReturn(10010L);
        when(projectMapper.selectCount(any())).thenReturn(0L);
        when(membershipMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            PermissionProjectMembership membership = invocation.getArgument(0);
            membership.setId(7001L);
            return 1;
        }).when(membershipMapper).insert(any(PermissionProjectMembership.class));

        PermissionProjectMutationResult result = service.createProject(
                new PermissionProjectCreateRequest(10L, null, "CUSTOMER_SYNC", "客户同步项目",
                        null, null, "用于客户库到数仓的同步任务", "创建客户同步项目"),
                actor(10L, 1001L, PermissionRoleCode.TENANT_ADMINISTRATOR));

        assertThat(result.projectId()).isEqualTo(100000L);
        assertThat(result.tenantId()).isEqualTo(10L);
        assertThat(result.projectCode()).isEqualTo("CUSTOMER_SYNC");
        assertThat(result.ownerActorId()).isEqualTo(1001L);

        ArgumentCaptor<PermissionProject> projectCaptor = ArgumentCaptor.forClass(PermissionProject.class);
        verify(projectMapper).insert(projectCaptor.capture());
        assertThat(projectCaptor.getValue().getDefaultWorkspaceId()).isNull();
        assertThat(projectCaptor.getValue().getApplicationId()).isEqualTo(10010L);

        ArgumentCaptor<PermissionProjectMembership> membershipCaptor =
                ArgumentCaptor.forClass(PermissionProjectMembership.class);
        verify(membershipMapper).insert(membershipCaptor.capture());
        assertThat(membershipCaptor.getValue().getWorkspaceId()).isNull();
        assertThat(membershipCaptor.getValue().getProjectRole()).isEqualTo("OWNER");
        assertThat(membershipCaptor.getValue().getGrantSource()).isEqualTo("PROJECT_CREATION");
        verify(projectAuditSupport).saveMutationAudit(any(), any(), any(), any(), any(), any());
        verify(membershipAuditSupport).saveMutationAudit(any(), any(), any(), any(), any(), any());
        verify(membershipChangedEventPublisher).publishProjectMembershipChanged(any(), any(), any(), any());
    }

    /**
     * 项目负责人只能创建自己负责的新项目，不能把别人设为 OWNER。
     */
    @Test
    void projectOwnerCannotCreateProjectDirectly() {
        assertThatThrownBy(() -> service.createProject(
                new PermissionProjectCreateRequest(10L, null, "PROJECT_OWNER_DIRECT", "直接创建项目",
                        null, null, null, "测试越权"),
                actor(10L, 1001L, PermissionRoleCode.PROJECT_OWNER)))
                .isInstanceOfSatisfying(PlatformBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PlatformErrorCode.FORBIDDEN));

        verify(projectMapper, never()).insert(any(PermissionProject.class));
        verify(membershipMapper, never()).insert(any(PermissionProjectMembership.class));
    }

    /**
     * 租户管理员不能跨租户创建项目。
     */
    @Test
    void tenantAdministratorCannotCreateProjectForOtherTenant() {
        assertThatThrownBy(() -> service.createProject(
                new PermissionProjectCreateRequest(99L, null, "CROSS_TENANT", "跨租户项目",
                        null, 1001L, null, "测试跨租户"),
                actor(10L, 1001L, PermissionRoleCode.TENANT_ADMINISTRATOR)))
                .isInstanceOfSatisfying(PlatformBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PlatformErrorCode.TENANT_SCOPE_DENIED));

        verify(projectMapper, never()).insert(any(PermissionProject.class));
    }

    /**
     * 运营人员可以只读查询本租户项目，支撑运行排障和项目上下文切换。
     */
    @Test
    void operatorCanReadTenantProjects() {
        when(projectMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<PermissionProject> page = invocation.getArgument(0);
            page.setRecords(List.of(project(10L, 101L, "FLASHSYNC_DEFAULT")));
            page.setTotal(1L);
            return page;
        });

        var page = service.pageProjects(
                new PermissionProjectQueryCriteria(10L, null, null, null, null, null, null, 1L, 20L),
                actor(10L, 1002L, PermissionRoleCode.OPERATOR));

        assertThat(page.getRecords()).hasSize(1);
        assertThat(page.getRecords().get(0).projectCode()).isEqualTo("FLASHSYNC_DEFAULT");
    }

    /**
     * 租户管理员可以禁用本租户项目；禁用只改变项目状态，不删除历史数据。
     */
    @Test
    void tenantAdministratorCanDisableProject() {
        PermissionProject project = project(10L, 101L, "FLASHSYNC_DEFAULT");
        when(projectMapper.selectById(101L)).thenReturn(project);

        PermissionProjectMutationResult result = service.disableProject(101L,
                new PermissionProjectStatusChangeRequest("客户暂停使用 FlashSync"),
                actor(10L, 1001L, PermissionRoleCode.TENANT_ADMINISTRATOR));

        assertThat(result.status()).isEqualTo("DISABLED");
        assertThat(project.getStatus()).isEqualTo("DISABLED");
        verify(projectMapper).updateById(project);
        verify(projectAuditSupport).saveMutationAudit(any(), any(), any(), any(), any(), any());
    }

    /**
     * 项目负责人必须拥有该项目 OWNER 成员关系才能编辑项目，不能只凭全局 PROJECT_OWNER 角色跨项目修改。
     */
    @Test
    void projectOwnerMustHaveOwnerMembershipToUpdateProject() {
        PermissionProject project = project(10L, 101L, "FLASHSYNC_DEFAULT");
        when(projectMapper.selectById(101L)).thenReturn(project);
        when(membershipMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.updateProject(101L,
                new PermissionProjectUpdateRequest(null, "新名称", null, null, null, "测试越权编辑"),
                actor(10L, 1001L, PermissionRoleCode.PROJECT_OWNER)))
                .isInstanceOfSatisfying(PlatformBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PlatformErrorCode.FORBIDDEN));

        verify(projectMapper, never()).updateById(any(PermissionProject.class));
    }

    /**
     * 项目删除前如果仍有活动数据源、启用模板或未归档任务，必须阻断，避免项目归属上下文丢失。
     */
    @Test
    void deleteProjectIsBlockedWhenBusinessResourcesStillExist() {
        PermissionProject project = project(10L, 101L, "FLASHSYNC_DEFAULT");
        when(projectMapper.selectById(101L)).thenReturn(project);
        when(projectMapper.countActiveDatasources(10L, 101L)).thenReturn(1L);
        when(projectMapper.countEnabledSyncTemplates(10L, 101L)).thenReturn(2L);
        when(projectMapper.countActiveSyncTasks(10L, 101L)).thenReturn(3L);

        var check = service.checkProjectDeletion(101L,
                actor(10L, 1001L, PermissionRoleCode.TENANT_ADMINISTRATOR));
        assertThat(check.deletable()).isFalse();
        assertThat(check.blockers()).hasSize(3);

        assertThatThrownBy(() -> service.deleteProject(101L,
                new PermissionProjectStatusChangeRequest("删除测试项目"),
                actor(10L, 1001L, PermissionRoleCode.TENANT_ADMINISTRATOR)))
                .isInstanceOfSatisfying(PlatformBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PlatformErrorCode.BUSINESS_STATE_CONFLICT));
        verify(projectMapper, never()).updateById(any(PermissionProject.class));
    }

    private PermissionActorContext actor(Long tenantId, Long actorId, PermissionRoleCode role) {
        return new PermissionActorContext(tenantId, actorId, role.name(), "trace-project-test");
    }

    private PermissionProject project(Long tenantId, Long projectId, String projectCode) {
        PermissionProject project = new PermissionProject();
        project.setTenantId(tenantId);
        project.setProjectId(projectId);
        project.setApplicationId(10010L);
        project.setProjectCode(projectCode);
        project.setProjectName("测试项目");
        project.setProjectType("DATA_GOVERNANCE");
        project.setStatus("ACTIVE");
        project.setOwnerActorId(1001L);
        return project;
    }
}

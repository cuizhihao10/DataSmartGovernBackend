/**
 * @Author : Cui
 * @Date: 2026/05/13 22:58
 * @Description DataSmart Govern Backend - PermissionProjectMembershipServiceImplTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipBatchUpsertRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipCreateRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipQueryCriteria;
import com.czh.datasmart.govern.permission.entity.PermissionProjectMembership;
import com.czh.datasmart.govern.permission.event.PermissionProjectMembershipChangedEventPublisher;
import com.czh.datasmart.govern.permission.mapper.PermissionProjectMembershipMapper;
import com.czh.datasmart.govern.permission.service.support.PermissionProjectMembershipAuditSupport;
import com.czh.datasmart.govern.permission.support.PermissionRoleCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
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
 * 项目成员授权管理服务单元测试。
 *
 * <p>这些测试不启动 Spring 容器，也不连接 MySQL。
 * 原因是本轮要验证的是“权限边界语义”，而不是 MyBatis 或数据库本身：
 * 1. 租户管理员只能管理自身租户；
 * 2. 项目负责人只能管理自己 OWNER 的项目；
 * 3. 项目负责人不能授予 OWNER，避免项目内自扩权；
 * 4. 运营人员可以排障查看，但不能变更成员；
 * 5. 批量导入必须有数量上限，避免管理接口变成长事务入口。
 *
 * <p>这类纯单元测试适合作为 Java Core 收口前的安全回归网。
 * 后续进入 AI/Agent 模块后，只要权限源头测试还在，就能更放心地复用 PROJECT 数据范围。
 */
class PermissionProjectMembershipServiceImplTest {

    private PermissionProjectMembershipMapper membershipMapper;
    private PermissionProjectMembershipAuditSupport auditSupport;
    private PermissionProjectMembershipChangedEventPublisher membershipChangedEventPublisher;
    private PermissionProjectMembershipServiceImpl service;

    /**
     * 每个测试重新创建 mock，避免一个用例中的查询桩影响另一个用例。
     */
    @BeforeEach
    void setUp() {
        membershipMapper = mock(PermissionProjectMembershipMapper.class);
        auditSupport = mock(PermissionProjectMembershipAuditSupport.class);
        membershipChangedEventPublisher = mock(PermissionProjectMembershipChangedEventPublisher.class);
        service = new PermissionProjectMembershipServiceImpl(membershipMapper, auditSupport, membershipChangedEventPublisher);
    }

    /**
     * 租户管理员在本租户内新增成员应该成功，并写入审计。
     *
     * <p>这里额外断言 workspaceId 必须为空。它对应最新产品模型中的关键收敛规则：
     * 项目成员授权属于“租户 -> 项目 -> 成员”，不再让用户选择工作空间；
     * 数据库列保留只是为了兼容旧数据和 Agent 内部空间语义。</p>
     */
    @Test
    void tenantAdministratorCanGrantMemberInOwnTenantAndWritesAudit() {
        when(membershipMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            PermissionProjectMembership inserted = invocation.getArgument(0);
            inserted.setId(9001L);
            return 1;
        }).when(membershipMapper).insert(any(PermissionProjectMembership.class));

        ProjectMembershipMutationResult result = service.grantOrUpdateProjectMembership(
                createRequest(10L, 2001L, 101L, "MEMBER"),
                actor(10L, 1001L, PermissionRoleCode.TENANT_ADMINISTRATOR));

        assertThat(result.membershipId()).isEqualTo(9001L);
        assertThat(result.tenantId()).isEqualTo(10L);
        assertThat(result.projectId()).isEqualTo(101L);
        assertThat(result.enabled()).isTrue();
        ArgumentCaptor<PermissionProjectMembership> insertedCaptor =
                ArgumentCaptor.forClass(PermissionProjectMembership.class);
        verify(membershipMapper).insert(insertedCaptor.capture());
        assertThat(insertedCaptor.getValue().getWorkspaceId()).isNull();
        verify(auditSupport).saveMutationAudit(any(), any(), any(), any(), any(), any());
        verify(membershipChangedEventPublisher).publishProjectMembershipChanged(any(), any(), any(), any());
    }

    /**
     * 租户管理员不能给其他租户发项目授权。
     */
    @Test
    void tenantAdministratorCannotGrantOtherTenantMembership() {
        assertThatThrownBy(() -> service.grantOrUpdateProjectMembership(
                createRequest(99L, 2001L, 101L, "MEMBER"),
                actor(10L, 1001L, PermissionRoleCode.TENANT_ADMINISTRATOR)))
                .isInstanceOfSatisfying(PlatformBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PlatformErrorCode.TENANT_SCOPE_DENIED));

        /*
         * MyBatis-Plus 3.5.14 的 BaseMapper 同时提供 insert(T) 与 insert(Collection<T>)。
         * 测试这里验证的是“不能写入单条项目成员授权记录”，因此必须把 Mockito matcher
         * 明确成 PermissionProjectMembership，避免 any() 同时匹配单条实体和批量集合重载。
         */
        verify(membershipMapper, never()).insert(any(PermissionProjectMembership.class));
        verify(auditSupport, never()).saveMutationAudit(any(), any(), any(), any(), any(), any());
        verify(membershipChangedEventPublisher, never()).publishProjectMembershipChanged(any(), any(), any(), any());
    }

    /**
     * 项目负责人可以维护自己拥有 OWNER 授权的项目成员。
     */
    @Test
    void projectOwnerCanGrantMaintainerInOwnedProject() {
        when(membershipMapper.selectList(any())).thenReturn(List.of(ownerMembership(10L, 1001L, 101L)));
        when(membershipMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            PermissionProjectMembership inserted = invocation.getArgument(0);
            inserted.setId(9002L);
            return 1;
        }).when(membershipMapper).insert(any(PermissionProjectMembership.class));

        ProjectMembershipMutationResult result = service.grantOrUpdateProjectMembership(
                createRequest(10L, 2002L, 101L, "MAINTAINER"),
                actor(10L, 1001L, PermissionRoleCode.PROJECT_OWNER));

        assertThat(result.membershipId()).isEqualTo(9002L);
        assertThat(result.projectId()).isEqualTo(101L);
        verify(auditSupport).saveMutationAudit(any(), any(), any(), any(), any(), any());
        verify(membershipChangedEventPublisher).publishProjectMembershipChanged(any(), any(), any(), any());
    }

    /**
     * 项目负责人不能授予 OWNER。
     *
     * <p>这是防止项目内自扩权的关键规则：如果项目负责人可以再创建 OWNER，
     * 项目级管理权限就可能绕过租户管理员审批无限扩散。</p>
     */
    @Test
    void projectOwnerCannotGrantOwnerRoleEvenInOwnedProject() {
        when(membershipMapper.selectList(any())).thenReturn(List.of(ownerMembership(10L, 1001L, 101L)));

        assertThatThrownBy(() -> service.grantOrUpdateProjectMembership(
                createRequest(10L, 2002L, 101L, "OWNER"),
                actor(10L, 1001L, PermissionRoleCode.PROJECT_OWNER)))
                .isInstanceOfSatisfying(PlatformBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PlatformErrorCode.FORBIDDEN));

        /*
         * 这里同样只关心是否阻止了单条 OWNER 授权记录写入；强类型 matcher 可以让测试含义
         * 与 MyBatis-Plus 新增的批量 insert 重载保持边界清晰。
         */
        verify(membershipMapper, never()).insert(any(PermissionProjectMembership.class));
        verify(auditSupport, never()).saveMutationAudit(any(), any(), any(), any(), any(), any());
    }

    /**
     * 运营人员只能排障查看，不能直接变更成员授权。
     */
    @Test
    void operatorCanViewButCannotMutateProjectMemberships() {
        when(membershipMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<PermissionProjectMembership> page = invocation.getArgument(0);
            page.setRecords(List.of(member(10L, 2001L, 101L, "MEMBER")));
            page.setTotal(1L);
            return page;
        });

        assertThat(service.pageProjectMemberships(queryTenant(10L), actor(10L, 3001L, PermissionRoleCode.OPERATOR))
                .getRecords()).hasSize(1);
        assertThatThrownBy(() -> service.grantOrUpdateProjectMembership(
                createRequest(10L, 2001L, 101L, "MEMBER"),
                actor(10L, 3001L, PermissionRoleCode.OPERATOR)))
                .isInstanceOfSatisfying(PlatformBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PlatformErrorCode.FORBIDDEN));
    }

    /**
     * 项目负责人不能读取非自己负责项目的成员详情。
     */
    @Test
    void projectOwnerCannotReadMembershipFromUnownedProject() {
        PermissionProjectMembership target = member(10L, 2001L, 999L, "MEMBER");
        target.setId(8888L);
        when(membershipMapper.selectById(8888L)).thenReturn(target);
        when(membershipMapper.selectList(any())).thenReturn(List.of(ownerMembership(10L, 1001L, 101L)));

        assertThatThrownBy(() -> service.getProjectMembership(8888L,
                actor(10L, 1001L, PermissionRoleCode.PROJECT_OWNER)))
                .isInstanceOfSatisfying(PlatformBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PlatformErrorCode.FORBIDDEN));
    }

    /**
     * 批量导入必须限制单批数量，避免一个同步接口承担大规模组织同步任务。
     */
    @Test
    void batchImportRejectsTooManyRows() {
        List<ProjectMembershipCreateRequest> requests = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            requests.add(createRequest(10L, 2000L + i, 101L, "MEMBER"));
        }

        assertThatThrownBy(() -> service.batchGrantOrUpdateProjectMemberships(
                new ProjectMembershipBatchUpsertRequest(requests, "批量导入测试"),
                actor(10L, 1001L, PermissionRoleCode.TENANT_ADMINISTRATOR)))
                .isInstanceOfSatisfying(PlatformBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PlatformErrorCode.VALIDATION_ERROR));
    }

    private ProjectMembershipQueryCriteria queryTenant(Long tenantId) {
        return new ProjectMembershipQueryCriteria(tenantId, null, null, null, null, null, 1L, 20L);
    }

    private ProjectMembershipCreateRequest createRequest(Long tenantId, Long actorId, Long projectId, String role) {
        return new ProjectMembershipCreateRequest(tenantId, actorId, projectId, role, "MANUAL", true, "测试授权");
    }

    private PermissionActorContext actor(Long tenantId, Long actorId, PermissionRoleCode role) {
        return new PermissionActorContext(tenantId, actorId, role.name(), "trace-test");
    }

    private PermissionProjectMembership ownerMembership(Long tenantId, Long actorId, Long projectId) {
        return member(tenantId, actorId, projectId, "OWNER");
    }

    private PermissionProjectMembership member(Long tenantId, Long actorId, Long projectId, String role) {
        PermissionProjectMembership membership = new PermissionProjectMembership();
        membership.setTenantId(tenantId);
        membership.setActorId(actorId);
        membership.setProjectId(projectId);
        membership.setWorkspaceId(10001L);
        membership.setProjectRole(role);
        membership.setGrantSource("TEST");
        membership.setEnabled(true);
        return membership;
    }
}

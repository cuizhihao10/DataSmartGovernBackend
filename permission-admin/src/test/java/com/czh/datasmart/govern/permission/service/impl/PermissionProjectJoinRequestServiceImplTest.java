/**
 * @Author : Cui
 * @Date: 2026/07/10 23:40
 * @Description DataSmart Govern Backend - PermissionProjectJoinRequestServiceImplTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.entity.PermissionProject;
import com.czh.datasmart.govern.permission.mapper.PermissionProjectJoinRequestMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionProjectMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionProjectMembershipMapper;
import com.czh.datasmart.govern.permission.service.PermissionProjectMembershipService;
import com.czh.datasmart.govern.permission.service.support.PermissionIdentityDisplaySupport;
import com.czh.datasmart.govern.permission.support.PermissionRoleCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that project-name discovery remains low-sensitive, tenant-isolated and bounded.
 */
class PermissionProjectJoinRequestServiceImplTest {

    private PermissionProjectMapper projectMapper;
    private PermissionProjectJoinRequestServiceImpl service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "project-directory-test");
        assistant.setCurrentNamespace("project-directory-test");
        TableInfoHelper.initTableInfo(assistant, PermissionProject.class);
        PermissionProjectJoinRequestMapper joinRequestMapper = mock(PermissionProjectJoinRequestMapper.class);
        projectMapper = mock(PermissionProjectMapper.class);
        PermissionProjectMembershipMapper membershipMapper = mock(PermissionProjectMembershipMapper.class);
        PermissionProjectMembershipService membershipService = mock(PermissionProjectMembershipService.class);
        PermissionIdentityDisplaySupport identityDisplaySupport = mock(PermissionIdentityDisplaySupport.class);
        when(identityDisplaySupport.usernames(any())).thenReturn(Map.of());
        service = new PermissionProjectJoinRequestServiceImpl(
                joinRequestMapper, projectMapper, membershipMapper, membershipService, identityDisplaySupport);
    }

    @Test
    void ordinaryUserCanOnlyDiscoverActiveProjectsFromOwnTenant() {
        PermissionProject project = project(101L, 10L, "FLASHSYNC_DEFAULT", "FlashSync 默认项目", "ACTIVE");
        when(projectMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<PermissionProject> requestedPage = invocation.getArgument(0);
            requestedPage.setRecords(List.of(project));
            requestedPage.setTotal(1L);
            return requestedPage;
        });

        var result = service.pageJoinCandidates(10L, "FlashSync", 1L, 20L,
                actor(10L, 1001L, PermissionRoleCode.ORDINARY_USER));

        assertThat(result.getRecords()).singleElement().satisfies(candidate -> {
            assertThat(candidate.projectId()).isEqualTo(101L);
            assertThat(candidate.tenantId()).isEqualTo(10L);
            assertThat(candidate.projectName()).isEqualTo("FlashSync 默认项目");
            assertThat(candidate.projectCode()).isEqualTo("FLASHSYNC_DEFAULT");
        });

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaQueryWrapper<PermissionProject>> wrapperCaptor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(projectMapper).selectPage(any(Page.class), wrapperCaptor.capture());
        String sqlSegment = wrapperCaptor.getValue().getSqlSegment();
        assertThat(sqlSegment)
                .contains("tenant_id")
                .contains("status")
                .contains("project_name")
                .contains("project_code");
        var parameterValues = wrapperCaptor.getValue().getParamNameValuePairs().values();
        assertThat(parameterValues).contains(10L, "ACTIVE");
        assertThat(parameterValues.stream().map(String::valueOf))
                .anyMatch(value -> value.contains("FlashSync"))
                .anyMatch(value -> value.contains("FLASHSYNC"));
    }

    @Test
    void ordinaryUserCannotDiscoverAnotherTenantProjectDirectory() {
        assertThatThrownBy(() -> service.pageJoinCandidates(20L, null, 1L, 20L,
                actor(10L, 1001L, PermissionRoleCode.ORDINARY_USER)))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("another tenant");
    }

    @Test
    void candidatePageSizeIsCappedAtOneHundred() {
        when(projectMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<PermissionProject> requestedPage = invocation.getArgument(0);
            requestedPage.setRecords(List.of());
            requestedPage.setTotal(0L);
            return requestedPage;
        });

        service.pageJoinCandidates(null, null, 3L, 500L,
                actor(10L, 1001L, PermissionRoleCode.ORDINARY_USER));

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Page> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(projectMapper).selectPage(pageCaptor.capture(), any(LambdaQueryWrapper.class));
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(3L);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(100L);
    }

    @Test
    void platformAdministratorCanDiscoverActiveProjectsAcrossTenantsWithoutJoining() {
        when(projectMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<PermissionProject> requestedPage = invocation.getArgument(0);
            requestedPage.setRecords(List.of(
                    project(900L, 1L, "PLATFORM_ADMINISTRATION", "平台管理项目", "ACTIVE"),
                    project(101L, 10L, "FLASHSYNC_DEFAULT", "FlashSync 默认项目", "ACTIVE")));
            requestedPage.setTotal(2L);
            return requestedPage;
        });

        var result = service.pageJoinCandidates(null, null, 1L, 100L,
                actor(1L, 9001L, PermissionRoleCode.PLATFORM_ADMINISTRATOR));

        assertThat(result.getRecords()).extracting(candidate -> candidate.tenantId())
                .containsExactly(1L, 10L);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaQueryWrapper<PermissionProject>> wrapperCaptor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(projectMapper).selectPage(any(Page.class), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment()).doesNotContain("tenant_id");
    }

    private PermissionProject project(Long projectId,
                                      Long tenantId,
                                      String projectCode,
                                      String projectName,
                                      String status) {
        PermissionProject project = new PermissionProject();
        project.setProjectId(projectId);
        project.setTenantId(tenantId);
        project.setProjectCode(projectCode);
        project.setProjectName(projectName);
        project.setProjectType("DATA_GOVERNANCE");
        project.setStatus(status);
        return project;
    }

    private PermissionActorContext actor(Long tenantId, Long actorId, PermissionRoleCode role) {
        return new PermissionActorContext(tenantId, actorId, role.name(), "trace-project-directory-test");
    }
}

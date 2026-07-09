/**
 * @Author : Cui
 * @Date: 2026/07/10 04:29
 * @Description DataSmart Govern Backend - PermissionProjectCreationRequestServiceImplTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectCreateRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.ProjectCreationRequestApplyRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectCreationRequestReviewRequest;
import com.czh.datasmart.govern.permission.entity.PermissionProjectCreationRequest;
import com.czh.datasmart.govern.permission.mapper.PermissionProjectCreationRequestMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionProjectMapper;
import com.czh.datasmart.govern.permission.service.PermissionProjectService;
import com.czh.datasmart.govern.permission.support.PermissionRoleCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the project creation approval workflow.
 *
 * <p>The workflow matters because pending requests must not leak into project data scope. Only approval should create
 * the real project and therefore create the OWNER membership consumed by gateway project-role headers.</p>
 */
class PermissionProjectCreationRequestServiceImplTest {

    private PermissionProjectCreationRequestMapper creationRequestMapper;
    private PermissionProjectMapper projectMapper;
    private PermissionProjectService projectService;
    private PermissionProjectCreationRequestServiceImpl service;

    @BeforeEach
    void setUp() {
        creationRequestMapper = mock(PermissionProjectCreationRequestMapper.class);
        projectMapper = mock(PermissionProjectMapper.class);
        projectService = mock(PermissionProjectService.class);
        service = new PermissionProjectCreationRequestServiceImpl(creationRequestMapper, projectMapper, projectService);
    }

    @Test
    void ordinaryUserApplyCreatesOnlyPendingWorkflowFact() {
        when(projectMapper.selectCount(any())).thenReturn(0L);
        when(creationRequestMapper.selectCount(any())).thenReturn(0L);
        when(creationRequestMapper.insert(any(PermissionProjectCreationRequest.class))).thenAnswer(invocation -> {
            PermissionProjectCreationRequest request = invocation.getArgument(0);
            request.setId(9001L);
            return 1;
        });

        var result = service.apply(
                new ProjectCreationRequestApplyRequest(10L, null, "CUSTOMER_SYNC", "客户同步项目",
                        null, "普通用户", null, "客户库到数仓同步", "需要独立项目隔离数据源和任务"),
                actor(10L, 1001L, PermissionRoleCode.ORDINARY_USER));

        assertThat(result.requestId()).isEqualTo(9001L);
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.createdProjectId()).isNull();

        ArgumentCaptor<PermissionProjectCreationRequest> requestCaptor =
                ArgumentCaptor.forClass(PermissionProjectCreationRequest.class);
        verify(creationRequestMapper).insert(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getOwnerActorId()).isEqualTo(1001L);
        assertThat(requestCaptor.getValue().getProjectCode()).isEqualTo("CUSTOMER_SYNC");
    }

    @Test
    void tenantAdministratorApproveCreatesRealProjectAndMarksRequestApproved() {
        PermissionProjectCreationRequest pending = pendingRequest();
        when(creationRequestMapper.selectById(9001L)).thenReturn(pending);
        when(projectService.createProject(any(PermissionProjectCreateRequest.class), any(PermissionActorContext.class)))
                .thenReturn(new PermissionProjectMutationResult(
                        100000L, 10L, "CUSTOMER_SYNC", "客户同步项目", "ACTIVE", 1001L, "created"));

        var result = service.approve(9001L,
                new ProjectCreationRequestReviewRequest(null, null, null, null, null, null, "审批通过"),
                actor(10L, 9001L, PermissionRoleCode.TENANT_ADMINISTRATOR));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.createdProjectId()).isEqualTo(100000L);

        ArgumentCaptor<PermissionProjectCreateRequest> createCaptor =
                ArgumentCaptor.forClass(PermissionProjectCreateRequest.class);
        verify(projectService).createProject(createCaptor.capture(), any(PermissionActorContext.class));
        assertThat(createCaptor.getValue().ownerActorId()).isEqualTo(1001L);
        assertThat(createCaptor.getValue().projectCode()).isEqualTo("CUSTOMER_SYNC");

        ArgumentCaptor<PermissionProjectCreationRequest> updateCaptor =
                ArgumentCaptor.forClass(PermissionProjectCreationRequest.class);
        verify(creationRequestMapper).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getStatus()).isEqualTo("APPROVED");
        assertThat(updateCaptor.getValue().getCreatedProjectId()).isEqualTo(100000L);
    }

    private PermissionProjectCreationRequest pendingRequest() {
        PermissionProjectCreationRequest request = new PermissionProjectCreationRequest();
        request.setId(9001L);
        request.setTenantId(10L);
        request.setProjectCode("CUSTOMER_SYNC");
        request.setProjectName("客户同步项目");
        request.setProjectType("DATA_GOVERNANCE");
        request.setApplicantActorId(1001L);
        request.setOwnerActorId(1001L);
        request.setStatus("PENDING");
        request.setCreateTime(LocalDateTime.now());
        request.setUpdateTime(LocalDateTime.now());
        return request;
    }

    private PermissionActorContext actor(Long tenantId, Long actorId, PermissionRoleCode role) {
        return new PermissionActorContext(tenantId, actorId, role.name(), "trace-project-creation-test");
    }
}

/**
 * @Author : Cui
 * @Date: 2026/07/10 11:39
 * @Description DataSmart Govern Backend - PermissionApprovalCenterServiceImplTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.permission.controller.dto.ApprovalCenterItemView;
import com.czh.datasmart.govern.permission.controller.dto.ApprovalCenterQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.ApprovalCenterReviewRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.ProjectJoinRequestMutationResult;
import com.czh.datasmart.govern.permission.mapper.PermissionApprovalCenterMapper;
import com.czh.datasmart.govern.permission.service.PermissionProjectCreationRequestService;
import com.czh.datasmart.govern.permission.service.PermissionProjectJoinRequestService;
import com.czh.datasmart.govern.permission.support.PermissionRoleCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Permission and dispatch tests for the unified approval center.
 */
class PermissionApprovalCenterServiceImplTest {

    private PermissionApprovalCenterMapper approvalCenterMapper;
    private PermissionProjectCreationRequestService creationRequestService;
    private PermissionProjectJoinRequestService joinRequestService;
    private PermissionApprovalCenterServiceImpl service;

    @BeforeEach
    void setUp() {
        approvalCenterMapper = mock(PermissionApprovalCenterMapper.class);
        creationRequestService = mock(PermissionProjectCreationRequestService.class);
        joinRequestService = mock(PermissionProjectJoinRequestService.class);
        service = new PermissionApprovalCenterServiceImpl(
                approvalCenterMapper, creationRequestService, joinRequestService);
    }

    @Test
    void ordinaryUserOnlyReceivesOwnRequestsWithCancelAction() {
        ApprovalCenterItemView item = item("PROJECT_CREATION", 11L, "PENDING");
        when(approvalCenterMapper.selectApprovalPage(eq(10L), eq(1001L), isNull(), isNull(), eq(20L), eq(0L)))
                .thenReturn(List.of(item));
        when(approvalCenterMapper.countApprovals(10L, 1001L, null, null)).thenReturn(1L);

        var page = service.pageMyRequests(
                new ApprovalCenterQueryCriteria(null, null, null, 1L, 20L),
                actor(10L, 1001L, PermissionRoleCode.ORDINARY_USER));

        assertThat(page.getRecords()).hasSize(1);
        assertThat(page.getRecords().get(0).getAvailableActions()).containsExactly("CANCEL");
        verify(approvalCenterMapper).selectApprovalPage(10L, 1001L, null, null, 20L, 0L);
    }

    @Test
    void ordinaryUserCannotReadAdministratorPendingWork() {
        assertThatThrownBy(() -> service.pagePendingApprovals(
                new ApprovalCenterQueryCriteria(null, null, "PENDING", 1L, 20L),
                actor(10L, 1001L, PermissionRoleCode.ORDINARY_USER)))
                .isInstanceOfSatisfying(PlatformBusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(PlatformErrorCode.FORBIDDEN));
    }

    @Test
    void tenantAdministratorApprovalDispatchesToJoinWorkflow() {
        when(joinRequestService.approve(eq(22L), any(), any()))
                .thenReturn(new ProjectJoinRequestMutationResult(
                        22L, 10L, 101L, 1002L, "APPROVED", 7001L, "approved"));

        var result = service.approve(
                "PROJECT_JOIN",
                22L,
                new ApprovalCenterReviewRequest("MANAGER", null, null, null,
                        null, null, null, "同意加入"),
                actor(10L, 9001L, PermissionRoleCode.TENANT_ADMINISTRATOR));

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.resultResourceId()).isEqualTo(7001L);
        verify(joinRequestService).approve(eq(22L), any(), any());
    }

    private ApprovalCenterItemView item(String type, Long id, String status) {
        ApprovalCenterItemView item = new ApprovalCenterItemView();
        item.setRequestType(type);
        item.setRequestId(id);
        item.setTenantId(10L);
        item.setApplicantActorId(1001L);
        item.setStatus(status);
        item.setUpdateTime(LocalDateTime.now());
        return item;
    }

    private PermissionActorContext actor(Long tenantId, Long actorId, PermissionRoleCode role) {
        return new PermissionActorContext(tenantId, actorId, role.name(), "trace-approval-center-test");
    }
}

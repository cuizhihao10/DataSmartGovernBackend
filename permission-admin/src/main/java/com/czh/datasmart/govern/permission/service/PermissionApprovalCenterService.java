/**
 * @Author : Cui
 * @Date: 2026/07/10 11:39
 * @Description DataSmart Govern Backend - PermissionApprovalCenterService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service;

import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.permission.controller.dto.ApprovalCenterItemView;
import com.czh.datasmart.govern.permission.controller.dto.ApprovalCenterMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.ApprovalCenterQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.ApprovalCenterReviewRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;

/**
 * Unified approval-center application service.
 */
public interface PermissionApprovalCenterService {

    PlatformPageResponse<ApprovalCenterItemView> pageMyRequests(ApprovalCenterQueryCriteria criteria,
                                                                PermissionActorContext actorContext);

    PlatformPageResponse<ApprovalCenterItemView> pagePendingApprovals(ApprovalCenterQueryCriteria criteria,
                                                                      PermissionActorContext actorContext);

    ApprovalCenterMutationResult approve(String requestType,
                                         Long requestId,
                                         ApprovalCenterReviewRequest request,
                                         PermissionActorContext actorContext);

    ApprovalCenterMutationResult reject(String requestType,
                                        Long requestId,
                                        ApprovalCenterReviewRequest request,
                                        PermissionActorContext actorContext);

    ApprovalCenterMutationResult cancel(String requestType,
                                        Long requestId,
                                        PermissionActorContext actorContext);
}

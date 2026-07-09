/**
 * @Author : Cui
 * @Date: 2026/07/10 20:42
 * @Description DataSmart Govern Backend - PermissionProjectJoinRequestService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service;

import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.ProjectJoinRequestApplyRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectJoinRequestMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.ProjectJoinRequestQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.ProjectJoinRequestReviewRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectJoinRequestView;

/**
 * Project join request workflow service.
 */
public interface PermissionProjectJoinRequestService {

    ProjectJoinRequestMutationResult apply(ProjectJoinRequestApplyRequest request, PermissionActorContext actorContext);

    PlatformPageResponse<ProjectJoinRequestView> pageMyRequests(ProjectJoinRequestQueryCriteria criteria,
                                                                PermissionActorContext actorContext);

    PlatformPageResponse<ProjectJoinRequestView> pageApprovalRequests(ProjectJoinRequestQueryCriteria criteria,
                                                                      PermissionActorContext actorContext);

    ProjectJoinRequestMutationResult approve(Long requestId,
                                             ProjectJoinRequestReviewRequest request,
                                             PermissionActorContext actorContext);

    ProjectJoinRequestMutationResult reject(Long requestId,
                                            ProjectJoinRequestReviewRequest request,
                                            PermissionActorContext actorContext);

    ProjectJoinRequestMutationResult cancel(Long requestId, PermissionActorContext actorContext);
}

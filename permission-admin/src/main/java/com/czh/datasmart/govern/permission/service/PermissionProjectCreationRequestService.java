/**
 * @Author : Cui
 * @Date: 2026/07/10 04:29
 * @Description DataSmart Govern Backend - PermissionProjectCreationRequestService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service;

import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.ProjectCreationRequestApplyRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectCreationRequestMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.ProjectCreationRequestQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.ProjectCreationRequestReviewRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectCreationRequestView;

/**
 * Project creation request workflow service.
 */
public interface PermissionProjectCreationRequestService {

    ProjectCreationRequestMutationResult apply(ProjectCreationRequestApplyRequest request,
                                               PermissionActorContext actorContext);

    PlatformPageResponse<ProjectCreationRequestView> pageMyRequests(ProjectCreationRequestQueryCriteria criteria,
                                                                    PermissionActorContext actorContext);

    PlatformPageResponse<ProjectCreationRequestView> pageApprovalRequests(ProjectCreationRequestQueryCriteria criteria,
                                                                         PermissionActorContext actorContext);

    ProjectCreationRequestMutationResult approve(Long requestId,
                                                 ProjectCreationRequestReviewRequest request,
                                                 PermissionActorContext actorContext);

    ProjectCreationRequestMutationResult reject(Long requestId,
                                                ProjectCreationRequestReviewRequest request,
                                                PermissionActorContext actorContext);

    ProjectCreationRequestMutationResult cancel(Long requestId, PermissionActorContext actorContext);
}

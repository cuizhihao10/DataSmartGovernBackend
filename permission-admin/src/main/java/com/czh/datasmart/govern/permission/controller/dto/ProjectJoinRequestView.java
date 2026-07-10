/**
 * @Author : Cui
 * @Date: 2026/07/10 20:41
 * @Description DataSmart Govern Backend - ProjectJoinRequestView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import com.czh.datasmart.govern.permission.entity.PermissionProject;
import com.czh.datasmart.govern.permission.entity.PermissionProjectJoinRequest;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Low-sensitive project join request view returned to the console.
 */
public record ProjectJoinRequestView(Long id,
                                     Long tenantId,
                                     Long projectId,
                                     String projectCode,
                                     String projectName,
                                     Long applicantActorId,
                                     String applicantName,
                                     String applicantUsername,
                                     String requestedProjectRole,
                                     String requestReason,
                                     String status,
                                     Long reviewerActorId,
                                     String reviewerUsername,
                                     String reviewerActorRole,
                                     String reviewComment,
                                     LocalDateTime reviewTime,
                                     Long membershipId,
                                     LocalDateTime createTime,
                                     LocalDateTime updateTime) {

    public static ProjectJoinRequestView from(PermissionProjectJoinRequest request) {
        return from(request, null, Map.of());
    }

    /**
     * Builds the workflow view together with its project master data.
     *
     * <p>The join-request table stores the project foreign key, while the project name remains authoritative in
     * {@code permission_project}. Returning both here prevents every frontend page from inventing labels such as
     * "Project 101" or issuing one request per row.</p>
     */
    public static ProjectJoinRequestView from(PermissionProjectJoinRequest request,
                                              PermissionProject project) {
        return from(request, project, Map.of());
    }

    public static ProjectJoinRequestView from(PermissionProjectJoinRequest request,
                                              PermissionProject project,
                                              Map<Long, String> usernames) {
        if (request == null) {
            return null;
        }
        return new ProjectJoinRequestView(
                request.getId(),
                request.getTenantId(),
                request.getProjectId(),
                project == null ? null : project.getProjectCode(),
                project == null ? null : project.getProjectName(),
                request.getApplicantActorId(),
                request.getApplicantName(),
                username(usernames, request.getApplicantActorId()),
                request.getRequestedProjectRole(),
                request.getRequestReason(),
                request.getStatus(),
                request.getReviewerActorId(),
                username(usernames, request.getReviewerActorId()),
                request.getReviewerActorRole(),
                request.getReviewComment(),
                request.getReviewTime(),
                request.getMembershipId(),
                request.getCreateTime(),
                request.getUpdateTime()
        );
    }

    private static String username(Map<Long, String> usernames, Long actorId) {
        return actorId == null || usernames == null ? null : usernames.get(actorId);
    }
}

/**
 * @Author : Cui
 * @Date: 2026/07/10 20:41
 * @Description DataSmart Govern Backend - ProjectJoinRequestView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import com.czh.datasmart.govern.permission.entity.PermissionProjectJoinRequest;

import java.time.LocalDateTime;

/**
 * Low-sensitive project join request view returned to the console.
 */
public record ProjectJoinRequestView(Long id,
                                     Long tenantId,
                                     Long projectId,
                                     Long applicantActorId,
                                     String applicantName,
                                     String requestedProjectRole,
                                     String requestReason,
                                     String status,
                                     Long reviewerActorId,
                                     String reviewerActorRole,
                                     String reviewComment,
                                     LocalDateTime reviewTime,
                                     Long membershipId,
                                     LocalDateTime createTime,
                                     LocalDateTime updateTime) {

    public static ProjectJoinRequestView from(PermissionProjectJoinRequest request) {
        if (request == null) {
            return null;
        }
        return new ProjectJoinRequestView(
                request.getId(),
                request.getTenantId(),
                request.getProjectId(),
                request.getApplicantActorId(),
                request.getApplicantName(),
                request.getRequestedProjectRole(),
                request.getRequestReason(),
                request.getStatus(),
                request.getReviewerActorId(),
                request.getReviewerActorRole(),
                request.getReviewComment(),
                request.getReviewTime(),
                request.getMembershipId(),
                request.getCreateTime(),
                request.getUpdateTime()
        );
    }
}

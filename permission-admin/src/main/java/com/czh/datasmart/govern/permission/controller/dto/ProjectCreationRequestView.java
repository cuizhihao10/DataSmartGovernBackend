/**
 * @Author : Cui
 * @Date: 2026/07/10 04:29
 * @Description DataSmart Govern Backend - ProjectCreationRequestView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import com.czh.datasmart.govern.permission.entity.PermissionProjectCreationRequest;

import java.time.LocalDateTime;

/**
 * Low-sensitive project creation request view returned to the console.
 */
public record ProjectCreationRequestView(Long id,
                                         Long tenantId,
                                         Long applicationId,
                                         String projectCode,
                                         String projectName,
                                         String projectType,
                                         Long applicantActorId,
                                         String applicantName,
                                         Long ownerActorId,
                                         String description,
                                         String requestReason,
                                         String status,
                                         Long reviewerActorId,
                                         String reviewerActorRole,
                                         String reviewComment,
                                         LocalDateTime reviewTime,
                                         Long createdProjectId,
                                         LocalDateTime createTime,
                                         LocalDateTime updateTime) {

    public static ProjectCreationRequestView from(PermissionProjectCreationRequest request) {
        if (request == null) {
            return null;
        }
        return new ProjectCreationRequestView(
                request.getId(),
                request.getTenantId(),
                request.getApplicationId(),
                request.getProjectCode(),
                request.getProjectName(),
                request.getProjectType(),
                request.getApplicantActorId(),
                request.getApplicantName(),
                request.getOwnerActorId(),
                request.getDescription(),
                request.getRequestReason(),
                request.getStatus(),
                request.getReviewerActorId(),
                request.getReviewerActorRole(),
                request.getReviewComment(),
                request.getReviewTime(),
                request.getCreatedProjectId(),
                request.getCreateTime(),
                request.getUpdateTime()
        );
    }
}

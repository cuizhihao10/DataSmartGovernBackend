/**
 * @Author : Cui
 * @Date: 2026/07/10 11:39
 * @Description DataSmart Govern Backend - ApprovalCenterReviewRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.Size;

/**
 * Unified review payload dispatched to the concrete approval workflow.
 */
public record ApprovalCenterReviewRequest(
        String approvedProjectRole,
        @Size(max = 64, message = "projectCode length must not exceed 64")
        String projectCode,
        @Size(max = 128, message = "projectName length must not exceed 128")
        String projectName,
        @Size(max = 64, message = "projectType length must not exceed 64")
        String projectType,
        Long applicationId,
        Long ownerActorId,
        @Size(max = 1000, message = "description length must not exceed 1000")
        String description,
        @Size(max = 500, message = "reviewComment length must not exceed 500")
        String reviewComment) {
}

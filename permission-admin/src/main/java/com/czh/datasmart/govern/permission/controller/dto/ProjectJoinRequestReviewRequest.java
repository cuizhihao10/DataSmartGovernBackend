/**
 * @Author : Cui
 * @Date: 2026/07/10 20:41
 * @Description DataSmart Govern Backend - ProjectJoinRequestReviewRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.Size;

/**
 * Approval or rejection payload for a pending project join request.
 */
public record ProjectJoinRequestReviewRequest(
        @Size(max = 64, message = "approvedProjectRole length cannot exceed 64")
        String approvedProjectRole,
        @Size(max = 500, message = "reviewComment length cannot exceed 500")
        String reviewComment) {
}

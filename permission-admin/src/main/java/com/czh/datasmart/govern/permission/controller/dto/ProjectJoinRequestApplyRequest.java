/**
 * @Author : Cui
 * @Date: 2026/07/10 20:41
 * @Description DataSmart Govern Backend - ProjectJoinRequestApplyRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body used by a normal user to apply for project membership.
 */
public record ProjectJoinRequestApplyRequest(
        Long tenantId,
        @NotNull(message = "projectId cannot be null")
        Long projectId,
        @Size(max = 64, message = "requestedProjectRole length cannot exceed 64")
        String requestedProjectRole,
        @Size(max = 128, message = "applicantName length cannot exceed 128")
        String applicantName,
        @Size(max = 500, message = "requestReason length cannot exceed 500")
        String requestReason) {
}

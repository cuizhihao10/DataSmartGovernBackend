/**
 * @Author : Cui
 * @Date: 2026/07/10 20:57
 * @Description DataSmart Govern Backend - ProjectCreationRequestApplyRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body used by ordinary users to apply for creating a project.
 */
public record ProjectCreationRequestApplyRequest(
        Long tenantId,
        Long applicationId,
        @Size(max = 64, message = "projectCode length must not exceed 64")
        String projectCode,
        @NotBlank(message = "projectName cannot be blank")
        @Size(max = 128, message = "projectName length must not exceed 128")
        String projectName,
        @Size(max = 64, message = "projectType length must not exceed 64")
        String projectType,
        String applicantName,
        Long ownerActorId,
        @Size(max = 1000, message = "description length must not exceed 1000")
        String description,
        @Size(max = 500, message = "requestReason length must not exceed 500")
        String requestReason) {
}

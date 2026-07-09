/**
 * @Author : Cui
 * @Date: 2026/07/10 04:29
 * @Description DataSmart Govern Backend - ProjectCreationRequestMutationResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

/**
 * Mutation result returned by project creation approval operations.
 */
public record ProjectCreationRequestMutationResult(Long requestId,
                                                   Long tenantId,
                                                   Long applicationId,
                                                   Long applicantActorId,
                                                   String status,
                                                   Long createdProjectId,
                                                   String message) {
}

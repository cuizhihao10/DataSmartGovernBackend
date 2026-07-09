/**
 * @Author : Cui
 * @Date: 2026/07/10 20:41
 * @Description DataSmart Govern Backend - ProjectJoinRequestMutationResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

/**
 * Mutation result for project join request workflow actions.
 */
public record ProjectJoinRequestMutationResult(Long requestId,
                                               Long tenantId,
                                               Long projectId,
                                               Long applicantActorId,
                                               String status,
                                               Long membershipId,
                                               String message) {
}

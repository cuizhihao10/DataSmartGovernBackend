/**
 * @Author : Cui
 * @Date: 2026/07/10 11:39
 * @Description DataSmart Govern Backend - ApprovalCenterMutationResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

/**
 * Unified result returned after approval-center mutations.
 */
public record ApprovalCenterMutationResult(String requestType,
                                           Long requestId,
                                           String status,
                                           Long resultResourceId,
                                           String message) {
}

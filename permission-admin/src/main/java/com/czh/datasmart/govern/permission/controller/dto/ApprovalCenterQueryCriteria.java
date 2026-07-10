/**
 * @Author : Cui
 * @Date: 2026/07/10 11:39
 * @Description DataSmart Govern Backend - ApprovalCenterQueryCriteria.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

/**
 * Unified approval-center query criteria.
 */
public record ApprovalCenterQueryCriteria(Long tenantId,
                                          String requestType,
                                          String status,
                                          Long current,
                                          Long size) {
}

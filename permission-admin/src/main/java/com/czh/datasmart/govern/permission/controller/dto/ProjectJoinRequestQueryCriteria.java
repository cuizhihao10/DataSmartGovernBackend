/**
 * @Author : Cui
 * @Date: 2026/07/10 20:41
 * @Description DataSmart Govern Backend - ProjectJoinRequestQueryCriteria.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

/**
 * Query criteria for project join requests.
 */
public record ProjectJoinRequestQueryCriteria(Long tenantId,
                                              Long projectId,
                                              Long applicantActorId,
                                              String status,
                                              Long current,
                                              Long size) {
}

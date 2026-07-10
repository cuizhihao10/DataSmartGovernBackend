/**
 * @Author : Cui
 * @Date: 2026/07/10 14:05
 * @Description DataSmart Govern Backend - PermissionTenantOpenRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 平台开租请求。
 *
 * <p>tenantId/applicationId 均由 PostgreSQL 序列生成。开租会在一个事务内创建租户和默认 FlashSync 应用，
 * 但不会创建默认项目；项目仍由用户提交项目创建申请并经管理员审批。</p>
 */
public record PermissionTenantOpenRequest(
        @NotBlank @Size(max = 64) String tenantCode,
        @NotBlank @Size(max = 128) String tenantName,
        @Size(max = 32) String tenantType,
        @Size(max = 64) String planCode,
        @Positive Long ownerActorId,
        @Size(max = 64) String applicationCode,
        @Size(max = 128) String applicationName,
        @Size(max = 1000) String description,
        @Size(max = 500) String reason) {
}

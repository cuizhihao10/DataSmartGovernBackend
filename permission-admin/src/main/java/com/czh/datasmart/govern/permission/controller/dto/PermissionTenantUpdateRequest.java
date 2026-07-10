/**
 * @Author : Cui
 * @Date: 2026/07/10 14:05
 * @Description DataSmart Govern Backend - PermissionTenantUpdateRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 租户基础资料更新请求。
 *
 * <p>稳定 tenantCode 和内部 tenantId 不允许在编辑时修改，避免审计、Token claim 和业务表范围发生漂移。</p>
 */
public record PermissionTenantUpdateRequest(
        @Size(max = 128) String tenantName,
        @Size(max = 32) String tenantType,
        @Size(max = 64) String planCode,
        @Positive Long ownerActorId,
        @Size(max = 1000) String description,
        @Size(max = 500) String reason) {
}

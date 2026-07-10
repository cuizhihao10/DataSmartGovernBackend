/**
 * @Author : Cui
 * @Date: 2026/07/10 14:05
 * @Description DataSmart Govern Backend - PermissionTenantQueryCriteria.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

/**
 * 平台租户分页查询条件。
 */
public record PermissionTenantQueryCriteria(
        Long tenantId,
        String tenantCode,
        String tenantName,
        String tenantType,
        String status,
        Long current,
        Long size) {
}

/**
 * @Author : Cui
 * @Date: 2026/07/10 14:05
 * @Description DataSmart Govern Backend - PermissionTenantView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import com.czh.datasmart.govern.permission.entity.PermissionApplication;
import com.czh.datasmart.govern.permission.entity.PermissionTenant;

import java.time.LocalDateTime;

/**
 * 平台租户管理视图。
 *
 * <p>返回租户及其默认 FlashSync 应用摘要，让平台管理员能够确认一次开租是否完整，而不是只看到一条孤立租户记录。</p>
 */
public record PermissionTenantView(
        Long tenantId,
        String tenantCode,
        String tenantName,
        String tenantType,
        String planCode,
        String status,
        Long ownerActorId,
        Long openedBy,
        LocalDateTime openedAt,
        String description,
        Long applicationId,
        String applicationCode,
        String applicationName,
        String applicationStatus,
        LocalDateTime createTime,
        LocalDateTime updateTime) {

    public static PermissionTenantView from(PermissionTenant tenant, PermissionApplication application) {
        return new PermissionTenantView(
                tenant.getTenantId(),
                tenant.getTenantCode(),
                tenant.getTenantName(),
                tenant.getTenantType(),
                tenant.getPlanCode(),
                tenant.getStatus(),
                tenant.getOwnerActorId(),
                tenant.getOpenedBy(),
                tenant.getOpenedAt(),
                tenant.getDescription(),
                application == null ? null : application.getApplicationId(),
                application == null ? tenant.getDefaultApplicationCode() : application.getApplicationCode(),
                application == null ? null : application.getApplicationName(),
                application == null ? null : application.getStatus(),
                tenant.getCreateTime(),
                tenant.getUpdateTime());
    }
}

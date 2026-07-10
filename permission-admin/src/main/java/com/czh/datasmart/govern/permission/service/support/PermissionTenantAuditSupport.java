/**
 * @Author : Cui
 * @Date: 2026/07/10 14:10
 * @Description DataSmart Govern Backend - PermissionTenantAuditSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.support;

import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.entity.PermissionApplication;
import com.czh.datasmart.govern.permission.entity.PermissionAuditRecord;
import com.czh.datasmart.govern.permission.entity.PermissionTenant;
import com.czh.datasmart.govern.permission.mapper.PermissionAuditRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.jsonEscape;
import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.nullSafe;

/**
 * 租户开通和生命周期审计支持。
 *
 * <p>开租、暂停、恢复和关闭会影响整家公司在平台上的可用性，必须记录操作者、租户、应用和状态前后值。
 * 审计只保存低敏主数据，不保存密码、Token、连接信息或用户业务数据。</p>
 */
@Component
@RequiredArgsConstructor
public class PermissionTenantAuditSupport {

    public static final String RESOURCE_TYPE_TENANT = "TENANT";

    private final PermissionAuditRecordMapper auditRecordMapper;

    public void saveMutationAudit(PermissionActorContext actorContext,
                                  String action,
                                  String summary,
                                  PermissionTenant before,
                                  PermissionTenant after,
                                  PermissionApplication application) {
        PermissionTenant resource = after == null ? before : after;
        PermissionAuditRecord record = new PermissionAuditRecord();
        record.setTraceId(actorContext == null ? null : actorContext.traceId());
        record.setTenantId(resource == null ? 0L : resource.getTenantId());
        record.setActorId(actorContext == null ? null : actorContext.actorId());
        record.setActorRole(actorContext == null ? null : actorContext.actorRole());
        record.setResourceType(RESOURCE_TYPE_TENANT);
        record.setResourceId(resource == null ? "permission_tenant" : "permission_tenant:" + resource.getTenantId());
        record.setAction(action);
        record.setResult("SUCCESS");
        record.setSummary(summary);
        record.setDetailJson("{\"before\":" + tenantJson(before)
                + ",\"after\":" + tenantJson(after)
                + ",\"application\":" + applicationJson(application) + "}");
        record.setCreateTime(LocalDateTime.now());
        auditRecordMapper.insert(record);
    }

    private String tenantJson(PermissionTenant tenant) {
        if (tenant == null) {
            return "null";
        }
        return "{"
                + "\"tenantId\":\"" + nullSafe(tenant.getTenantId()) + "\","
                + "\"tenantCode\":\"" + jsonEscape(tenant.getTenantCode()) + "\","
                + "\"tenantName\":\"" + jsonEscape(tenant.getTenantName()) + "\","
                + "\"tenantType\":\"" + jsonEscape(tenant.getTenantType()) + "\","
                + "\"planCode\":\"" + jsonEscape(tenant.getPlanCode()) + "\","
                + "\"status\":\"" + jsonEscape(tenant.getStatus()) + "\","
                + "\"ownerActorId\":\"" + nullSafe(tenant.getOwnerActorId()) + "\""
                + "}";
    }

    private String applicationJson(PermissionApplication application) {
        if (application == null) {
            return "null";
        }
        return "{"
                + "\"applicationId\":\"" + nullSafe(application.getApplicationId()) + "\","
                + "\"applicationCode\":\"" + jsonEscape(application.getApplicationCode()) + "\","
                + "\"applicationName\":\"" + jsonEscape(application.getApplicationName()) + "\","
                + "\"status\":\"" + jsonEscape(application.getStatus()) + "\""
                + "}";
    }
}

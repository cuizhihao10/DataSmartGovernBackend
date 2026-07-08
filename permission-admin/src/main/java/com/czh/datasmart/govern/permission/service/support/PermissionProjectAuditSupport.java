/**
 * @Author : Cui
 * @Date: 2026/07/08 23:24
 * @Description DataSmart Govern Backend - PermissionProjectAuditSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.support;

import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.entity.PermissionAuditRecord;
import com.czh.datasmart.govern.permission.entity.PermissionProject;
import com.czh.datasmart.govern.permission.mapper.PermissionAuditRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.jsonEscape;
import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.normalizeTenantId;
import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.nullSafe;

/**
 * 项目主数据审计支持组件。
 *
 * <p>项目是当前产品去掉 workspace 后最核心的数据范围边界。
 * 一旦项目被错误创建、错误归属或错误授权，下游数据源、同步任务、数据质量规则和 Agent 会话都会受到影响。
 * 因此项目创建虽然不是“危险 SQL”这类高风险数据动作，也必须写审计证据。</p>
 */
@Component
@RequiredArgsConstructor
public class PermissionProjectAuditSupport {

    /**
     * 项目主数据资源类型。
     *
     * <p>该值同时用于 route policy、审计记录和未来按钮权限，避免把项目创建混入泛化 SYSTEM_SETTING。</p>
     */
    public static final String RESOURCE_TYPE_PROJECT = "PROJECT";

    private final PermissionAuditRecordMapper auditRecordMapper;

    /**
     * 保存项目变更审计。
     *
     * @param actorContext 当前操作者上下文。
     * @param action 动作编码，例如 CREATE_PROJECT。
     * @param result 操作结果，通常是 SUCCESS。
     * @param summary 低敏摘要。
     * @param before 变更前项目，创建时为空。
     * @param after 变更后项目。
     */
    public void saveMutationAudit(PermissionActorContext actorContext,
                                  String action,
                                  String result,
                                  String summary,
                                  PermissionProject before,
                                  PermissionProject after) {
        PermissionProject resource = after == null ? before : after;
        PermissionAuditRecord auditRecord = new PermissionAuditRecord();
        auditRecord.setTraceId(actorContext == null ? null : actorContext.traceId());
        auditRecord.setTenantId(resource == null ? normalizeTenantId(null) : normalizeTenantId(resource.getTenantId()));
        auditRecord.setActorId(actorContext == null ? null : actorContext.actorId());
        auditRecord.setActorRole(actorContext == null ? null : actorContext.actorRole());
        auditRecord.setResourceType(RESOURCE_TYPE_PROJECT);
        auditRecord.setResourceId(resourceId(resource));
        auditRecord.setAction(action);
        auditRecord.setResult(result);
        auditRecord.setSummary(summary);
        auditRecord.setDetailJson("{\"before\":" + projectJson(before) + ",\"after\":" + projectJson(after) + "}");
        auditRecord.setCreateTime(LocalDateTime.now());
        auditRecordMapper.insert(auditRecord);
    }

    private String resourceId(PermissionProject project) {
        if (project == null) {
            return "permission_project";
        }
        return "permission_project:" + nullSafe(project.getProjectId());
    }

    /**
     * 生成审计 JSON。
     *
     * <p>这里刻意不写入 workspace、密码、Token、连接串或任何业务样本。
     * 项目审计只需要保存低敏控制面字段，足够回答“哪个租户创建了哪个项目、挂在哪个应用下、负责人是谁”。</p>
     */
    private String projectJson(PermissionProject project) {
        if (project == null) {
            return "null";
        }
        return "{"
                + "\"projectId\":\"" + nullSafe(project.getProjectId()) + "\","
                + "\"tenantId\":\"" + nullSafe(project.getTenantId()) + "\","
                + "\"applicationId\":\"" + nullSafe(project.getApplicationId()) + "\","
                + "\"projectCode\":\"" + jsonEscape(project.getProjectCode()) + "\","
                + "\"projectName\":\"" + jsonEscape(project.getProjectName()) + "\","
                + "\"projectType\":\"" + jsonEscape(project.getProjectType()) + "\","
                + "\"status\":\"" + jsonEscape(project.getStatus()) + "\","
                + "\"ownerActorId\":\"" + nullSafe(project.getOwnerActorId()) + "\""
                + "}";
    }
}

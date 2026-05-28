/**
 * @Author : Cui
 * @Date: 2026/05/10 19:34
 * @Description DataSmart Govern Backend - PermissionProjectMembershipAuditSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.support;

import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.entity.PermissionAuditRecord;
import com.czh.datasmart.govern.permission.entity.PermissionProjectMembership;
import com.czh.datasmart.govern.permission.mapper.PermissionAuditRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.jsonEscape;
import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.normalizeTenantId;
import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.nullSafe;

/**
 * 项目成员授权变更审计支持组件。
 *
 * <p>为什么不直接在 Service 里拼审计记录？
 * 项目成员管理后续会继续扩展批量导入、组织同步、审批授权、成员有效期、授权继承等能力。
 * 如果每个 Service 方法都手写审计 JSON，很快会出现字段缺失、结果编码不一致、before/after 口径不同等问题。
 * 因此把审计封装为独立 support，Service 只表达业务动作，审计组件负责统一落表。
 */
@Component
@RequiredArgsConstructor
public class PermissionProjectMembershipAuditSupport {

    /**
     * 审计资源类型。
     *
     * <p>该值也会在 route policy 和 gateway route-metadata 中出现，用于把“项目成员授权管理”
     * 从普通系统设置里拆出来，便于后续单独配置按钮权限、审计报表和高风险审批。
     */
    public static final String RESOURCE_TYPE_PROJECT_MEMBERSHIP = "PROJECT_MEMBERSHIP";

    private final PermissionAuditRecordMapper auditRecordMapper;

    /**
     * 保存项目成员授权变更审计。
     *
     * @param actorContext 当前操作者上下文，用于记录谁发起了变更。
     * @param action 动作编码，例如 GRANT_PROJECT_MEMBERSHIP、DISABLE_PROJECT_MEMBERSHIP。
     * @param result 操作结果，通常是 SUCCESS 或 FAILED。
     * @param summary 摘要说明，适合在审计列表中直接展示。
     * @param before 变更前快照，新增时为空。
     * @param after 变更后快照，删除/禁用仍应传入变更后的记录，便于定位资源。
     */
    public void saveMutationAudit(PermissionActorContext actorContext,
                                  String action,
                                  String result,
                                  String summary,
                                  PermissionProjectMembership before,
                                  PermissionProjectMembership after) {
        PermissionProjectMembership resource = after == null ? before : after;
        PermissionAuditRecord auditRecord = new PermissionAuditRecord();
        auditRecord.setTraceId(actorContext == null ? null : actorContext.traceId());
        auditRecord.setTenantId(resource == null ? normalizeTenantId(null) : normalizeTenantId(resource.getTenantId()));
        auditRecord.setActorId(actorContext == null ? null : actorContext.actorId());
        auditRecord.setActorRole(actorContext == null ? null : actorContext.actorRole());
        auditRecord.setResourceType(RESOURCE_TYPE_PROJECT_MEMBERSHIP);
        auditRecord.setResourceId(resourceId(resource));
        auditRecord.setAction(action);
        auditRecord.setResult(result);
        auditRecord.setSummary(summary);
        auditRecord.setDetailJson("{\"before\":" + membershipJson(before) + ",\"after\":" + membershipJson(after) + "}");
        auditRecord.setCreateTime(LocalDateTime.now());
        auditRecordMapper.insert(auditRecord);
    }

    /**
     * 生成审计资源 ID。
     *
     * <p>优先使用数据库主键；如果新增前还没有主键，则退化为 tenant/actor/project 组合，
     * 这样审计记录仍能定位到授权关系。
     */
    private String resourceId(PermissionProjectMembership membership) {
        if (membership == null) {
            return "permission_project_membership";
        }
        if (membership.getId() != null) {
            return "permission_project_membership:" + membership.getId();
        }
        return "permission_project_membership:"
                + nullSafe(membership.getTenantId()) + ":"
                + nullSafe(membership.getActorId()) + ":"
                + nullSafe(membership.getProjectId());
    }

    /**
     * 生成轻量 JSON 快照。
     *
     * <p>当前项目没有强制引入 ObjectMapper 到 support 层，先沿用 permission-admin 既有手写 JSON 风格。
     * 如果成员字段继续增加，后续可以把审计 JSON 序列化统一替换成 Jackson。
     */
    private String membershipJson(PermissionProjectMembership membership) {
        if (membership == null) {
            return "null";
        }
        return "{"
                + "\"id\":\"" + nullSafe(membership.getId()) + "\","
                + "\"tenantId\":\"" + nullSafe(membership.getTenantId()) + "\","
                + "\"actorId\":\"" + nullSafe(membership.getActorId()) + "\","
                + "\"projectId\":\"" + nullSafe(membership.getProjectId()) + "\","
                + "\"workspaceId\":\"" + nullSafe(membership.getWorkspaceId()) + "\","
                + "\"projectRole\":\"" + jsonEscape(membership.getProjectRole()) + "\","
                + "\"grantSource\":\"" + jsonEscape(membership.getGrantSource()) + "\","
                + "\"enabled\":\"" + nullSafe(membership.getEnabled()) + "\""
                + "}";
    }
}

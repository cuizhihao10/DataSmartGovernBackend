package com.czh.datasmart.govern.permission.service.support;

import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.PermissionDecisionRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionDecisionResult;
import com.czh.datasmart.govern.permission.entity.PermissionAuditRecord;
import com.czh.datasmart.govern.permission.entity.PermissionRoutePolicy;
import com.czh.datasmart.govern.permission.mapper.PermissionAuditRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Locale;

import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.RESOURCE_TYPE_SYSTEM_SETTING;
import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.jsonEscape;
import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.normalizeTenantId;
import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.nullSafe;

/**
 * @Author : Cui
 * @Date: 2026/05/06 00:19
 * @Description DataSmart Govern Backend - PermissionAuditSupport.java
 * @Version:1.0.0
 *
 * 权限审计支持组件。
 *
 * <p>权限中心的审计非常重要，因为它回答两个关键问题：
 * 1. 某一次访问为什么被允许或拒绝；
 * 2. 某一条权限策略是谁在什么时候改成了什么。
 *
 * <p>本组件统一写入 `permission_audit_record`，让访问判定审计和策略变更审计共享一张轨迹表。
 * 这不是最终的审计中心形态，但已经为后续接入 audit-center、合规导出、SIEM 投递、
 * 高风险操作审批复盘预留了稳定入口。
 */
@Component
@RequiredArgsConstructor
public class PermissionAuditSupport {

    private final PermissionAuditRecordMapper auditRecordMapper;

    /**
     * 保存一次访问判定审计。
     *
     * <p>生产环境中通常可以对 ALLOW 做采样，对 DENY、高风险资源、跨租户访问做 100% 记录。
     * 当前为了学习和可追踪性，先完整记录每次判定摘要。
     */
    public void saveDecisionAudit(PermissionDecisionRequest request, String traceId, PermissionDecisionResult result) {
        PermissionAuditRecord auditRecord = new PermissionAuditRecord();
        auditRecord.setTraceId(traceId);
        auditRecord.setTenantId(normalizeTenantId(request.getTenantId()));
        auditRecord.setActorId(request.getActorId());
        auditRecord.setActorRole(request.getActorRole());
        auditRecord.setResourceType(request.getResourceType());
        auditRecord.setResourceId(request.getRequestPath());
        auditRecord.setAction(request.getAction() == null ? request.getHttpMethod().toUpperCase(Locale.ROOT) : request.getAction());
        auditRecord.setResult(Boolean.TRUE.equals(result.getAllowed()) ? "SUCCESS" : "DENIED");
        auditRecord.setSummary(result.getReason());
        auditRecord.setDetailJson("{\"httpMethod\":\"" + jsonEscape(request.getHttpMethod())
                + "\",\"requestPath\":\"" + jsonEscape(request.getRequestPath())
                + "\",\"actorType\":\"" + jsonEscape(request.getActorType())
                + "\",\"workspaceId\":\"" + jsonEscape(request.getWorkspaceId())
                + "\",\"requestSource\":\"" + jsonEscape(request.getRequestSource())
                + "\",\"matchedRoutePolicyId\":\"" + nullSafe(result.getMatchedRoutePolicyId())
                + "\",\"policyVersion\":\"" + jsonEscape(result.getPolicyVersion())
                + "\",\"delegated\":\"" + nullSafe(result.getDelegated())
                + "\",\"serviceAccountActorId\":\"" + nullSafe(request.getServiceAccountActorId())
                + "\",\"serviceAccountCode\":\"" + jsonEscape(request.getServiceAccountCode())
                + "\",\"representedActorId\":\"" + jsonEscape(request.getRepresentedActorId())
                + "\",\"delegationType\":\"" + jsonEscape(request.getDelegationType())
                + "\",\"delegationReason\":\"" + jsonEscape(request.getDelegationReason())
                + "\",\"delegationEvidence\":\"" + jsonEscape(result.getDelegationEvidence()) + "\"}");
        auditRecord.setCreateTime(LocalDateTime.now());
        auditRecordMapper.insert(auditRecord);
    }

    /**
     * 保存路由策略变更审计。
     *
     * <p>策略变更可能扩大权限，也可能收紧权限，均属于高风险管理动作。
     * before/after 快照可以帮助后续追踪“谁把某个接口开放给了某个角色”。
     */
    public void saveRoutePolicyMutationAudit(PermissionActorContext actorContext,
                                             String action,
                                             String result,
                                             String summary,
                                             PermissionRoutePolicy afterPolicy,
                                             PermissionRoutePolicy beforePolicy) {
        PermissionAuditRecord auditRecord = new PermissionAuditRecord();
        auditRecord.setTraceId(actorContext == null ? null : actorContext.traceId());
        auditRecord.setTenantId(afterPolicy == null ? PermissionAdminSupport.PLATFORM_TENANT_ID : normalizeTenantId(afterPolicy.getTenantId()));
        auditRecord.setActorId(actorContext == null ? null : actorContext.actorId());
        auditRecord.setActorRole(actorContext == null ? null : actorContext.actorRole());
        auditRecord.setResourceType(RESOURCE_TYPE_SYSTEM_SETTING);
        auditRecord.setResourceId(afterPolicy == null || afterPolicy.getId() == null
                ? "permission_route_policy"
                : "permission_route_policy:" + afterPolicy.getId());
        auditRecord.setAction(action);
        auditRecord.setResult(result);
        auditRecord.setSummary(summary);
        auditRecord.setDetailJson(routePolicyAuditDetail(beforePolicy, afterPolicy));
        auditRecord.setCreateTime(LocalDateTime.now());
        auditRecordMapper.insert(auditRecord);
    }

    private String routePolicyAuditDetail(PermissionRoutePolicy beforePolicy, PermissionRoutePolicy afterPolicy) {
        return "{\"before\":" + routePolicyJson(beforePolicy) + ",\"after\":" + routePolicyJson(afterPolicy) + "}";
    }

    private String routePolicyJson(PermissionRoutePolicy policy) {
        if (policy == null) {
            return "null";
        }
        return "{"
                + "\"id\":\"" + nullSafe(policy.getId()) + "\","
                + "\"tenantId\":\"" + nullSafe(policy.getTenantId()) + "\","
                + "\"policyName\":\"" + jsonEscape(policy.getPolicyName()) + "\","
                + "\"roleCode\":\"" + jsonEscape(policy.getRoleCode()) + "\","
                + "\"httpMethod\":\"" + jsonEscape(policy.getHttpMethod()) + "\","
                + "\"pathPattern\":\"" + jsonEscape(policy.getPathPattern()) + "\","
                + "\"resourceType\":\"" + jsonEscape(policy.getResourceType()) + "\","
                + "\"action\":\"" + jsonEscape(policy.getAction()) + "\","
                + "\"effect\":\"" + jsonEscape(policy.getEffect()) + "\","
                + "\"priority\":\"" + nullSafe(policy.getPriority()) + "\","
                + "\"enabled\":\"" + nullSafe(policy.getEnabled()) + "\""
                + "}";
    }
}

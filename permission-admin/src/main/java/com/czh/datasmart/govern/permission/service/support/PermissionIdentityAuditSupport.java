/**
 * @Author : Cui
 * @Date: 2026/07/05 03:26
 * @Description DataSmartGovernBackend - PermissionIdentityAuditSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.support;

import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.entity.PermissionAuditRecord;
import com.czh.datasmart.govern.permission.entity.PermissionIdentityUser;
import com.czh.datasmart.govern.permission.mapper.PermissionAuditRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.jsonEscape;
import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.normalizeTenantId;
import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.nullSafe;

/**
 * 身份供应审计支持。
 *
 * <p>账号创建、禁用、重置密码都属于高风险管理动作。审计记录要能回答“谁、在哪个租户、对哪个外部身份、
 * 执行了什么动作、结果如何、为什么执行”。同时审计 detail 必须保持低敏：不能写入密码、Token、client secret、
 * Keycloak endpoint、邮箱完整明文或外部错误响应体。
 */
@Component
@RequiredArgsConstructor
public class PermissionIdentityAuditSupport {

    private static final String RESOURCE_TYPE_IDENTITY_USER = "IDENTITY_USER";

    private final PermissionAuditRecordMapper auditRecordMapper;

    /**
     * 保存身份供应审计记录。
     *
     * @param actorContext 当前操作人上下文，来自 gateway 注入的 X-DataSmart-* Header
     * @param action 业务动作，例如 CREATE_IDENTITY_USER、DISABLE_IDENTITY_USER、RESET_IDENTITY_PASSWORD
     * @param result SUCCESS、FAILED 或 DENIED
     * @param summary 低敏摘要，便于审计员快速理解本次动作
     * @param identityUser 影子身份记录，可以为空；为空时使用 fallbackResourceId 记录资源线索
     * @param fallbackResourceId 无影子记录时的资源线索，例如 providerUserId 或 username 的低敏引用
     * @param reason 操作原因，必须是低敏文本
     */
    public void saveIdentityAudit(PermissionActorContext actorContext,
                                  String action,
                                  String result,
                                  String summary,
                                  PermissionIdentityUser identityUser,
                                  String fallbackResourceId,
                                  String reason) {
        PermissionAuditRecord auditRecord = new PermissionAuditRecord();
        auditRecord.setTraceId(actorContext == null ? null : actorContext.traceId());
        auditRecord.setTenantId(identityUser == null
                ? normalizeTenantId(actorContext == null ? null : actorContext.tenantId())
                : normalizeTenantId(identityUser.getTenantId()));
        auditRecord.setActorId(actorContext == null ? null : actorContext.actorId());
        auditRecord.setActorRole(actorContext == null ? null : actorContext.actorRole());
        auditRecord.setResourceType(RESOURCE_TYPE_IDENTITY_USER);
        auditRecord.setResourceId(resourceId(identityUser, fallbackResourceId));
        auditRecord.setAction(action);
        auditRecord.setResult(result);
        auditRecord.setSummary(summary);
        auditRecord.setDetailJson(detailJson(identityUser, fallbackResourceId, reason));
        auditRecord.setCreateTime(LocalDateTime.now());
        auditRecordMapper.insert(auditRecord);
    }

    /**
     * 构造审计资源 ID。
     *
     * <p>优先使用 providerUserId，因为它能稳定定位外部 IdP 用户；没有影子记录时使用调用方传入的低敏线索。
     */
    private String resourceId(PermissionIdentityUser identityUser, String fallbackResourceId) {
        if (identityUser != null && identityUser.getProviderUserId() != null) {
            return "identity_user:" + identityUser.getProviderUserId();
        }
        if (fallbackResourceId != null && !fallbackResourceId.isBlank()) {
            return "identity_user:" + fallbackResourceId.trim();
        }
        return "identity_user:unknown";
    }

    /**
     * 构造低敏 detailJson。
     *
     * <p>这里手写 JSON 是为了保持与现有权限审计风格一致。字段只包含 providerMode、providerUserId、actorId、
     * actorRole、actorType、status 等控制面事实，不包含密码、Token、clientSecret 或完整邮箱。
     */
    private String detailJson(PermissionIdentityUser identityUser, String fallbackResourceId, String reason) {
        if (identityUser == null) {
            return "{\"resourceHint\":\"" + jsonEscape(fallbackResourceId)
                    + "\",\"reason\":\"" + jsonEscape(reason)
                    + "\",\"payloadPolicy\":\"NO_PASSWORD_NO_TOKEN_NO_SECRET\"}";
        }
        return "{"
                + "\"identityId\":\"" + nullSafe(identityUser.getId()) + "\","
                + "\"providerMode\":\"" + jsonEscape(identityUser.getProviderMode()) + "\","
                + "\"providerUserId\":\"" + jsonEscape(identityUser.getProviderUserId()) + "\","
                + "\"tenantId\":\"" + nullSafe(identityUser.getTenantId()) + "\","
                + "\"actorId\":\"" + nullSafe(identityUser.getActorId()) + "\","
                + "\"actorRole\":\"" + jsonEscape(identityUser.getActorRole()) + "\","
                + "\"actorType\":\"" + jsonEscape(identityUser.getActorType()) + "\","
                + "\"workspaceId\":\"" + jsonEscape(identityUser.getWorkspaceId()) + "\","
                + "\"status\":\"" + jsonEscape(identityUser.getStatus()) + "\","
                + "\"reason\":\"" + jsonEscape(reason) + "\","
                + "\"payloadPolicy\":\"NO_PASSWORD_NO_TOKEN_NO_SECRET\""
                + "}";
    }
}

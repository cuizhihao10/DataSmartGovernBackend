/**
 * @Author : Cui
 * @Date: 2026/07/05 03:26
 * @Description DataSmartGovernBackend - IdentityProvisioningController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.permission.controller.dto.AuthorizationSubjectCandidateQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.AuthorizationSubjectCandidateView;
import com.czh.datasmart.govern.permission.controller.dto.IdentityProvisioningCapabilityView;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserDisableRequest;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserPasswordResetRequest;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserProvisionResult;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserRegisterRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.service.IdentityProvisioningService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 身份供应控制器。
 *
 * <p>该控制器补齐“系统账号从哪里来”的管理入口，但它不是登录表单，也不是 OAuth2 token endpoint。
 * 用户真正登录仍然访问 Keycloak/企业 IdP，登录成功后由 gateway 验证 JWT 并注入 X-DataSmart-* 上下文。
 *
 * <p>路由设计：
 * 1. /identity/** 用于服务本地直连调试；
 * 2. /api/identity/** 用于 gateway 外部统一入口；
 * 3. gateway 会把 /api/identity/** 改写到 /identity/**，避免 permission-admin 长期感知外部 API 前缀。
 */
@RestController
@RequestMapping({"/identity", "/api/identity"})
@RequiredArgsConstructor
public class IdentityProvisioningController {

    private final IdentityProvisioningService identityProvisioningService;

    /**
     * 查询身份供应能力。
     *
     * <p>该接口用于管理后台、smoke 脚本和运维人员确认当前环境是否启用了 Keycloak/企业 IdP 账号供应。
     * 响应只包含低敏能力说明，不返回 IdP endpoint、admin 凭据、client secret 或任何 token。
     */
    @GetMapping("/capabilities")
    public PlatformApiResponse<IdentityProvisioningCapabilityView> capabilities(
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(identityProvisioningService.capabilities(), traceId);
    }

    /**
     * 查询业务资源授权弹窗可选择的用户/角色候选。
     *
     * <p>典型使用方式：
     * 1. 数据源管理页面点击“授权”；
     * 2. 前端根据当前项目 projectId 调用本接口搜索 USER 或 ROLE；
     * 3. 用户选择候选后，把响应里的 subjectType、subjectId、subjectName、subjectRole 原样写入
     * datasource-management 的实例级授权请求；
     * 4. datasource-management 再保存自己的资源 ACL 账本。</p>
     *
     * <p>该接口只返回低敏候选信息，不返回密码、token、Keycloak 管理凭据或完整邮箱。
     * 它也不替代业务服务的最终授权写入与资源访问校验。</p>
     */
    @GetMapping("/authorization-subjects")
    public PlatformApiResponse<PlatformPageResponse<AuthorizationSubjectCandidateView>> pageAuthorizationSubjectCandidates(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(required = false) Boolean projectMembersOnly,
            @RequestParam(required = false) Long current,
            @RequestParam(required = false) Long size,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        AuthorizationSubjectCandidateQueryCriteria criteria = new AuthorizationSubjectCandidateQueryCriteria(
                tenantId, projectId, subjectType, keyword, activeOnly, projectMembersOnly, current, size);
        return PlatformApiResponse.success("授权主体候选查询成功",
                identityProvisioningService.pageAuthorizationSubjectCandidates(
                        criteria, actorContext(actorTenantId, actorId, actorRole, traceId)),
                traceId);
    }

    /**
     * 创建用户账号。
     *
     * <p>调用方必须已经通过 gateway 认证和权限判定。服务层会再次校验操作者角色和租户边界：
     * 租户管理员只能创建本租户账号；平台管理员可以跨租户创建账号。密码只转发给 IdP，不进入 DataSmart 数据库。
     */
    @PostMapping("/users/register")
    public PlatformApiResponse<IdentityUserProvisionResult> registerUser(
            @Valid @RequestBody IdentityUserRegisterRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("身份账号创建成功",
                identityProvisioningService.registerUser(request, actorContext(actorTenantId, actorId, actorRole, traceId)),
                traceId);
    }

    /**
     * 禁用用户账号。
     *
     * <p>providerUserId 来自 Keycloak/企业 IdP。禁用后，外部 IdP 用户会被置为不可登录，本地影子身份进入 DISABLED。
     * 这里不做物理删除，因为账号生命周期和审计追溯比“表面清理”更重要。
     */
    @PostMapping("/users/{providerUserId}/disable")
    public PlatformApiResponse<IdentityUserProvisionResult> disableUser(
            @PathVariable String providerUserId,
            @Valid @RequestBody(required = false) IdentityUserDisableRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("身份账号禁用成功",
                identityProvisioningService.disableUser(providerUserId, request,
                        actorContext(actorTenantId, actorId, actorRole, traceId)),
                traceId);
    }

    /**
     * 重置用户密码。
     *
     * <p>该接口只把新密码提交给 Keycloak/企业 IdP。响应和审计中只记录“密码已重置”这一事实，不记录密码明文。
     * 生产环境后续可以升级为“发送重置密码邮件/一次性链接”，当前同步重置先保证本地 Keycloak 闭环。
     */
    @PostMapping("/users/{providerUserId}/password/reset")
    public PlatformApiResponse<IdentityUserProvisionResult> resetPassword(
            @PathVariable String providerUserId,
            @Valid @RequestBody IdentityUserPasswordResetRequest request,
            @RequestHeader(value = PlatformContextHeaders.TENANT_ID, required = false) Long actorTenantId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ID, required = false) Long actorId,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success("身份账号密码重置成功",
                identityProvisioningService.resetPassword(providerUserId, request,
                        actorContext(actorTenantId, actorId, actorRole, traceId)),
                traceId);
    }

    /**
     * 从 gateway 注入的 Header 构造操作人上下文。
     */
    private PermissionActorContext actorContext(Long tenantId, Long actorId, String actorRole, String traceId) {
        return new PermissionActorContext(tenantId, actorId, actorRole, traceId);
    }
}

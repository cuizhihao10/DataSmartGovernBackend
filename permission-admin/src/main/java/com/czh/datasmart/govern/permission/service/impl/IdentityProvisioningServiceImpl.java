/**
 * @Author : Cui
 * @Date: 2026/07/05 03:26
 * @Description DataSmartGovernBackend - IdentityProvisioningServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.permission.config.IdentityProvisioningProperties;
import com.czh.datasmart.govern.permission.controller.dto.IdentityProvisioningCapabilityView;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserDisableRequest;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserPasswordResetRequest;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserProvisionResult;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserRegisterRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.entity.PermissionIdentityUser;
import com.czh.datasmart.govern.permission.mapper.PermissionIdentityUserMapper;
import com.czh.datasmart.govern.permission.service.IdentityProvisioningService;
import com.czh.datasmart.govern.permission.service.identity.IdentityProviderAdminClient;
import com.czh.datasmart.govern.permission.service.identity.IdentityProviderOperationResult;
import com.czh.datasmart.govern.permission.service.identity.IdentityProviderUserCreateCommand;
import com.czh.datasmart.govern.permission.service.support.PermissionIdentityAuditSupport;
import com.czh.datasmart.govern.permission.support.PermissionRoleCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.czh.datasmart.govern.permission.service.support.PermissionAdminSupport.normalizeTenantId;

/**
 * 身份供应业务服务。
 *
 * <p>本服务把“注册登录能力”拆成两个层次：
 * 1. 登录认证：由 Keycloak/企业 IdP 负责，gateway 只验证 JWT，不收密码；
 * 2. 账号供应：由 permission-admin 代表管理员调用 IdP 创建/禁用/重置账号，并保存 DataSmart 影子身份。
 *
 * <p>为什么不在 gateway 写 username/password 登录接口？
 * gateway 是入口网关，适合做 Token 校验、上下文注入、限流、路由和鉴权，不适合持有密码、处理 MFA、管理账号锁定。
 * 这些能力交给成熟 IdP，项目才能更接近真实企业部署形态。
 */
@Service
@RequiredArgsConstructor
public class IdentityProvisioningServiceImpl implements IdentityProvisioningService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String ACTOR_TYPE_USER = "USER";
    private static final String PAYLOAD_POLICY = "NO_PASSWORD_NO_TOKEN_NO_SECRET";

    /**
     * 允许执行账号供应写动作的角色。
     *
     * <p>租户管理员只能管理自己租户；平台管理员可以跨租户。普通用户、项目负责人、运营、审计员都不能创建或重置账号。
     */
    private static final Set<String> MUTATION_ROLES = Set.of(
            PermissionRoleCode.TENANT_ADMINISTRATOR.name(),
            PermissionRoleCode.PLATFORM_ADMINISTRATOR.name()
    );

    private final IdentityProvisioningProperties properties;
    private final IdentityProviderAdminClient identityProviderAdminClient;
    private final PermissionIdentityUserMapper identityUserMapper;
    private final PermissionIdentityAuditSupport auditSupport;

    /**
     * 查询身份供应能力。
     *
     * <p>该方法可用于管理后台展示“当前账号体系如何工作”：用户在哪里登录、DataSmart 是否保存密码、
     * 当前支持哪些账号动作。它故意不返回 baseUrl、admin client、secret 等敏感配置。
     */
    @Override
    public IdentityProvisioningCapabilityView capabilities() {
        return new IdentityProvisioningCapabilityView(
                properties.isEnabled(),
                providerMode(),
                false,
                true,
                "Keycloak/Enterprise IdP",
                List.of("REGISTER_USER", "DISABLE_USER", "RESET_PASSWORD", "SHADOW_IDENTITY_AUDIT"),
                List.of("OIDC_RESOURCE_SERVER_LOGIN", "IDP_OWNS_PASSWORD", "DATASMART_STORES_SHADOW_IDENTITY"),
                "用户登录发生在 Keycloak/企业 IdP；DataSmart 只保存低敏影子身份、权限和审计事实。");
    }

    /**
     * 创建 IdP 用户并写入本地影子身份。
     *
     * <p>当前采用同步调用：先调用 Keycloak 创建外部用户，再写本地影子表。商用高可用版本可以继续演进为 outbox/saga：
     * 1. 先写 PENDING_PROVISION 影子记录；
     * 2. 通过可靠任务调用 IdP；
     * 3. 根据结果更新 ACTIVE/FAILED；
     * 4. 提供补偿删除或人工修复入口。
     *
     * <p>本阶段先完成最小闭环，但把幂等键、审计和 providerUserId 保存好，后续升级不会推翻接口。
     */
    @Override
    @Transactional
    public IdentityUserProvisionResult registerUser(IdentityUserRegisterRequest request, PermissionActorContext actorContext) {
        requireEnabled();
        requireMutationRole(actorContext);
        Long tenantId = resolveTargetTenantId(request.tenantId(), actorContext);
        Long actorId = resolveActorId(request.actorId());
        String actorRole = normalizeRole(request.actorRole());
        String actorType = normalizeActorType(request.actorType());
        String username = requireText(request.username(), "username");
        validateLocalUniqueness(tenantId, actorId, username);

        IdentityProviderUserCreateCommand command = new IdentityProviderUserCreateCommand(
                username,
                trimToNull(request.email()),
                trimToNull(request.firstName()),
                trimToNull(request.lastName()),
                request.password(),
                request.temporaryPassword() == null || request.temporaryPassword(),
                request.enabled() == null || request.enabled(),
                Boolean.TRUE.equals(request.emailVerified()),
                tenantId,
                actorId,
                actorRole,
                actorType,
                trimToNull(request.workspaceId()),
                realmRoleName(actorRole)
        );

        IdentityProviderOperationResult providerResult = identityProviderAdminClient.createUser(command);
        PermissionIdentityUser identityUser = new PermissionIdentityUser();
        identityUser.setTenantId(tenantId);
        identityUser.setActorId(actorId);
        identityUser.setProviderMode(providerMode());
        identityUser.setProviderUserId(providerResult.providerUserId());
        identityUser.setUsername(username);
        identityUser.setEmail(trimToNull(request.email()));
        identityUser.setActorRole(actorRole);
        identityUser.setActorType(actorType);
        identityUser.setWorkspaceId(trimToNull(request.workspaceId()));
        identityUser.setStatus(STATUS_ACTIVE);
        identityUser.setCreateTime(LocalDateTime.now());
        identityUser.setUpdateTime(LocalDateTime.now());
        identityUserMapper.insert(identityUser);

        String reason = defaultText(request.reason(), "管理员创建身份账号");
        auditSupport.saveIdentityAudit(actorContext, "CREATE_IDENTITY_USER", "SUCCESS",
                "身份账号已在外部 IdP 创建并写入 DataSmart 影子身份", identityUser, username, reason);
        return result("REGISTER_USER", identityUser, "账号已创建，用户应通过 Keycloak/企业 IdP 登录",
                providerResult.evidenceCodes());
    }

    /**
     * 禁用 IdP 用户并同步本地影子状态。
     */
    @Override
    @Transactional
    public IdentityUserProvisionResult disableUser(String providerUserId,
                                                   IdentityUserDisableRequest request,
                                                   PermissionActorContext actorContext) {
        requireEnabled();
        requireMutationRole(actorContext);
        PermissionIdentityUser identityUser = findByProviderUserId(providerUserId);
        validateTenantBoundary(identityUser.getTenantId(), actorContext);

        String reason = defaultText(request == null ? null : request.reason(), "管理员禁用身份账号");
        IdentityProviderOperationResult providerResult = identityProviderAdminClient.disableUser(providerUserId, reason);
        identityUser.setStatus(STATUS_DISABLED);
        identityUser.setDisabledReason(reason);
        identityUser.setUpdateTime(LocalDateTime.now());
        identityUserMapper.updateById(identityUser);

        auditSupport.saveIdentityAudit(actorContext, "DISABLE_IDENTITY_USER", "SUCCESS",
                "身份账号已在外部 IdP 禁用，并同步 DataSmart 影子状态", identityUser, providerUserId, reason);
        return result("DISABLE_USER", identityUser, "账号已禁用，后续登录将由 IdP 拒绝或失去有效授权",
                providerResult.evidenceCodes());
    }

    /**
     * 重置 IdP 用户密码。
     *
     * <p>重置密码不会改变本地影子身份字段，只更新 updateTime 并写审计。真正的密码策略、强度校验、
     * 首次登录改密和 MFA 仍由 IdP 执行。
     */
    @Override
    @Transactional
    public IdentityUserProvisionResult resetPassword(String providerUserId,
                                                     IdentityUserPasswordResetRequest request,
                                                     PermissionActorContext actorContext) {
        requireEnabled();
        requireMutationRole(actorContext);
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "重置密码请求不能为空");
        }
        PermissionIdentityUser identityUser = findByProviderUserId(providerUserId);
        validateTenantBoundary(identityUser.getTenantId(), actorContext);

        IdentityProviderOperationResult providerResult = identityProviderAdminClient.resetPassword(
                providerUserId, request.password(), request.temporaryPassword() == null || request.temporaryPassword());
        identityUser.setUpdateTime(LocalDateTime.now());
        identityUserMapper.updateById(identityUser);

        String reason = defaultText(request.reason(), "管理员重置身份账号密码");
        auditSupport.saveIdentityAudit(actorContext, "RESET_IDENTITY_PASSWORD", "SUCCESS",
                "身份账号密码已在外部 IdP 重置，DataSmart 未保存新密码", identityUser, providerUserId, reason);
        return result("RESET_PASSWORD", identityUser, "密码已由 IdP 重置，DataSmart 未保存密码",
                providerResult.evidenceCodes());
    }

    /**
     * 校验身份供应开关。
     */
    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "当前环境未启用 DataSmart 身份供应，请改由企业 IdP 或组织同步系统创建账号");
        }
    }

    /**
     * 校验当前操作者是否具备账号供应写权限。
     */
    private void requireMutationRole(PermissionActorContext actorContext) {
        String actorRole = normalizeCode(actorContext == null ? null : actorContext.actorRole());
        if (!MUTATION_ROLES.contains(actorRole)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "当前角色无权管理身份账号，actorRole=" + actorRole);
        }
    }

    /**
     * 解析目标租户，并阻止租户管理员跨租户创建或维护账号。
     */
    private Long resolveTargetTenantId(Long requestedTenantId, PermissionActorContext actorContext) {
        Long targetTenantId = normalizeTenantId(requestedTenantId == null
                ? actorContext == null ? null : actorContext.tenantId()
                : requestedTenantId);
        validateTenantBoundary(targetTenantId, actorContext);
        return targetTenantId;
    }

    /**
     * 校验租户边界。
     *
     * <p>平台管理员可跨租户；租户管理员只能管理自己租户。这里不允许普通用户进入，因为入口角色校验已经提前拦截。
     */
    private void validateTenantBoundary(Long targetTenantId, PermissionActorContext actorContext) {
        String actorRole = normalizeCode(actorContext == null ? null : actorContext.actorRole());
        if (PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(actorRole)) {
            return;
        }
        Long actorTenantId = normalizeTenantId(actorContext == null ? null : actorContext.tenantId());
        if (!actorTenantId.equals(normalizeTenantId(targetTenantId))) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "租户管理员只能管理本租户身份账号，actorTenantId=" + actorTenantId + ", targetTenantId=" + targetTenantId);
        }
    }

    /**
     * 解析 DataSmart actorId。
     */
    private Long resolveActorId(Long requestedActorId) {
        if (requestedActorId != null) {
            if (requestedActorId <= 0) {
                throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "actorId 必须为正数");
            }
            return requestedActorId;
        }
        Long generated = identityUserMapper.nextActorId();
        if (generated == null || generated <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED, "无法生成 DataSmart actorId");
        }
        return generated;
    }

    /**
     * 校验本地影子身份唯一性。
     */
    private void validateLocalUniqueness(Long tenantId, Long actorId, String username) {
        PermissionIdentityUser sameActor = identityUserMapper.selectOne(new LambdaQueryWrapper<PermissionIdentityUser>()
                .eq(PermissionIdentityUser::getTenantId, tenantId)
                .eq(PermissionIdentityUser::getActorId, actorId));
        if (sameActor != null) {
            throw new PlatformBusinessException(PlatformErrorCode.DUPLICATE_OPERATION,
                    "该租户下 actorId 已经绑定身份账号，actorId=" + actorId);
        }
        PermissionIdentityUser sameUsername = identityUserMapper.selectOne(new LambdaQueryWrapper<PermissionIdentityUser>()
                .eq(PermissionIdentityUser::getProviderMode, providerMode())
                .eq(PermissionIdentityUser::getUsername, username));
        if (sameUsername != null) {
            throw new PlatformBusinessException(PlatformErrorCode.DUPLICATE_OPERATION,
                    "该用户名已经存在身份影子记录，username=" + username);
        }
    }

    private PermissionIdentityUser findByProviderUserId(String providerUserId) {
        String safeProviderUserId = requireText(providerUserId, "providerUserId");
        PermissionIdentityUser identityUser = identityUserMapper.selectOne(new LambdaQueryWrapper<PermissionIdentityUser>()
                .eq(PermissionIdentityUser::getProviderMode, providerMode())
                .eq(PermissionIdentityUser::getProviderUserId, safeProviderUserId));
        if (identityUser == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "身份影子记录不存在，providerUserId=" + safeProviderUserId);
        }
        return identityUser;
    }

    /**
     * 规范化目标用户角色。
     */
    private String normalizeRole(String role) {
        String normalized = defaultText(normalizeCode(role), PermissionRoleCode.ORDINARY_USER.name());
        try {
            PermissionRoleCode.valueOf(normalized);
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "不支持的 DataSmart 角色：" + normalized);
        }
    }

    private String normalizeActorType(String actorType) {
        return defaultText(normalizeCode(actorType), ACTOR_TYPE_USER);
    }

    private String realmRoleName(String actorRole) {
        String prefix = properties.getKeycloak().getRealmRolePrefix() == null ? "" : properties.getKeycloak().getRealmRolePrefix();
        return actorRole.startsWith(prefix) ? actorRole : prefix + actorRole;
    }

    private IdentityUserProvisionResult result(String operation,
                                               PermissionIdentityUser identityUser,
                                               String message,
                                               List<String> evidenceCodes) {
        List<String> safeEvidenceCodes = new ArrayList<>();
        if (evidenceCodes != null) {
            safeEvidenceCodes.addAll(evidenceCodes);
        }
        safeEvidenceCodes.add("DATASMART_SHADOW_IDENTITY_UPDATED");
        safeEvidenceCodes.add(PAYLOAD_POLICY);
        return new IdentityUserProvisionResult(
                operation,
                identityUser.getProviderMode(),
                identityUser.getProviderUserId(),
                identityUser.getId(),
                identityUser.getUsername(),
                maskEmail(identityUser.getEmail()),
                identityUser.getTenantId(),
                identityUser.getActorId(),
                identityUser.getActorRole(),
                identityUser.getActorType(),
                identityUser.getWorkspaceId(),
                identityUser.getStatus(),
                message,
                PAYLOAD_POLICY,
                safeEvidenceCodes);
    }

    private String providerMode() {
        return defaultText(normalizeCode(properties.getProviderMode()), "KEYCLOAK_ADMIN_API");
    }

    private String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String trimmed = email.trim();
        int at = trimmed.indexOf('@');
        if (at <= 1) {
            return "***" + (at >= 0 ? trimmed.substring(at) : "");
        }
        return trimmed.charAt(0) + "***" + trimmed.substring(at);
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, fieldName + " 不能为空");
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String normalizeCode(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}

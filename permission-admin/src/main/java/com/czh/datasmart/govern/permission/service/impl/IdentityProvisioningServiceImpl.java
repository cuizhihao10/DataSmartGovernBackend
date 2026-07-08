/**
 * @Author : Cui
 * @Date: 2026/07/05 03:26
 * @Description DataSmartGovernBackend - IdentityProvisioningServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.permission.config.IdentityProvisioningProperties;
import com.czh.datasmart.govern.permission.controller.dto.AuthorizationSubjectCandidateQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.AuthorizationSubjectCandidateView;
import com.czh.datasmart.govern.permission.controller.dto.IdentityProvisioningCapabilityView;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserDisableRequest;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserPasswordResetRequest;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserProvisionResult;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserRegisterRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.entity.PermissionIdentityUser;
import com.czh.datasmart.govern.permission.entity.PermissionProjectMembership;
import com.czh.datasmart.govern.permission.entity.PermissionRole;
import com.czh.datasmart.govern.permission.mapper.PermissionIdentityUserMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionProjectMembershipMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionRoleMapper;
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
import java.util.stream.Collectors;

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
    private static final long DEFAULT_PAGE_SIZE = 10L;
    private static final long MAX_CANDIDATE_PAGE_SIZE = 100L;

    /**
     * 允许执行账号供应写动作的角色。
     *
     * <p>租户管理员只能管理自己租户；平台管理员可以跨租户。普通用户、项目负责人、运营、审计员都不能创建或重置账号。
     */
    private static final Set<String> MUTATION_ROLES = Set.of(
            PermissionRoleCode.TENANT_ADMINISTRATOR.name(),
            PermissionRoleCode.PLATFORM_ADMINISTRATOR.name()
    );

    /**
     * 允许读取低敏授权候选的角色集合。
     *
     * <p>候选查询不是账号生命周期写操作，因此比 {@link #MUTATION_ROLES} 更宽：
     * 项目负责人需要在项目内把数据源、同步任务等资源授权给项目成员；
     * 审计员需要只读复盘“授权弹窗当时为什么能选到某个主体”；
     * 租户管理员和平台管理员则用于账号治理与排障。</p>
     */
    private static final Set<String> CANDIDATE_READ_ROLES = Set.of(
            PermissionRoleCode.PROJECT_OWNER.name(),
            PermissionRoleCode.TENANT_ADMINISTRATOR.name(),
            PermissionRoleCode.PLATFORM_ADMINISTRATOR.name(),
            PermissionRoleCode.AUDITOR.name()
    );

    private final IdentityProvisioningProperties properties;
    private final IdentityProviderAdminClient identityProviderAdminClient;
    private final PermissionIdentityUserMapper identityUserMapper;
    private final PermissionRoleMapper roleMapper;
    private final PermissionProjectMembershipMapper projectMembershipMapper;
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
     * 查询授权弹窗使用的用户/角色候选。
     *
     * <p>为什么把该能力放在 identity service，而不是放在 datasource-management？
     * 数据源授权只是“资源授权”的一个消费者，真正的用户、角色、项目成员关系事实源属于 permission-admin。
     * 如果每个业务服务都自己查身份表，就会形成跨服务数据库耦合，未来接入企业 IdP、SCIM、组织同步或角色体系重构时会非常难迁移。
     * 因此这里提供一个稳定 HTTP 合同：业务页面先查候选，再把候选的 subjectType/subjectId 写入各自领域的 ACL 表。</p>
     */
    @Override
    public PlatformPageResponse<AuthorizationSubjectCandidateView> pageAuthorizationSubjectCandidates(
            AuthorizationSubjectCandidateQueryCriteria criteria,
            PermissionActorContext actorContext) {
        requireCandidateReadRole(actorContext);
        AuthorizationSubjectCandidateQueryCriteria safeCriteria = criteria == null
                ? new AuthorizationSubjectCandidateQueryCriteria(null, null, null, null, null, null, null, null)
                : criteria;
        String subjectType = defaultText(normalizeCode(safeCriteria.subjectType()), "USER");
        Long tenantId = resolveReadableTenantId(safeCriteria.tenantId(), actorContext);
        Long projectId = safeCriteria.projectId();
        validateCandidateProjectBoundary(tenantId, projectId, actorContext);
        return switch (subjectType) {
            case "USER" -> pageUserCandidates(safeCriteria, tenantId, projectId);
            case "ROLE" -> pageRoleCandidates(safeCriteria, tenantId, projectId);
            default -> throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "授权主体候选只支持 USER 或 ROLE，当前 subjectType=" + subjectType);
        };
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
     * 查询 USER 类型候选。
     *
     * <p>默认策略：如果调用方传入 projectId，则只返回该项目的启用成员。这样数据源授权弹窗不会把整个租户用户都暴露出来，
     * 也不会让项目负责人误把项目外用户授权到当前项目数据源。租户管理员/平台管理员如果确实需要租户级账号检索，
     * 可以传 {@code projectMembersOnly=false} 做治理排查。</p>
     */
    private PlatformPageResponse<AuthorizationSubjectCandidateView> pageUserCandidates(
            AuthorizationSubjectCandidateQueryCriteria criteria,
            Long tenantId,
            Long projectId) {
        boolean activeOnly = criteria.activeOnly() == null || criteria.activeOnly();
        boolean projectMembersOnly = criteria.projectMembersOnly() == null
                ? projectId != null
                : criteria.projectMembersOnly();
        Set<Long> projectActorIds = projectMembersOnly
                ? findProjectMemberActorIds(tenantId, projectId)
                : Set.of();
        if (projectMembersOnly && projectActorIds.isEmpty()) {
            return PlatformPageResponse.of(normalizeCurrent(criteria.current()), normalizeSize(criteria.size()), 0L, List.of());
        }

        LambdaQueryWrapper<PermissionIdentityUser> wrapper = new LambdaQueryWrapper<PermissionIdentityUser>()
                .eq(PermissionIdentityUser::getTenantId, tenantId)
                .eq(activeOnly, PermissionIdentityUser::getStatus, STATUS_ACTIVE)
                .in(projectMembersOnly, PermissionIdentityUser::getActorId, projectActorIds)
                .and(hasText(criteria.keyword()), query -> applyUserKeyword(query, criteria.keyword()))
                .orderByAsc(PermissionIdentityUser::getUsername)
                .orderByDesc(PermissionIdentityUser::getUpdateTime);

        IPage<PermissionIdentityUser> page = identityUserMapper.selectPage(
                new Page<>(normalizeCurrent(criteria.current()), normalizeSize(criteria.size())), wrapper);
        List<AuthorizationSubjectCandidateView> records = page.getRecords().stream()
                .map(user -> AuthorizationSubjectCandidateView.fromUser(user, projectId, maskEmail(user.getEmail())))
                .toList();
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    /**
     * 查询 ROLE 类型候选。
     *
     * <p>角色授权适合“项目内所有项目负责人都能使用某条数据源”这类场景。这里会同时查询平台内置角色 tenantId=0
     * 和当前租户自定义角色 tenantId=当前租户。若后续角色体系支持应用级、项目级角色覆盖，可以继续在这里扩展作用域字段，
     * 但对外仍保持 subjectType=ROLE、subjectId=roleCode 的稳定授权合同。</p>
     */
    private PlatformPageResponse<AuthorizationSubjectCandidateView> pageRoleCandidates(
            AuthorizationSubjectCandidateQueryCriteria criteria,
            Long tenantId,
            Long projectId) {
        boolean activeOnly = criteria.activeOnly() == null || criteria.activeOnly();
        LambdaQueryWrapper<PermissionRole> wrapper = new LambdaQueryWrapper<PermissionRole>()
                .in(PermissionRole::getTenantId, List.of(0L, tenantId))
                .eq(activeOnly, PermissionRole::getEnabled, true)
                .and(hasText(criteria.keyword()), query -> applyRoleKeyword(query, criteria.keyword()))
                .orderByAsc(PermissionRole::getTenantId)
                .orderByAsc(PermissionRole::getRoleCode);
        IPage<PermissionRole> page = roleMapper.selectPage(
                new Page<>(normalizeCurrent(criteria.current()), normalizeSize(criteria.size())), wrapper);
        List<AuthorizationSubjectCandidateView> records = page.getRecords().stream()
                .map(role -> AuthorizationSubjectCandidateView.fromRole(role, projectId))
                .toList();
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    /**
     * 查询某项目已启用成员 actorId 集合。
     *
     * <p>当前先使用同步查询并把 actorId 放入内存集合，足够支撑授权弹窗分页搜索。
     * 如果客户单项目成员达到数十万级，后续应改为自定义 SQL join identity_user + project_membership，
     * 或把项目成员候选沉淀成搜索索引，避免 IN 列表过大。</p>
     */
    private Set<Long> findProjectMemberActorIds(Long tenantId, Long projectId) {
        if (tenantId == null || projectId == null) {
            return Set.of();
        }
        return projectMembershipMapper.selectList(new LambdaQueryWrapper<PermissionProjectMembership>()
                        .eq(PermissionProjectMembership::getTenantId, tenantId)
                        .eq(PermissionProjectMembership::getProjectId, projectId)
                        .eq(PermissionProjectMembership::getEnabled, true))
                .stream()
                .map(PermissionProjectMembership::getActorId)
                .filter(actorId -> actorId != null && actorId > 0)
                .collect(Collectors.toSet());
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
     * 校验当前操作者是否可以读取授权候选。
     *
     * <p>该校验是 service 层的二次防线。gateway/permission-admin 路由策略负责“请求能否进入接口”，
     * service 层继续负责“即使本地直连接口，也不能绕过身份候选的租户和项目边界”。</p>
     */
    private void requireCandidateReadRole(PermissionActorContext actorContext) {
        String actorRole = normalizeCode(actorContext == null ? null : actorContext.actorRole());
        if (!CANDIDATE_READ_ROLES.contains(actorRole)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "当前角色无权查询授权主体候选，actorRole=" + actorRole);
        }
    }

    /**
     * 解析候选查询租户。
     *
     * <p>平台管理员可以显式传 tenantId 做跨租户排障；其他角色只能查询自己所在租户。
     * 如果非平台角色试图传入其他 tenantId，会触发 {@link #validateTenantBoundary(Long, PermissionActorContext)}。</p>
     */
    private Long resolveReadableTenantId(Long requestedTenantId, PermissionActorContext actorContext) {
        Long targetTenantId = normalizeTenantId(requestedTenantId == null
                ? actorContext == null ? null : actorContext.tenantId()
                : requestedTenantId);
        validateTenantBoundary(targetTenantId, actorContext);
        return targetTenantId;
    }

    /**
     * 校验项目级候选查询边界。
     *
     * <p>项目负责人只能查询自己已启用成员关系所在的项目。租户管理员、平台管理员和审计员不在这里强制项目成员校验，
     * 但仍受租户边界保护；后续如果客户要求审计员也只能看授权项目，可以在这里继续收紧。</p>
     */
    private void validateCandidateProjectBoundary(Long tenantId, Long projectId, PermissionActorContext actorContext) {
        String actorRole = normalizeCode(actorContext == null ? null : actorContext.actorRole());
        if (!PermissionRoleCode.PROJECT_OWNER.name().equals(actorRole)) {
            return;
        }
        if (projectId == null) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "项目负责人查询授权候选时必须指定 projectId，避免越权搜索整个租户用户");
        }
        Long actorId = actorContext == null ? null : actorContext.actorId();
        if (actorId == null || actorId <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "项目负责人查询授权候选时必须携带有效 actorId");
        }
        Long count = projectMembershipMapper.selectCount(new LambdaQueryWrapper<PermissionProjectMembership>()
                .eq(PermissionProjectMembership::getTenantId, tenantId)
                .eq(PermissionProjectMembership::getProjectId, projectId)
                .eq(PermissionProjectMembership::getActorId, actorId)
                .eq(PermissionProjectMembership::getEnabled, true));
        if (count == null || count <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "项目负责人只能查询自己已授权项目内的主体候选，projectId=" + projectId);
        }
    }

    /**
     * 给 USER 候选追加关键字过滤。
     *
     * <p>actorId 是数字字段，因此仅当关键字可以解析为正整数时才追加精确匹配；
     * username/email/actorRole 是字符串字段，使用 LIKE 支撑授权弹窗的模糊搜索。</p>
     */
    private void applyUserKeyword(LambdaQueryWrapper<PermissionIdentityUser> query, String keyword) {
        String safeKeyword = keyword.trim();
        query.like(PermissionIdentityUser::getUsername, safeKeyword)
                .or()
                .like(PermissionIdentityUser::getEmail, safeKeyword)
                .or()
                .like(PermissionIdentityUser::getActorRole, normalizeCode(safeKeyword));
        Long actorId = parsePositiveLong(safeKeyword);
        if (actorId != null) {
            query.or().eq(PermissionIdentityUser::getActorId, actorId);
        }
    }

    /**
     * 给 ROLE 候选追加关键字过滤。
     */
    private void applyRoleKeyword(LambdaQueryWrapper<PermissionRole> query, String keyword) {
        String safeKeyword = keyword.trim();
        query.like(PermissionRole::getRoleCode, normalizeCode(safeKeyword))
                .or()
                .like(PermissionRole::getRoleName, safeKeyword)
                .or()
                .like(PermissionRole::getDescription, safeKeyword);
    }

    /**
     * 规范化候选查询页码。
     */
    private long normalizeCurrent(Long current) {
        return current == null || current <= 0 ? 1L : current;
    }

    /**
     * 规范化候选查询分页大小，并限制最大值。
     *
     * <p>授权弹窗属于高频管理交互，如果允许任意 size，会导致一次请求把大量身份数据拉到浏览器和网关链路上；
     * 因此这里限制最大 100 条，后续如需批量授权应走异步导入或批量接口，而不是靠超大分页。</p>
     */
    private long normalizeSize(Long size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_CANDIDATE_PAGE_SIZE);
    }

    private Long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeCode(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}

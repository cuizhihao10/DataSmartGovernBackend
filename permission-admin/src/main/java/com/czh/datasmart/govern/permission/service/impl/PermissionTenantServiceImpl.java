/**
 * @Author : Cui
 * @Date: 2026/07/10 14:10
 * @Description DataSmart Govern Backend - PermissionTenantServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.PermissionTenantOpenRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionTenantQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.PermissionTenantStatusChangeRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionTenantUpdateRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionTenantView;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserProvisionResult;
import com.czh.datasmart.govern.permission.controller.dto.IdentityUserRegisterRequest;
import com.czh.datasmart.govern.permission.entity.PermissionApplication;
import com.czh.datasmart.govern.permission.entity.PermissionTenant;
import com.czh.datasmart.govern.permission.entity.PermissionIdentityUser;
import com.czh.datasmart.govern.permission.mapper.PermissionApplicationMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionTenantMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionIdentityUserMapper;
import com.czh.datasmart.govern.permission.service.IdentityProvisioningService;
import com.czh.datasmart.govern.permission.service.PermissionTenantService;
import com.czh.datasmart.govern.permission.service.support.PermissionTenantAuditSupport;
import com.czh.datasmart.govern.permission.support.PermissionRoleCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * 平台租户控制面实现。
 *
 * <p>服务层固定只接受平台超级管理员。开租写入 permission_tenant、permission_application，
 * 并通过身份供应服务创建首个租户管理员。PostgreSQL 写入在同一事务中回滚，外部 IdP 调用保留独立审计证据。</p>
 */
@Service
@RequiredArgsConstructor
public class PermissionTenantServiceImpl implements PermissionTenantService {

    private static final long DEFAULT_CURRENT = 1L;
    private static final long DEFAULT_PAGE_SIZE = 20L;
    private static final long MAX_PAGE_SIZE = 100L;
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_SUSPENDED = "SUSPENDED";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String APPLICATION_ACTIVE = "ACTIVE";
    private static final String APPLICATION_DISABLED = "DISABLED";
    private static final String APPLICATION_ARCHIVED = "ARCHIVED";
    private static final String DEFAULT_APPLICATION_CODE = "FLASHSYNC";
    private static final String DEFAULT_APPLICATION_NAME = "FlashSync";
    private static final Pattern STABLE_CODE = Pattern.compile("[A-Z][A-Z0-9_-]{1,63}");
    private static final Set<String> TENANT_TYPES = Set.of("PLATFORM", "BUSINESS", "INTERNAL");
    private static final Set<String> TENANT_STATUSES = Set.of(STATUS_ACTIVE, STATUS_SUSPENDED, STATUS_CLOSED);

    private final PermissionTenantMapper tenantMapper;
    private final PermissionApplicationMapper applicationMapper;
    private final PermissionIdentityUserMapper identityUserMapper;
    private final IdentityProvisioningService identityProvisioningService;
    private final PermissionTenantAuditSupport auditSupport;

    @Override
    public PlatformPageResponse<PermissionTenantView> pageTenants(PermissionTenantQueryCriteria criteria,
                                                                  PermissionActorContext actorContext) {
        requirePlatformAdministrator(actorContext);
        PermissionTenantQueryCriteria safe = criteria == null
                ? new PermissionTenantQueryCriteria(null, null, null, null, null, null, null)
                : criteria;
        LambdaQueryWrapper<PermissionTenant> wrapper = new LambdaQueryWrapper<PermissionTenant>()
                .orderByDesc(PermissionTenant::getTenantId);
        if (safe.tenantId() != null) {
            wrapper.eq(PermissionTenant::getTenantId, safe.tenantId());
        }
        likeIfPresent(wrapper, PermissionTenant::getTenantCode, safe.tenantCode());
        likeIfPresent(wrapper, PermissionTenant::getTenantName, safe.tenantName());
        eqCodeIfPresent(wrapper, PermissionTenant::getTenantType, safe.tenantType(), TENANT_TYPES, "tenantType");
        eqCodeIfPresent(wrapper, PermissionTenant::getStatus, safe.status(), TENANT_STATUSES, "status");
        Page<PermissionTenant> page = tenantMapper.selectPage(page(safe.current(), safe.size()), wrapper);
        List<PermissionTenantView> records = views(page.getRecords());
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), records);
    }

    @Override
    public PermissionTenantView getTenant(Long tenantId, PermissionActorContext actorContext) {
        requirePlatformAdministrator(actorContext);
        return view(findTenant(tenantId));
    }

    @Override
    @Transactional
    public PermissionTenantView openTenant(PermissionTenantOpenRequest request, PermissionActorContext actorContext) {
        requirePlatformAdministrator(actorContext);
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "开租请求不能为空");
        }
        String tenantCode = stableCode(request.tenantCode(), "tenantCode");
        ensureTenantCodeAvailable(tenantCode);
        String applicationCode = stableCode(defaultText(request.applicationCode(), DEFAULT_APPLICATION_CODE),
                "applicationCode");
        Long tenantId = nextTenantId();
        Long applicationId = nextApplicationId();
        LocalDateTime now = LocalDateTime.now();

        PermissionTenant tenant = new PermissionTenant();
        tenant.setTenantId(tenantId);
        tenant.setTenantCode(tenantCode);
        tenant.setTenantName(requiredText(request.tenantName(), "tenantName"));
        tenant.setTenantType(allowedCode(request.tenantType(), "BUSINESS", TENANT_TYPES, "tenantType"));
        tenant.setPlanCode(normalizeCode(defaultText(request.planCode(), "STANDARD")));
        tenant.setStatus(STATUS_ACTIVE);
        tenant.setDefaultApplicationCode(applicationCode);
        tenant.setOwnerActorId(null);
        tenant.setOpenedBy(requirePositive(actorContext.actorId(), "actorId"));
        tenant.setOpenedAt(now);
        tenant.setDescription(trimToNull(request.description()));
        tenant.setCreateTime(now);
        tenant.setUpdateTime(now);
        tenantMapper.insert(tenant);

        PermissionApplication application = new PermissionApplication();
        application.setApplicationId(applicationId);
        application.setTenantId(tenantId);
        application.setApplicationCode(applicationCode);
        application.setApplicationName(defaultText(request.applicationName(), DEFAULT_APPLICATION_NAME));
        application.setApplicationType("DATA_SYNC_AGENT_APP");
        application.setStatus(APPLICATION_ACTIVE);
        application.setHomepagePath("/dashboard");
        application.setOwnerActorId(null);
        application.setDescription("FlashSync 数据同步与 Agent 应用，由平台开租流程自动创建。");
        application.setCreateTime(now);
        application.setUpdateTime(now);
        applicationMapper.insert(application);

        IdentityUserProvisionResult administratorResult = identityProvisioningService.registerUser(
                new IdentityUserRegisterRequest(
                        request.administratorUsername(),
                        request.administratorEmail(),
                        request.administratorFirstName(),
                        request.administratorLastName(),
                        request.administratorInitialPassword(),
                        request.administratorTemporaryPassword() == null || request.administratorTemporaryPassword(),
                        tenantId,
                        request.ownerActorId(),
                        PermissionRoleCode.TENANT_ADMINISTRATOR.name(),
                        "USER",
                        null,
                        true,
                        false,
                        defaultText(request.reason(), "开租时创建首个租户管理员")),
                actorContext);

        tenant.setOwnerActorId(administratorResult.actorId());
        tenant.setUpdateTime(LocalDateTime.now());
        tenantMapper.updateById(tenant);
        application.setOwnerActorId(administratorResult.actorId());
        application.setUpdateTime(LocalDateTime.now());
        applicationMapper.updateById(application);

        PermissionIdentityUser administrator = new PermissionIdentityUser();
        administrator.setTenantId(tenantId);
        administrator.setActorId(administratorResult.actorId());
        administrator.setUsername(administratorResult.username());
        administrator.setActorRole(administratorResult.actorRole());
        administrator.setStatus(administratorResult.status());

        auditSupport.saveMutationAudit(actorContext, "OPEN_TENANT",
                defaultText(request.reason(), "平台开通租户及 FlashSync 应用"), null, tenant, application);
        return PermissionTenantView.from(tenant, application, administrator);
    }

    @Override
    @Transactional
    public PermissionTenantView updateTenant(Long tenantId,
                                             PermissionTenantUpdateRequest request,
                                             PermissionActorContext actorContext) {
        requirePlatformAdministrator(actorContext);
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "租户更新请求不能为空");
        }
        PermissionTenant tenant = findTenant(tenantId);
        ensureNotClosed(tenant, "已关闭租户不能修改基础资料");
        PermissionTenant before = copyOf(tenant);
        if (trimToNull(request.tenantName()) != null) {
            tenant.setTenantName(trimToNull(request.tenantName()));
        }
        if (trimToNull(request.tenantType()) != null) {
            tenant.setTenantType(allowedCode(request.tenantType(), null, TENANT_TYPES, "tenantType"));
        }
        if (trimToNull(request.planCode()) != null) {
            tenant.setPlanCode(normalizeCode(request.planCode()));
        }
        if (request.ownerActorId() != null) {
            tenant.setOwnerActorId(request.ownerActorId());
        }
        if (request.description() != null) {
            tenant.setDescription(trimToNull(request.description()));
        }
        tenant.setUpdateTime(LocalDateTime.now());
        tenantMapper.updateById(tenant);
        PermissionApplication application = defaultApplication(tenant);
        if (application != null && request.ownerActorId() != null) {
            application.setOwnerActorId(request.ownerActorId());
            application.setUpdateTime(LocalDateTime.now());
            applicationMapper.updateById(application);
        }
        auditSupport.saveMutationAudit(actorContext, "UPDATE_TENANT",
                defaultText(request.reason(), "平台修改租户基础资料"), before, tenant, application);
        return PermissionTenantView.from(tenant, application, administrator(tenant));
    }

    @Override
    @Transactional
    public PermissionTenantView activateTenant(Long tenantId,
                                               PermissionTenantStatusChangeRequest request,
                                               PermissionActorContext actorContext) {
        PermissionTenant tenant = findAndRequirePlatform(tenantId, actorContext);
        if (!STATUS_SUSPENDED.equals(tenant.getStatus())) {
            throw stateConflict("只有 SUSPENDED 租户可以恢复启用");
        }
        return changeStatus(tenant, STATUS_ACTIVE, APPLICATION_ACTIVE, "ACTIVATE_TENANT", request, actorContext);
    }

    @Override
    @Transactional
    public PermissionTenantView suspendTenant(Long tenantId,
                                              PermissionTenantStatusChangeRequest request,
                                              PermissionActorContext actorContext) {
        PermissionTenant tenant = findAndRequirePlatform(tenantId, actorContext);
        if (!STATUS_ACTIVE.equals(tenant.getStatus())) {
            throw stateConflict("只有 ACTIVE 租户可以暂停");
        }
        return changeStatus(tenant, STATUS_SUSPENDED, APPLICATION_DISABLED, "SUSPEND_TENANT", request, actorContext);
    }

    @Override
    @Transactional
    public PermissionTenantView closeTenant(Long tenantId,
                                            PermissionTenantStatusChangeRequest request,
                                            PermissionActorContext actorContext) {
        PermissionTenant tenant = findAndRequirePlatform(tenantId, actorContext);
        ensureNotClosed(tenant, "租户已经关闭");
        return changeStatus(tenant, STATUS_CLOSED, APPLICATION_ARCHIVED, "CLOSE_TENANT", request, actorContext);
    }

    private PermissionTenantView changeStatus(PermissionTenant tenant,
                                              String tenantStatus,
                                              String applicationStatus,
                                              String action,
                                              PermissionTenantStatusChangeRequest request,
                                              PermissionActorContext actorContext) {
        if (request == null || trimToNull(request.reason()) == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "租户状态变更原因不能为空");
        }
        PermissionTenant before = copyOf(tenant);
        tenant.setStatus(tenantStatus);
        tenant.setUpdateTime(LocalDateTime.now());
        tenantMapper.updateById(tenant);
        PermissionApplication application = defaultApplication(tenant);
        if (application != null) {
            application.setStatus(applicationStatus);
            application.setUpdateTime(LocalDateTime.now());
            applicationMapper.updateById(application);
        }
        auditSupport.saveMutationAudit(actorContext, action, request.reason(), before, tenant, application);
        return PermissionTenantView.from(tenant, application, administrator(tenant));
    }

    private PermissionTenant findAndRequirePlatform(Long tenantId, PermissionActorContext actorContext) {
        requirePlatformAdministrator(actorContext);
        return findTenant(tenantId);
    }

    private PermissionTenant findTenant(Long tenantId) {
        PermissionTenant tenant = tenantMapper.selectById(requirePositive(tenantId, "tenantId"));
        if (tenant == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "租户不存在：" + tenantId);
        }
        return tenant;
    }

    private PermissionTenantView view(PermissionTenant tenant) {
        return PermissionTenantView.from(tenant, defaultApplication(tenant), administrator(tenant));
    }

    /**
     * 批量组装租户与默认应用视图，避免管理列表出现 N+1 查询。
     *
     * <p>一个页面最多 100 个租户，逐条读取应用会把一次列表请求放大为 101 次 SQL。这里先批量读取当前页
     * 所有租户的应用，再按 tenantId + applicationCode 关联，数据库访问固定为两次。</p>
     */
    private List<PermissionTenantView> views(List<PermissionTenant> tenants) {
        if (tenants == null || tenants.isEmpty()) {
            return List.of();
        }
        Set<Long> tenantIds = tenants.stream()
                .map(PermissionTenant::getTenantId)
                .collect(Collectors.toSet());
        Map<String, PermissionApplication> applications = applicationMapper.selectList(
                        new LambdaQueryWrapper<PermissionApplication>()
                                .in(PermissionApplication::getTenantId, tenantIds))
                .stream()
                .collect(Collectors.toMap(
                        application -> applicationKey(application.getTenantId(), application.getApplicationCode()),
                        Function.identity(),
                        (left, right) -> left.getApplicationId() <= right.getApplicationId() ? left : right));
        Map<String, PermissionIdentityUser> administrators = administrators(tenants);
        return tenants.stream()
                .map(tenant -> PermissionTenantView.from(tenant, applications.get(
                                applicationKey(tenant.getTenantId(), tenant.getDefaultApplicationCode())),
                        administrators.get(administratorKey(tenant.getTenantId(), tenant.getOwnerActorId()))))
                .toList();
    }

    private PermissionIdentityUser administrator(PermissionTenant tenant) {
        if (tenant == null || tenant.getOwnerActorId() == null) {
            return null;
        }
        return identityUserMapper.selectOne(new LambdaQueryWrapper<PermissionIdentityUser>()
                .eq(PermissionIdentityUser::getTenantId, tenant.getTenantId())
                .eq(PermissionIdentityUser::getActorId, tenant.getOwnerActorId())
                .in(PermissionIdentityUser::getActorRole,
                        PermissionRoleCode.TENANT_ADMINISTRATOR.name(),
                        PermissionRoleCode.PLATFORM_ADMINISTRATOR.name())
                .last("LIMIT 1"));
    }

    private Map<String, PermissionIdentityUser> administrators(List<PermissionTenant> tenants) {
        Set<Long> ownerActorIds = tenants.stream()
                .map(PermissionTenant::getOwnerActorId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ownerActorIds.isEmpty()) {
            return Map.of();
        }
        List<PermissionIdentityUser> identities = identityUserMapper.selectList(new LambdaQueryWrapper<PermissionIdentityUser>()
                        .in(PermissionIdentityUser::getActorId, ownerActorIds)
                        .in(PermissionIdentityUser::getActorRole,
                                PermissionRoleCode.TENANT_ADMINISTRATOR.name(),
                                PermissionRoleCode.PLATFORM_ADMINISTRATOR.name()));
        if (identities == null) {
            return Map.of();
        }
        return identities.stream()
                .collect(Collectors.toMap(
                        identity -> administratorKey(identity.getTenantId(), identity.getActorId()),
                        Function.identity(),
                        (left, right) -> left.getId() <= right.getId() ? left : right));
    }

    private String administratorKey(Long tenantId, Long actorId) {
        return tenantId + ":" + actorId;
    }

    private PermissionApplication defaultApplication(PermissionTenant tenant) {
        return applicationMapper.selectOne(new LambdaQueryWrapper<PermissionApplication>()
                .eq(PermissionApplication::getTenantId, tenant.getTenantId())
                .eq(PermissionApplication::getApplicationCode, tenant.getDefaultApplicationCode())
                .last("LIMIT 1"));
    }

    private String applicationKey(Long tenantId, String applicationCode) {
        return tenantId + ":" + normalizeCode(applicationCode);
    }

    private void ensureTenantCodeAvailable(String tenantCode) {
        Long count = tenantMapper.selectCount(new LambdaQueryWrapper<PermissionTenant>()
                .eq(PermissionTenant::getTenantCode, tenantCode));
        if (count != null && count > 0) {
            throw new PlatformBusinessException(PlatformErrorCode.DUPLICATE_OPERATION,
                    "租户编码已存在：" + tenantCode);
        }
    }

    private void requirePlatformAdministrator(PermissionActorContext actorContext) {
        String role = actorContext == null ? null : normalizeCode(actorContext.actorRole());
        if (!PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(role)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "只有平台超级管理员可以开通和管理租户");
        }
    }

    private Long nextTenantId() {
        return requirePositive(tenantMapper.nextTenantId(), "tenantId sequence");
    }

    private Long nextApplicationId() {
        return requirePositive(applicationMapper.nextApplicationId(), "applicationId sequence");
    }

    private void ensureNotClosed(PermissionTenant tenant, String message) {
        if (STATUS_CLOSED.equals(normalizeCode(tenant.getStatus()))) {
            throw stateConflict(message);
        }
    }

    private PlatformBusinessException stateConflict(String message) {
        return new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, message);
    }

    private Page<PermissionTenant> page(Long current, Long size) {
        long safeCurrent = current == null || current <= 0 ? DEFAULT_CURRENT : current;
        long safeSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return new Page<>(safeCurrent, safeSize);
    }

    private String stableCode(String value, String fieldName) {
        String code = normalizeCode(requiredText(value, fieldName));
        if (!STABLE_CODE.matcher(code).matches()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    fieldName + " 只能包含大写字母、数字、下划线或短横线，并且必须以字母开头");
        }
        return code;
    }

    private String allowedCode(String value, String defaultValue, Set<String> allowed, String fieldName) {
        String code = normalizeCode(defaultText(value, defaultValue));
        if (code == null || !allowed.contains(code)) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    fieldName + " 可选值：" + allowed);
        }
        return code;
    }

    private String requiredText(String value, String fieldName) {
        String text = trimToNull(value);
        if (text == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, fieldName + " 不能为空");
        }
        return text;
    }

    private String defaultText(String value, String defaultValue) {
        String text = trimToNull(value);
        return text == null ? defaultValue : text;
    }

    private String normalizeCode(String value) {
        String text = trimToNull(value);
        return text == null ? null : text.toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Long requirePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, fieldName + " 必须为正数");
        }
        return value;
    }

    private PermissionTenant copyOf(PermissionTenant source) {
        PermissionTenant copy = new PermissionTenant();
        copy.setTenantId(source.getTenantId());
        copy.setTenantCode(source.getTenantCode());
        copy.setTenantName(source.getTenantName());
        copy.setTenantType(source.getTenantType());
        copy.setPlanCode(source.getPlanCode());
        copy.setStatus(source.getStatus());
        copy.setDefaultApplicationCode(source.getDefaultApplicationCode());
        copy.setOwnerActorId(source.getOwnerActorId());
        copy.setOpenedBy(source.getOpenedBy());
        copy.setOpenedAt(source.getOpenedAt());
        copy.setDescription(source.getDescription());
        copy.setCreateTime(source.getCreateTime());
        copy.setUpdateTime(source.getUpdateTime());
        return copy;
    }

    private <T> void likeIfPresent(LambdaQueryWrapper<T> wrapper,
                                   com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> column,
                                   String value) {
        String text = trimToNull(value);
        if (text != null) {
            wrapper.like(column, text);
        }
    }

    private <T> void eqCodeIfPresent(LambdaQueryWrapper<T> wrapper,
                                     com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> column,
                                     String value,
                                     Set<String> allowed,
                                     String fieldName) {
        if (trimToNull(value) != null) {
            wrapper.eq(column, allowedCode(value, null, allowed, fieldName));
        }
    }
}

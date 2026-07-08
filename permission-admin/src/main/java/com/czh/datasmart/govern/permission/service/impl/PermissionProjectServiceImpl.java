/**
 * @Author : Cui
 * @Date: 2026/07/08 23:30
 * @Description DataSmart Govern Backend - PermissionProjectServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectCreateRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectView;
import com.czh.datasmart.govern.permission.entity.PermissionProject;
import com.czh.datasmart.govern.permission.entity.PermissionProjectMembership;
import com.czh.datasmart.govern.permission.event.PermissionProjectMembershipChangedEventPublisher;
import com.czh.datasmart.govern.permission.mapper.PermissionProjectMapper;
import com.czh.datasmart.govern.permission.mapper.PermissionProjectMembershipMapper;
import com.czh.datasmart.govern.permission.service.PermissionProjectService;
import com.czh.datasmart.govern.permission.service.support.PermissionProjectAuditSupport;
import com.czh.datasmart.govern.permission.service.support.PermissionProjectMembershipAuditSupport;
import com.czh.datasmart.govern.permission.support.PermissionRoleCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 项目主数据服务实现。
 *
 * <p>本实现的业务定位非常明确：让项目成为用户创建数据源、创建同步任务、进入 Agent 会话时的唯一用户可见归属层级。
 * 早期 workspace 仍然存在于数据库和少量 Agent 兼容语义中，但本服务不创建 workspace、不返回 workspace、不要求用户填写 workspace。</p>
 *
 * <p>权限设计：
 * 1. 平台管理员可以跨租户查询和创建项目；
 * 2. 租户管理员可以管理本租户项目；
 * 3. 项目负责人可以在本租户自助创建新项目，并自动成为新项目 OWNER；
 * 4. 运营、审计、普通用户默认只读，且只看自己有成员关系或租户范围允许看到的项目；
 * 5. 项目创建会写审计，并自动写入 OWNER 项目成员授权，保证创建后立刻可切换。</p>
 */
@Service
@RequiredArgsConstructor
public class PermissionProjectServiceImpl implements PermissionProjectService {

    private static final long PLATFORM_TENANT_ID = 0L;
    private static final long DEFAULT_CURRENT = 1L;
    private static final long DEFAULT_PAGE_SIZE = 20L;
    private static final long MAX_PAGE_SIZE = 200L;
    private static final String DEFAULT_PROJECT_TYPE = "DATA_GOVERNANCE";
    private static final String DEFAULT_STATUS = "ACTIVE";
    private static final String ARCHIVED_STATUS = "ARCHIVED";
    private static final String OWNER_PROJECT_ROLE = "OWNER";
    private static final String OWNER_GRANT_SOURCE = "PROJECT_CREATION";
    private static final String EVENT_PROJECT_OWNER_GRANTED = "PROJECT_OWNER_GRANTED_BY_PROJECT_CREATION";
    private static final Pattern PROJECT_CODE_PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,63}$");

    /**
     * 可查看项目的角色。
     *
     * <p>普通用户也需要查询“自己可切换的项目”，否则登录后无法进入业务工作台。
     * 真正的数据范围在 {@link #applyReadableScope(LambdaQueryWrapper, PermissionProjectQueryCriteria, PermissionActorContext, String)} 中继续收口。</p>
     */
    private static final Set<String> VIEW_ROLES = Set.of(
            PermissionRoleCode.ORDINARY_USER.name(),
            PermissionRoleCode.PROJECT_OWNER.name(),
            PermissionRoleCode.OPERATOR.name(),
            PermissionRoleCode.AUDITOR.name(),
            PermissionRoleCode.TENANT_ADMINISTRATOR.name(),
            PermissionRoleCode.PLATFORM_ADMINISTRATOR.name()
    );

    /**
     * 可创建项目的角色。
     *
     * <p>这里允许 PROJECT_OWNER 自助创建项目，是为了支撑当前产品“用户先创建项目，再在项目中创建数据源和同步任务”的交互。
     * 运营和审计保持只读，避免排障角色无意间扩展资源边界。</p>
     */
    private static final Set<String> CREATE_ROLES = Set.of(
            PermissionRoleCode.PROJECT_OWNER.name(),
            PermissionRoleCode.TENANT_ADMINISTRATOR.name(),
            PermissionRoleCode.PLATFORM_ADMINISTRATOR.name()
    );

    private final PermissionProjectMapper projectMapper;
    private final PermissionProjectMembershipMapper membershipMapper;
    private final PermissionProjectAuditSupport projectAuditSupport;
    private final PermissionProjectMembershipAuditSupport membershipAuditSupport;
    private final PermissionProjectMembershipChangedEventPublisher membershipChangedEventPublisher;

    /**
     * 分页查询项目。
     *
     * <p>该方法是前端项目切换器的主要后端入口。返回值使用 {@link PermissionProjectView}，
     * 不直接暴露 {@link PermissionProject}，目的就是把 defaultWorkspaceId 这类历史兼容字段挡在服务端内部。</p>
     */
    @Override
    public PlatformPageResponse<PermissionProjectView> pageProjects(PermissionProjectQueryCriteria criteria,
                                                                    PermissionActorContext actorContext) {
        String actorRole = requireRole(actorContext);
        requireAnyRole(actorRole, VIEW_ROLES, "当前角色无权查看项目");
        PermissionProjectQueryCriteria safeCriteria = criteria == null
                ? new PermissionProjectQueryCriteria(null, null, null, null, null, null, null, null, null)
                : criteria;

        LambdaQueryWrapper<PermissionProject> wrapper = new LambdaQueryWrapper<PermissionProject>()
                .orderByDesc(PermissionProject::getUpdateTime)
                .orderByDesc(PermissionProject::getProjectId);
        applyReadableScope(wrapper, safeCriteria, actorContext, actorRole);
        eqIfPresent(wrapper, PermissionProject::getApplicationId, safeCriteria.applicationId());
        eqIfPresent(wrapper, PermissionProject::getProjectId, safeCriteria.projectId());
        eqIfPresent(wrapper, PermissionProject::getProjectCode, normalizeCode(safeCriteria.projectCode()));
        if (safeCriteria.projectName() != null && !safeCriteria.projectName().isBlank()) {
            wrapper.like(PermissionProject::getProjectName, safeCriteria.projectName().trim());
        }
        String status = normalizeCode(safeCriteria.status());
        if (status == null) {
            wrapper.ne(PermissionProject::getStatus, ARCHIVED_STATUS);
        } else {
            wrapper.eq(PermissionProject::getStatus, status);
        }

        Page<PermissionProject> page = projectMapper.selectPage(page(safeCriteria.current(), safeCriteria.size()), wrapper);
        List<PermissionProjectView> views = page.getRecords().stream()
                .map(PermissionProjectView::from)
                .toList();
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), views);
    }

    /**
     * 查询项目详情。
     *
     * <p>详情接口不能因为调用方知道 projectId 就绕过成员关系校验。
     * 因此读取项目后仍调用 {@link #validateReadableProject(PermissionProject, PermissionActorContext)} 做二次确认。</p>
     */
    @Override
    public PermissionProjectView getProject(Long projectId, PermissionActorContext actorContext) {
        PermissionProject project = findProjectByIdOrThrow(projectId);
        validateReadableProject(project, actorContext);
        return PermissionProjectView.from(project);
    }

    /**
     * 创建项目并授予负责人 OWNER。
     *
     * <p>项目创建的完整业务闭环：</p>
     * <p>1. 校验当前角色是否允许创建项目；</p>
     * <p>2. 解析目标租户和默认应用，不要求前端手填内部 ID；</p>
     * <p>3. 通过 PostgreSQL 序列生成项目 ID，避免并发创建冲突；</p>
     * <p>4. 写入 permission_project，defaultWorkspaceId 固定为空；</p>
     * <p>5. 写入负责人 OWNER 成员关系，workspaceId 也固定为空；</p>
     * <p>6. 写项目审计和成员授权审计，并发布成员变更事件。</p>
     */
    @Override
    @Transactional
    public PermissionProjectMutationResult createProject(PermissionProjectCreateRequest request,
                                                         PermissionActorContext actorContext) {
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "项目创建请求不能为空");
        }
        String actorRole = requireRole(actorContext);
        requireAnyRole(actorRole, CREATE_ROLES, "当前角色无权创建项目");

        Long tenantId = resolveTargetTenantId(request.tenantId(), actorContext, actorRole);
        Long ownerActorId = resolveOwnerActorId(request.ownerActorId(), actorContext, actorRole);
        Long applicationId = resolveApplicationId(tenantId, request.applicationId());
        Long projectId = nextProjectId();
        String projectCode = resolveProjectCode(request.projectCode(), projectId);

        ensureProjectCodeNotExists(tenantId, projectCode);

        LocalDateTime now = LocalDateTime.now();
        PermissionProject project = new PermissionProject();
        project.setProjectId(projectId);
        project.setTenantId(tenantId);
        project.setApplicationId(applicationId);
        project.setProjectCode(projectCode);
        project.setProjectName(requiredText(request.projectName(), "projectName"));
        project.setProjectType(defaultText(normalizeCode(request.projectType()), DEFAULT_PROJECT_TYPE));
        project.setStatus(DEFAULT_STATUS);
        project.setDefaultWorkspaceId(null);
        project.setOwnerActorId(ownerActorId);
        project.setDescription(trimToNull(request.description()));
        project.setCreateTime(now);
        project.setUpdateTime(now);
        projectMapper.insert(project);

        PermissionProjectMembership ownerMembership = grantOwnerMembership(project, actorContext,
                defaultText(request.reason(), "创建项目并自动授予负责人 OWNER"));
        projectAuditSupport.saveMutationAudit(actorContext, "CREATE_PROJECT", "SUCCESS",
                defaultText(request.reason(), "创建项目：" + project.getProjectName()), null, project);

        return new PermissionProjectMutationResult(
                project.getProjectId(),
                project.getTenantId(),
                project.getProjectCode(),
                project.getProjectName(),
                project.getStatus(),
                ownerMembership.getActorId(),
                "项目创建成功，负责人已获得 OWNER 授权"
        );
    }

    /**
     * 按角色追加项目可见范围。
     */
    private void applyReadableScope(LambdaQueryWrapper<PermissionProject> wrapper,
                                    PermissionProjectQueryCriteria criteria,
                                    PermissionActorContext actorContext,
                                    String actorRole) {
        if (PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(actorRole)) {
            eqIfPresent(wrapper, PermissionProject::getTenantId, criteria.tenantId());
            applyMembershipScopeIfRequested(wrapper, criteria, actorContext);
            return;
        }

        Long actorTenantId = requirePositive(normalizeTenantId(actorContext.tenantId()), "actorTenantId");
        if (criteria.tenantId() != null && !actorTenantId.equals(criteria.tenantId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "当前身份只能查看自身租户项目，actorTenantId=" + actorTenantId + ", requestedTenantId=" + criteria.tenantId());
        }
        wrapper.eq(PermissionProject::getTenantId, actorTenantId);

        if (Boolean.TRUE.equals(criteria.onlyMine()) || isMembershipScopedRole(actorRole)) {
            applyMembershipScope(wrapper, criteria, actorTenantId, actorContext.actorId());
        }
    }

    /**
     * 平台管理员/租户管理员显式选择“我的项目”时，也可以复用成员关系范围。
     */
    private void applyMembershipScopeIfRequested(LambdaQueryWrapper<PermissionProject> wrapper,
                                                 PermissionProjectQueryCriteria criteria,
                                                 PermissionActorContext actorContext) {
        if (!Boolean.TRUE.equals(criteria.onlyMine())) {
            return;
        }
        Long tenantId = criteria.tenantId() == null ? normalizeTenantId(actorContext.tenantId()) : criteria.tenantId();
        applyMembershipScope(wrapper, criteria, tenantId, actorContext.actorId());
    }

    /**
     * 根据 permission_project_membership 限定项目集合。
     */
    private void applyMembershipScope(LambdaQueryWrapper<PermissionProject> wrapper,
                                      PermissionProjectQueryCriteria criteria,
                                      Long tenantId,
                                      Long actorId) {
        Set<Long> projectIds = actorProjectIds(tenantId, actorId);
        if (projectIds.isEmpty()) {
            wrapper.eq(PermissionProject::getProjectId, -1L);
            return;
        }
        if (criteria.projectId() != null && !projectIds.contains(criteria.projectId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "当前身份不能查看未授权项目，projectId=" + criteria.projectId());
        }
        if (criteria.projectId() == null) {
            wrapper.in(PermissionProject::getProjectId, projectIds);
        }
    }

    private void validateReadableProject(PermissionProject project, PermissionActorContext actorContext) {
        String actorRole = requireRole(actorContext);
        requireAnyRole(actorRole, VIEW_ROLES, "当前角色无权查看项目");
        if (PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(actorRole)) {
            return;
        }
        Long actorTenantId = normalizeTenantId(actorContext.tenantId());
        if (!actorTenantId.equals(normalizeTenantId(project.getTenantId()))) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED, "当前身份不能查看其他租户项目");
        }
        if (isMembershipScopedRole(actorRole)
                && !actorProjectIds(actorTenantId, actorContext.actorId()).contains(project.getProjectId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, "当前身份不能查看未授权项目");
        }
    }

    private PermissionProjectMembership grantOwnerMembership(PermissionProject project,
                                                            PermissionActorContext actorContext,
                                                            String reason) {
        PermissionProjectMembership existing = membershipMapper.selectOne(new LambdaQueryWrapper<PermissionProjectMembership>()
                .eq(PermissionProjectMembership::getTenantId, project.getTenantId())
                .eq(PermissionProjectMembership::getActorId, project.getOwnerActorId())
                .eq(PermissionProjectMembership::getProjectId, project.getProjectId()));
        PermissionProjectMembership before = copyMembership(existing);
        PermissionProjectMembership membership = existing == null ? new PermissionProjectMembership() : existing;
        membership.setTenantId(project.getTenantId());
        membership.setActorId(project.getOwnerActorId());
        membership.setProjectId(project.getProjectId());
        membership.setWorkspaceId(null);
        membership.setProjectRole(OWNER_PROJECT_ROLE);
        membership.setGrantSource(OWNER_GRANT_SOURCE);
        membership.setEnabled(true);
        membership.setUpdateTime(LocalDateTime.now());
        if (existing == null) {
            membership.setCreateTime(LocalDateTime.now());
            membershipMapper.insert(membership);
        } else {
            membershipMapper.updateById(membership);
        }
        membershipAuditSupport.saveMutationAudit(actorContext, "GRANT_PROJECT_OWNER_BY_PROJECT_CREATE",
                "SUCCESS", reason, before, membership);
        membershipChangedEventPublisher.publishProjectMembershipChanged(EVENT_PROJECT_OWNER_GRANTED,
                membership, actorContext, reason);
        return membership;
    }

    private Set<Long> actorProjectIds(Long tenantId, Long actorId) {
        if (actorId == null) {
            return Set.of();
        }
        return membershipMapper.selectList(new LambdaQueryWrapper<PermissionProjectMembership>()
                        .eq(PermissionProjectMembership::getTenantId, normalizeTenantId(tenantId))
                        .eq(PermissionProjectMembership::getActorId, actorId)
                        .eq(PermissionProjectMembership::getEnabled, true)
                        .isNotNull(PermissionProjectMembership::getProjectId)
                        .orderByAsc(PermissionProjectMembership::getProjectId))
                .stream()
                .map(PermissionProjectMembership::getProjectId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean isMembershipScopedRole(String actorRole) {
        return PermissionRoleCode.ORDINARY_USER.name().equals(actorRole)
                || PermissionRoleCode.PROJECT_OWNER.name().equals(actorRole);
    }

    private Long resolveTargetTenantId(Long requestedTenantId, PermissionActorContext actorContext, String actorRole) {
        Long targetTenantId = requestedTenantId == null ? normalizeTenantId(actorContext.tenantId()) : requestedTenantId;
        requirePositive(targetTenantId, "tenantId");
        if (PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(actorRole)) {
            return targetTenantId;
        }
        Long actorTenantId = requirePositive(normalizeTenantId(actorContext.tenantId()), "actorTenantId");
        if (!actorTenantId.equals(targetTenantId)) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED, "当前身份不能在其他租户创建项目");
        }
        return actorTenantId;
    }

    private Long resolveOwnerActorId(Long requestedOwnerActorId,
                                     PermissionActorContext actorContext,
                                     String actorRole) {
        Long ownerActorId = requestedOwnerActorId == null ? actorContext.actorId() : requestedOwnerActorId;
        requirePositive(ownerActorId, "ownerActorId");
        if (PermissionRoleCode.PROJECT_OWNER.name().equals(actorRole)
                && !ownerActorId.equals(actorContext.actorId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "项目负责人自助创建项目时只能把自己设为负责人");
        }
        return ownerActorId;
    }

    private Long resolveApplicationId(Long tenantId, Long requestedApplicationId) {
        if (requestedApplicationId != null) {
            requirePositive(requestedApplicationId, "applicationId");
            if (projectMapper.countActiveApplication(tenantId, requestedApplicationId) <= 0) {
                throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                        "指定应用不存在、已禁用或不属于当前租户，applicationId=" + requestedApplicationId);
            }
            return requestedApplicationId;
        }
        Long defaultApplicationId = projectMapper.selectDefaultApplicationId(tenantId);
        if (defaultApplicationId == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "当前租户没有可用应用，无法创建项目，tenantId=" + tenantId);
        }
        return defaultApplicationId;
    }

    private Long nextProjectId() {
        Long projectId = projectMapper.nextProjectId();
        if (projectId == null || projectId <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.INTERNAL_ERROR, "项目 ID 序列返回无效值");
        }
        return projectId;
    }

    private String resolveProjectCode(String requestedProjectCode, Long projectId) {
        String code = normalizeCode(requestedProjectCode);
        if (code == null) {
            code = "PROJECT_" + projectId;
        }
        if (!PROJECT_CODE_PATTERN.matcher(code).matches()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "projectCode 只能包含大写字母、数字、下划线或短横线，且长度不能超过 64：" + requestedProjectCode);
        }
        return code;
    }

    private void ensureProjectCodeNotExists(Long tenantId, String projectCode) {
        Long count = projectMapper.selectCount(new LambdaQueryWrapper<PermissionProject>()
                .eq(PermissionProject::getTenantId, tenantId)
                .eq(PermissionProject::getProjectCode, projectCode));
        if (count != null && count > 0) {
            throw new PlatformBusinessException(PlatformErrorCode.DUPLICATE_OPERATION,
                    "同一租户下项目编码已存在，projectCode=" + projectCode);
        }
    }

    private PermissionProject findProjectByIdOrThrow(Long projectId) {
        Long id = requirePositive(projectId, "projectId");
        PermissionProject project = projectMapper.selectById(id);
        if (project == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "项目不存在：" + id);
        }
        return project;
    }

    private PermissionProjectMembership copyMembership(PermissionProjectMembership source) {
        if (source == null) {
            return null;
        }
        PermissionProjectMembership copy = new PermissionProjectMembership();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setActorId(source.getActorId());
        copy.setProjectId(source.getProjectId());
        copy.setWorkspaceId(source.getWorkspaceId());
        copy.setProjectRole(source.getProjectRole());
        copy.setGrantSource(source.getGrantSource());
        copy.setEnabled(source.getEnabled());
        copy.setCreateTime(source.getCreateTime());
        copy.setUpdateTime(source.getUpdateTime());
        return copy;
    }

    private String requireRole(PermissionActorContext actorContext) {
        if (actorContext == null || actorContext.actorRole() == null || actorContext.actorRole().isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, "缺少可信操作者角色，不能访问项目控制面");
        }
        return normalizeCode(actorContext.actorRole());
    }

    private void requireAnyRole(String actorRole, Set<String> allowedRoles, String message) {
        if (!allowedRoles.contains(actorRole)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, message + "，actorRole=" + actorRole);
        }
    }

    private Long requirePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, fieldName + " 必须为正数");
        }
        return value;
    }

    private Long normalizeTenantId(Long tenantId) {
        return tenantId == null ? PLATFORM_TENANT_ID : tenantId;
    }

    private String normalizeCode(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
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

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private <T> Page<T> page(Long current, Long size) {
        long safeCurrent = current == null || current <= 0 ? DEFAULT_CURRENT : current;
        long safeSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return new Page<>(safeCurrent, safeSize);
    }

    private <T> void eqIfPresent(LambdaQueryWrapper<T> wrapper,
                                 SFunction<T, ?> column,
                                 Long value) {
        if (value != null) {
            wrapper.eq(column, value);
        }
    }

    private <T> void eqIfPresent(LambdaQueryWrapper<T> wrapper,
                                 SFunction<T, ?> column,
                                 String value) {
        if (value != null && !value.isBlank()) {
            wrapper.eq(column, value);
        }
    }
}

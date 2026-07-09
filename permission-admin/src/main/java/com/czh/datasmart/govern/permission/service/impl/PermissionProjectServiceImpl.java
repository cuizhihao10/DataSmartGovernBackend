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
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectDeletionBlocker;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectDeletionCheckResponse;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectStatusChangeRequest;
import com.czh.datasmart.govern.permission.controller.dto.PermissionProjectUpdateRequest;
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
import java.util.ArrayList;
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
    private static final String DISABLED_STATUS = "DISABLED";
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
            PermissionRoleCode.TENANT_ADMINISTRATOR.name(),
            PermissionRoleCode.PLATFORM_ADMINISTRATOR.name()
    );

    /**
     * 可管理项目生命周期的角色。
     *
     * <p>项目生命周期会影响下游数据源、同步任务、质量规则和 Agent 会话的可用性，所以不能开放给普通用户、
     * 运营只读角色或审计角色。PROJECT_OWNER 只能管理自己拥有 OWNER 成员关系的项目；租户管理员只能管理本租户项目；
     * 平台管理员用于交付和运维兜底，可跨租户管理。</p>
     */
    private static final Set<String> MANAGE_ROLES = Set.of(
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
     * 更新项目基础资料。
     *
     * <p>更新前会先校验项目是否仍处于可编辑状态，并判断当前 actor 是否拥有管理权限。
     * 这里刻意不修改成员授权：ownerActorId 是项目主数据上的负责人快照，真正的 OWNER/MAINTAINER/VIEWER
     * 成员关系仍然由 permission_project_membership 管理，避免一个普通资料编辑动作暗中扩大权限。</p>
     */
    @Override
    @Transactional
    public PermissionProjectMutationResult updateProject(Long projectId,
                                                         PermissionProjectUpdateRequest request,
                                                         PermissionActorContext actorContext) {
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "项目更新请求不能为空");
        }
        PermissionProject project = findProjectByIdOrThrow(projectId);
        validateManageProject(project, actorContext);
        if (ARCHIVED_STATUS.equals(normalizeCode(project.getStatus()))) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "已归档项目不能继续编辑，请重新创建项目或走专门的归档恢复流程");
        }

        PermissionProject before = copyProject(project);
        String nextProjectCode = normalizeCode(request.projectCode());
        if (nextProjectCode != null && !nextProjectCode.equals(project.getProjectCode())) {
            if (!PROJECT_CODE_PATTERN.matcher(nextProjectCode).matches()) {
                throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                        "projectCode 只能包含大写字母、数字、下划线或短横线，且长度不能超过 64：" + request.projectCode());
            }
            ensureProjectCodeNotExists(project.getTenantId(), nextProjectCode);
            project.setProjectCode(nextProjectCode);
        }
        String nextProjectName = trimToNull(request.projectName());
        if (nextProjectName != null) {
            project.setProjectName(nextProjectName);
        }
        String nextProjectType = normalizeCode(request.projectType());
        if (nextProjectType != null) {
            project.setProjectType(nextProjectType);
        }
        if (request.ownerActorId() != null) {
            project.setOwnerActorId(requirePositive(request.ownerActorId(), "ownerActorId"));
        }
        if (request.description() != null) {
            project.setDescription(trimToNull(request.description()));
        }
        project.setUpdateTime(LocalDateTime.now());
        projectMapper.updateById(project);
        projectAuditSupport.saveMutationAudit(actorContext, "UPDATE_PROJECT", "SUCCESS",
                defaultText(request.reason(), "更新项目基础资料：" + project.getProjectName()), before, project);
        return mutationResult(project, "项目更新成功");
    }

    /**
     * 将 DISABLED 项目重新启用为 ACTIVE。
     *
     * <p>已归档项目不允许通过普通启用入口恢复，是为了避免“删除/归档后又无审计地恢复”的状态歧义。
     * 如果后续产品确实需要恢复归档项目，应新增单独 restore 接口并要求平台管理员或租户管理员审批。</p>
     */
    @Override
    @Transactional
    public PermissionProjectMutationResult activateProject(Long projectId,
                                                           PermissionProjectStatusChangeRequest request,
                                                           PermissionActorContext actorContext) {
        PermissionProject project = findProjectByIdOrThrow(projectId);
        validateManageProject(project, actorContext);
        if (ARCHIVED_STATUS.equals(normalizeCode(project.getStatus()))) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "已归档项目不能通过启用入口恢复");
        }
        return changeProjectStatus(project, DEFAULT_STATUS, request, actorContext,
                "ACTIVATE_PROJECT", "项目已启用");
    }

    /**
     * 将项目置为 DISABLED。
     *
     * <p>禁用是轻量冻结：它用于阻止后续新资源继续落入项目，但不直接停止 data-sync 正在运行的任务，
     * 因为执行停止、取消、重试应由 data-sync 根据任务状态机和执行租约来完成。</p>
     */
    @Override
    @Transactional
    public PermissionProjectMutationResult disableProject(Long projectId,
                                                          PermissionProjectStatusChangeRequest request,
                                                          PermissionActorContext actorContext) {
        PermissionProject project = findProjectByIdOrThrow(projectId);
        validateManageProject(project, actorContext);
        if (ARCHIVED_STATUS.equals(normalizeCode(project.getStatus()))) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "已归档项目不能再禁用");
        }
        return changeProjectStatus(project, DISABLED_STATUS, request, actorContext,
                "DISABLE_PROJECT", "项目已禁用");
    }

    /**
     * 只读检查项目是否可以归档式删除。
     *
     * <p>检查数据来自 datasource-management 与 data-sync schema 的 count 聚合。permission-admin 不返回明细，
     * 因为具体资源明细应继续由对应业务微服务在自己的权限规则下提供。</p>
     */
    @Override
    public PermissionProjectDeletionCheckResponse checkProjectDeletion(Long projectId,
                                                                       PermissionActorContext actorContext) {
        PermissionProject project = findProjectByIdOrThrow(projectId);
        validateManageProject(project, actorContext);
        return evaluateDeletionReadiness(project);
    }

    /**
     * 归档项目。
     *
     * <p>归档前必须通过占用检查。项目仍有活动数据源、启用模板或非归档任务时，归档会让下游资源失去可见归属，
     * 因此服务端直接阻断，并把阻断项返回给前端引导用户先下线资源。</p>
     */
    @Override
    @Transactional
    public PermissionProjectMutationResult archiveProject(Long projectId,
                                                          PermissionProjectStatusChangeRequest request,
                                                          PermissionActorContext actorContext) {
        PermissionProject project = findProjectByIdOrThrow(projectId);
        validateManageProject(project, actorContext);
        ensureDeletionReady(project);
        return changeProjectStatus(project, ARCHIVED_STATUS, request, actorContext,
                "ARCHIVE_PROJECT", "项目已归档");
    }

    /**
     * 删除项目。
     *
     * <p>当前删除采用“软删除/归档式删除”：把 status 写为 ARCHIVED，而不是删除 permission_project 行。
     * 这样可以保留历史审计、成员授权、数据源和同步任务的 projectId 解释能力，符合商业系统的可追溯要求。</p>
     */
    @Override
    @Transactional
    public PermissionProjectMutationResult deleteProject(Long projectId,
                                                         PermissionProjectStatusChangeRequest request,
                                                         PermissionActorContext actorContext) {
        PermissionProject project = findProjectByIdOrThrow(projectId);
        validateManageProject(project, actorContext);
        ensureDeletionReady(project);
        return changeProjectStatus(project, ARCHIVED_STATUS, request, actorContext,
                "DELETE_PROJECT", "项目已删除并归档");
    }

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

    /**
     * 校验当前操作者是否可以管理项目。
     *
     * <p>读权限和管理权限不能混用。普通用户、运营、审计可能需要看到项目用于切换或排障，
     * 但不应能修改项目、禁用项目或删除项目。PROJECT_OWNER 也不能只凭全局角色管理所有项目，
     * 必须在 permission_project_membership 中拥有该项目的 OWNER 成员关系。</p>
     */
    private void validateManageProject(PermissionProject project, PermissionActorContext actorContext) {
        String actorRole = requireRole(actorContext);
        requireAnyRole(actorRole, MANAGE_ROLES, "当前角色无权管理项目");
        if (PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(actorRole)) {
            return;
        }
        Long actorTenantId = requirePositive(normalizeTenantId(actorContext.tenantId()), "actorTenantId");
        if (!actorTenantId.equals(normalizeTenantId(project.getTenantId()))) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "当前身份不能管理其他租户项目");
        }
        if (PermissionRoleCode.TENANT_ADMINISTRATOR.name().equals(actorRole)) {
            return;
        }
        PermissionProjectMembership membership = membershipForActor(project, actorContext.actorId());
        if (membership == null || !OWNER_PROJECT_ROLE.equals(normalizeCode(membership.getProjectRole()))) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "项目负责人只能管理自己拥有 OWNER 授权的项目，projectId=" + project.getProjectId());
        }
    }

    /**
     * 执行项目状态变更并写入审计。
     *
     * <p>所有生命周期动作集中到该方法，是为了确保 updateTime、审计 before/after、返回结果保持一致。
     * 如果目标状态和当前状态相同，仍返回成功但不重复写数据库，避免前端重复点击造成无意义更新。</p>
     */
    private PermissionProjectMutationResult changeProjectStatus(PermissionProject project,
                                                                String targetStatus,
                                                                PermissionProjectStatusChangeRequest request,
                                                                PermissionActorContext actorContext,
                                                                String action,
                                                                String successMessage) {
        String normalizedTargetStatus = normalizeCode(targetStatus);
        if (normalizedTargetStatus == null
                || (!DEFAULT_STATUS.equals(normalizedTargetStatus)
                && !DISABLED_STATUS.equals(normalizedTargetStatus)
                && !ARCHIVED_STATUS.equals(normalizedTargetStatus))) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "项目目标状态不合法：" + targetStatus);
        }
        if (normalizedTargetStatus.equals(normalizeCode(project.getStatus()))) {
            return mutationResult(project, successMessage);
        }
        PermissionProject before = copyProject(project);
        project.setStatus(normalizedTargetStatus);
        project.setUpdateTime(LocalDateTime.now());
        projectMapper.updateById(project);
        projectAuditSupport.saveMutationAudit(actorContext, action, "SUCCESS",
                defaultText(request == null ? null : request.reason(), successMessage + "：" + project.getProjectName()),
                before, project);
        return mutationResult(project, successMessage);
    }

    /**
     * 计算项目是否可以归档式删除。
     *
     * <p>占用检查只做低敏聚合，不返回资源明细。这样 permission-admin 仍然只承担“控制面决策”职责，
     * 具体的数据源列表、同步任务列表和执行明细继续由各业务服务在自身权限边界内提供。</p>
     */
    private PermissionProjectDeletionCheckResponse evaluateDeletionReadiness(PermissionProject project) {
        long activeDatasourceCount = projectMapper.countActiveDatasources(project.getTenantId(), project.getProjectId());
        long enabledSyncTemplateCount = projectMapper.countEnabledSyncTemplates(project.getTenantId(), project.getProjectId());
        long activeSyncTaskCount = projectMapper.countActiveSyncTasks(project.getTenantId(), project.getProjectId());
        List<PermissionProjectDeletionBlocker> blockers = new ArrayList<>();
        if (activeDatasourceCount > 0) {
            blockers.add(new PermissionProjectDeletionBlocker("DATASOURCE", activeDatasourceCount,
                    "项目下仍存在未删除数据源，请先删除或迁移数据源"));
        }
        if (enabledSyncTemplateCount > 0) {
            blockers.add(new PermissionProjectDeletionBlocker("DATA_SYNC_TEMPLATE", enabledSyncTemplateCount,
                    "项目下仍存在启用中的同步模板，请先禁用或迁移模板"));
        }
        if (activeSyncTaskCount > 0) {
            blockers.add(new PermissionProjectDeletionBlocker("DATA_SYNC_TASK", activeSyncTaskCount,
                    "项目下仍存在未归档同步任务，请先下线、删除或归档任务"));
        }
        boolean deletable = blockers.isEmpty();
        return new PermissionProjectDeletionCheckResponse(
                project.getProjectId(),
                project.getTenantId(),
                deletable,
                activeDatasourceCount,
                enabledSyncTemplateCount,
                activeSyncTaskCount,
                List.copyOf(blockers),
                deletable ? "项目可以归档式删除" : "项目仍有关联资源，不能删除"
        );
    }

    /**
     * 强制执行删除/归档前置检查。
     */
    private void ensureDeletionReady(PermissionProject project) {
        PermissionProjectDeletionCheckResponse deletionCheck = evaluateDeletionReadiness(project);
        if (!deletionCheck.deletable()) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    deletionCheck.message() + "，阻断项=" + deletionCheck.blockers());
        }
    }

    /**
     * 查询某个 actor 在项目上的成员关系。
     */
    private PermissionProjectMembership membershipForActor(PermissionProject project, Long actorId) {
        if (actorId == null) {
            return null;
        }
        return membershipMapper.selectOne(new LambdaQueryWrapper<PermissionProjectMembership>()
                .eq(PermissionProjectMembership::getTenantId, project.getTenantId())
                .eq(PermissionProjectMembership::getActorId, actorId)
                .eq(PermissionProjectMembership::getProjectId, project.getProjectId())
                .eq(PermissionProjectMembership::getEnabled, true));
    }

    /**
     * 构建项目变更结果。
     */
    private PermissionProjectMutationResult mutationResult(PermissionProject project, String message) {
        return new PermissionProjectMutationResult(
                project.getProjectId(),
                project.getTenantId(),
                project.getProjectCode(),
                project.getProjectName(),
                project.getStatus(),
                project.getOwnerActorId(),
                message
        );
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

    /**
     * 复制项目快照。
     *
     * <p>审计记录需要保存变更前后的两个状态。如果直接把同一个实体引用传给审计组件，
     * 后续字段更新会让 before/after 看起来完全一样，失去复盘价值。</p>
     */
    private PermissionProject copyProject(PermissionProject source) {
        if (source == null) {
            return null;
        }
        PermissionProject copy = new PermissionProject();
        copy.setProjectId(source.getProjectId());
        copy.setTenantId(source.getTenantId());
        copy.setApplicationId(source.getApplicationId());
        copy.setProjectCode(source.getProjectCode());
        copy.setProjectName(source.getProjectName());
        copy.setProjectType(source.getProjectType());
        copy.setStatus(source.getStatus());
        copy.setDefaultWorkspaceId(source.getDefaultWorkspaceId());
        copy.setOwnerActorId(source.getOwnerActorId());
        copy.setDescription(source.getDescription());
        copy.setCreateTime(source.getCreateTime());
        copy.setUpdateTime(source.getUpdateTime());
        return copy;
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

/**
 * @Author : Cui
 * @Date: 2026/05/10 19:35
 * @Description DataSmart Govern Backend - PermissionProjectMembershipServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.permission.controller.dto.PermissionActorContext;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipBatchUpsertRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipCreateRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipMutationResult;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipQueryCriteria;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipStateChangeRequest;
import com.czh.datasmart.govern.permission.controller.dto.ProjectMembershipUpdateRequest;
import com.czh.datasmart.govern.permission.entity.PermissionProjectMembership;
import com.czh.datasmart.govern.permission.event.PermissionProjectMembershipChangedEventPublisher;
import com.czh.datasmart.govern.permission.mapper.PermissionProjectMembershipMapper;
import com.czh.datasmart.govern.permission.service.PermissionProjectMembershipService;
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

import static com.czh.datasmart.govern.permission.service.impl.PermissionProjectMembershipCopySupport.copyOf;

/**
 * 项目成员授权管理服务实现。
 *
 * <p>该实现刻意只处理项目成员授权，不混入菜单、角色、路由策略或 outbox 运维逻辑。
 * 这样可以避免 permission-admin 中的 Service Impl 继续膨胀，也符合用户提出的“解耦、单文件不宜过长”规范。
 *
 * <p>核心业务规则：
 * 1. 平台管理员可以跨租户管理项目成员；
 * 2. 租户管理员只能管理自己租户内的项目成员；
 * 3. 项目负责人只能管理自己拥有 OWNER 授权的项目，且不能给别人授予 OWNER，避免项目内自扩权；
 * 4. 审计员和运营人员可以只读查询，但不能变更成员；
 * 5. 禁用优先于删除，成员离开项目时保留历史审计线索。
 */
@Service
@RequiredArgsConstructor
public class PermissionProjectMembershipServiceImpl implements PermissionProjectMembershipService {

    private static final long PLATFORM_TENANT_ID = 0L;
    private static final long DEFAULT_CURRENT = 1L;
    private static final long DEFAULT_PAGE_SIZE = 20L;
    private static final long MAX_PAGE_SIZE = 200L;
    private static final int MAX_BATCH_SIZE = 200;
    private static final String DEFAULT_PROJECT_ROLE = "MEMBER";
    private static final String DEFAULT_GRANT_SOURCE = "MANUAL";
    private static final String OWNER_PROJECT_ROLE = "OWNER";
    private static final String EVENT_PROJECT_MEMBERSHIP_UPSERTED = "PROJECT_MEMBERSHIP_UPSERTED";
    private static final String EVENT_PROJECT_MEMBERSHIP_UPDATED = "PROJECT_MEMBERSHIP_UPDATED";
    private static final String EVENT_PROJECT_MEMBERSHIP_ENABLED = "PROJECT_MEMBERSHIP_ENABLED";
    private static final String EVENT_PROJECT_MEMBERSHIP_DISABLED = "PROJECT_MEMBERSHIP_DISABLED";

    /**
     * 可查看项目成员授权的角色。
     *
     * <p>运营人员需要只读查看成员关系来排查“为什么该用户能/不能看到某项目”；
     * 审计员需要只读查看成员关系来复核授权来源。
     */
    private static final Set<String> VIEW_ROLES = Set.of(
            PermissionRoleCode.PLATFORM_ADMINISTRATOR.name(),
            PermissionRoleCode.TENANT_ADMINISTRATOR.name(),
            PermissionRoleCode.PROJECT_OWNER.name(),
            PermissionRoleCode.OPERATOR.name(),
            PermissionRoleCode.AUDITOR.name()
    );

    /**
     * 可变更项目成员授权的角色。
     *
     * <p>PROJECT_OWNER 只能管理自己拥有 OWNER 授权的项目，具体项目边界在 service 方法里进一步校验。
     */
    private static final Set<String> MUTATION_ROLES = Set.of(
            PermissionRoleCode.PLATFORM_ADMINISTRATOR.name(),
            PermissionRoleCode.TENANT_ADMINISTRATOR.name(),
            PermissionRoleCode.PROJECT_OWNER.name()
    );

    private final PermissionProjectMembershipMapper membershipMapper;
    private final PermissionProjectMembershipAuditSupport auditSupport;
    private final PermissionProjectMembershipChangedEventPublisher membershipChangedEventPublisher;

    /**
     * 分页查询项目成员授权。
     *
     * <p>查询逻辑不仅按请求条件过滤，还会根据操作者角色追加安全边界：
     * 平台管理员可跨租户；租户管理员、运营、审计只看本租户；项目负责人只看自己拥有 OWNER 的项目。
     */
    @Override
    public PlatformPageResponse<PermissionProjectMembership> pageProjectMemberships(ProjectMembershipQueryCriteria criteria,
                                                                                   PermissionActorContext actorContext) {
        String actorRole = requireRole(actorContext);
        requireAnyRole(actorRole, VIEW_ROLES, "当前角色无权查看项目成员授权");
        ProjectMembershipQueryCriteria safeCriteria = criteria == null
                ? new ProjectMembershipQueryCriteria(null, null, null, null, null, null, null, null, null)
                : criteria;

        LambdaQueryWrapper<PermissionProjectMembership> wrapper = new LambdaQueryWrapper<PermissionProjectMembership>()
                .orderByDesc(PermissionProjectMembership::getUpdateTime)
                .orderByDesc(PermissionProjectMembership::getId);

        applyReadableScope(wrapper, safeCriteria, actorContext, actorRole);
        eqIfPresent(wrapper, PermissionProjectMembership::getActorId, safeCriteria.actorId());
        eqIfPresent(wrapper, PermissionProjectMembership::getWorkspaceId, safeCriteria.workspaceId());
        eqIfPresent(wrapper, PermissionProjectMembership::getProjectRole, normalizeCode(safeCriteria.projectRole()));
        eqIfPresent(wrapper, PermissionProjectMembership::getGrantSource, normalizeCode(safeCriteria.grantSource()));
        if (safeCriteria.enabled() != null) {
            wrapper.eq(PermissionProjectMembership::getEnabled, safeCriteria.enabled());
        }

        Page<PermissionProjectMembership> page = membershipMapper.selectPage(page(safeCriteria.current(), safeCriteria.size()), wrapper);
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    /**
     * 查询单条项目成员授权。
     */
    @Override
    public PermissionProjectMembership getProjectMembership(Long membershipId, PermissionActorContext actorContext) {
        PermissionProjectMembership membership = findByIdOrThrow(membershipId);
        validateReadableMembership(membership, actorContext);
        return membership;
    }

    /**
     * 新增或幂等更新项目成员授权。
     *
     * <p>为了支持管理后台重试和组织同步补偿，这里不会在唯一键冲突时直接失败，
     * 而是按 tenantId + actorId + projectId 找到已有记录并更新。
     */
    @Override
    @Transactional
    public ProjectMembershipMutationResult grantOrUpdateProjectMembership(ProjectMembershipCreateRequest request,
                                                                          PermissionActorContext actorContext) {
        return grantOrUpdateInternal(request, actorContext, defaultText(request.reason(), "新增或更新项目成员授权"));
    }

    /**
     * 批量新增或幂等更新项目成员授权。
     *
     * <p>批量接口使用一个事务，保证同一批授权要么整体成功，要么整体失败。
     * 如果后续需要支持“部分成功 + 错误报告下载”，可以扩展为异步导入任务。
     */
    @Override
    @Transactional
    public List<ProjectMembershipMutationResult> batchGrantOrUpdateProjectMemberships(ProjectMembershipBatchUpsertRequest request,
                                                                                      PermissionActorContext actorContext) {
        if (request == null || request.memberships() == null || request.memberships().isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "memberships 不能为空");
        }
        if (request.memberships().size() > MAX_BATCH_SIZE) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "单次最多导入 " + MAX_BATCH_SIZE + " 条项目成员授权");
        }
        String batchReason = defaultText(request.reason(), "批量导入或同步项目成员授权");
        return request.memberships().stream()
                .map(item -> grantOrUpdateInternal(item, actorContext, defaultText(item.reason(), batchReason)))
                .toList();
    }

    /**
     * 更新项目成员授权。
     */
    @Override
    @Transactional
    public ProjectMembershipMutationResult updateProjectMembership(Long membershipId,
                                                                   ProjectMembershipUpdateRequest request,
                                                                   PermissionActorContext actorContext) {
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "更新请求不能为空");
        }
        PermissionProjectMembership membership = findByIdOrThrow(membershipId);
        validateMutableMembership(membership, actorContext, normalizeCode(request.projectRole()));

        PermissionProjectMembership before = copyOf(membership);
        if (request.projectRole() != null && !request.projectRole().isBlank()) {
            membership.setProjectRole(normalizeCode(request.projectRole()));
        }
        if (request.workspaceId() != null) {
            membership.setWorkspaceId(request.workspaceId());
        } else if (Boolean.TRUE.equals(request.clearWorkspace())) {
            membership.setWorkspaceId(null);
        }
        if (request.grantSource() != null && !request.grantSource().isBlank()) {
            membership.setGrantSource(normalizeCode(request.grantSource()));
        }
        if (request.enabled() != null) {
            membership.setEnabled(request.enabled());
        }
        membership.setUpdateTime(LocalDateTime.now());
        membershipMapper.updateById(membership);

        PermissionProjectMembership after = findByIdOrThrow(membershipId);
        String summary = defaultText(request.reason(), "更新项目成员授权");
        auditSupport.saveMutationAudit(actorContext, "UPDATE_PROJECT_MEMBERSHIP", "SUCCESS", summary, before, after);
        publishMembershipChanged(EVENT_PROJECT_MEMBERSHIP_UPDATED, after, actorContext, summary);
        return result(after, "项目成员授权已更新");
    }

    /**
     * 启用项目成员授权。
     */
    @Override
    @Transactional
    public ProjectMembershipMutationResult enableProjectMembership(Long membershipId,
                                                                   ProjectMembershipStateChangeRequest request,
                                                                   PermissionActorContext actorContext) {
        return changeMembershipState(membershipId, true, request, actorContext);
    }

    /**
     * 禁用项目成员授权。
     */
    @Override
    @Transactional
    public ProjectMembershipMutationResult disableProjectMembership(Long membershipId,
                                                                    ProjectMembershipStateChangeRequest request,
                                                                    PermissionActorContext actorContext) {
        return changeMembershipState(membershipId, false, request, actorContext);
    }

    private ProjectMembershipMutationResult grantOrUpdateInternal(ProjectMembershipCreateRequest request,
                                                                  PermissionActorContext actorContext,
                                                                  String reason) {
        if (request == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "项目成员授权请求不能为空");
        }
        Long tenantId = resolveTargetTenantId(request.tenantId(), actorContext);
        Long actorId = requirePositive(request.actorId(), "actorId");
        Long projectId = requirePositive(request.projectId(), "projectId");
        String projectRole = normalizeProjectRole(request.projectRole());
        validateMutableTarget(tenantId, projectId, actorContext, projectRole);

        PermissionProjectMembership existing = membershipMapper.selectOne(new LambdaQueryWrapper<PermissionProjectMembership>()
                .eq(PermissionProjectMembership::getTenantId, tenantId)
                .eq(PermissionProjectMembership::getActorId, actorId)
                .eq(PermissionProjectMembership::getProjectId, projectId));
        PermissionProjectMembership before = copyOf(existing);
        PermissionProjectMembership target = existing == null ? new PermissionProjectMembership() : existing;
        target.setTenantId(tenantId);
        target.setActorId(actorId);
        target.setProjectId(projectId);
        target.setWorkspaceId(request.workspaceId());
        target.setProjectRole(projectRole);
        target.setGrantSource(normalizeGrantSource(request.grantSource()));
        target.setEnabled(request.enabled() == null || request.enabled());
        target.setUpdateTime(LocalDateTime.now());

        if (existing == null) {
            target.setCreateTime(LocalDateTime.now());
            membershipMapper.insert(target);
        } else {
            membershipMapper.updateById(target);
        }

        PermissionProjectMembership after = existing == null ? target : findByIdOrThrow(target.getId());
        String action = existing == null ? "GRANT_PROJECT_MEMBERSHIP" : "UPSERT_PROJECT_MEMBERSHIP";
        auditSupport.saveMutationAudit(actorContext, action, "SUCCESS", reason, before, after);
        publishMembershipChanged(EVENT_PROJECT_MEMBERSHIP_UPSERTED, after, actorContext, reason);
        return result(after, existing == null ? "项目成员授权已创建" : "项目成员授权已幂等更新");
    }
    private ProjectMembershipMutationResult changeMembershipState(Long membershipId,
                                                                  boolean enabled,
                                                                  ProjectMembershipStateChangeRequest request,
                                                                  PermissionActorContext actorContext) {
        PermissionProjectMembership membership = findByIdOrThrow(membershipId);
        validateMutableMembership(membership, actorContext, null);
        PermissionProjectMembership before = copyOf(membership);
        membership.setEnabled(enabled);
        membership.setUpdateTime(LocalDateTime.now());
        membershipMapper.updateById(membership);
        PermissionProjectMembership after = findByIdOrThrow(membershipId);
        String action = enabled ? "ENABLE_PROJECT_MEMBERSHIP" : "DISABLE_PROJECT_MEMBERSHIP";
        String summary = defaultText(request == null ? null : request.reason(),
                enabled ? "启用项目成员授权" : "禁用项目成员授权");
        auditSupport.saveMutationAudit(actorContext, action, "SUCCESS", summary, before, after);
        publishMembershipChanged(enabled ? EVENT_PROJECT_MEMBERSHIP_ENABLED : EVENT_PROJECT_MEMBERSHIP_DISABLED,
                after, actorContext, summary);
        return result(after, enabled ? "项目成员授权已启用" : "项目成员授权已禁用");
    }

    /**
     * 发布项目成员授权变更事件。
     *
     * <p>该事件的核心用途不是给前端展示，而是让 gateway 清理本地授权缓存。
     * PROJECT 数据范围会把成员关系物化成 `authorizedProjectIds`，如果这里不发事件，
     * gateway 可能继续使用旧项目集合，导致“授权刚变更但请求仍按旧范围执行”。
     */
    private void publishMembershipChanged(String eventType,
                                          PermissionProjectMembership membership,
                                          PermissionActorContext actorContext,
                                          String summary) {
        membershipChangedEventPublisher.publishProjectMembershipChanged(eventType, membership, actorContext, summary);
    }
    private void applyReadableScope(LambdaQueryWrapper<PermissionProjectMembership> wrapper,
                                    ProjectMembershipQueryCriteria criteria,
                                    PermissionActorContext actorContext,
                                    String actorRole) {
        if (PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(actorRole)) {
            eqIfPresent(wrapper, PermissionProjectMembership::getTenantId, criteria.tenantId());
            eqIfPresent(wrapper, PermissionProjectMembership::getProjectId, criteria.projectId());
            return;
        }

        Long actorTenantId = normalizeTenantId(actorContext.tenantId());
        Long requestedTenantId = criteria.tenantId() == null ? actorTenantId : criteria.tenantId();
        if (!actorTenantId.equals(requestedTenantId)) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "当前身份只能查看自身租户的项目成员授权，actorTenantId=" + actorTenantId + ", requestedTenantId=" + requestedTenantId);
        }
        wrapper.eq(PermissionProjectMembership::getTenantId, actorTenantId);

        if (PermissionRoleCode.PROJECT_OWNER.name().equals(actorRole)) {
            Set<Long> manageableProjectIds = ownerProjectIds(actorTenantId, actorContext.actorId());
            if (manageableProjectIds.isEmpty()) {
                wrapper.eq(PermissionProjectMembership::getProjectId, -1L);
                return;
            }
            if (criteria.projectId() != null) {
                if (!manageableProjectIds.contains(criteria.projectId())) {
                    throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                            "项目负责人只能查看自己拥有 OWNER 授权的项目成员，projectId=" + criteria.projectId());
                }
                wrapper.eq(PermissionProjectMembership::getProjectId, criteria.projectId());
            } else {
                wrapper.in(PermissionProjectMembership::getProjectId, manageableProjectIds);
            }
            return;
        }

        eqIfPresent(wrapper, PermissionProjectMembership::getProjectId, criteria.projectId());
    }
    private void validateReadableMembership(PermissionProjectMembership membership, PermissionActorContext actorContext) {
        String actorRole = requireRole(actorContext);
        requireAnyRole(actorRole, VIEW_ROLES, "当前角色无权查看项目成员授权");
        if (PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(actorRole)) {
            return;
        }
        Long actorTenantId = normalizeTenantId(actorContext.tenantId());
        if (!actorTenantId.equals(normalizeTenantId(membership.getTenantId()))) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED, "当前身份不能查看其他租户的项目成员授权");
        }
        if (PermissionRoleCode.PROJECT_OWNER.name().equals(actorRole)
                && !ownerProjectIds(actorTenantId, actorContext.actorId()).contains(membership.getProjectId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, "项目负责人只能查看自己负责项目的成员授权");
        }
    }
    private void validateMutableMembership(PermissionProjectMembership membership,
                                           PermissionActorContext actorContext,
                                           String targetProjectRole) {
        validateMutableTarget(membership.getTenantId(), membership.getProjectId(), actorContext,
                targetProjectRole == null ? membership.getProjectRole() : targetProjectRole);
    }

    private void validateMutableTarget(Long tenantId,
                                       Long projectId,
                                       PermissionActorContext actorContext,
                                       String targetProjectRole) {
        String actorRole = requireRole(actorContext);
        requireAnyRole(actorRole, MUTATION_ROLES, "当前角色无权管理项目成员授权");
        if (PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(actorRole)) {
            return;
        }

        Long actorTenantId = normalizeTenantId(actorContext.tenantId());
        if (!actorTenantId.equals(normalizeTenantId(tenantId))) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED, "当前身份不能管理其他租户的项目成员授权");
        }

        if (PermissionRoleCode.TENANT_ADMINISTRATOR.name().equals(actorRole)) {
            return;
        }

        if (!ownerProjectIds(actorTenantId, actorContext.actorId()).contains(projectId)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, "项目负责人只能管理自己拥有 OWNER 授权的项目成员");
        }
        if (OWNER_PROJECT_ROLE.equals(normalizeCode(targetProjectRole))) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "项目负责人不能授予或调整 OWNER 项目角色，该动作需要租户管理员或平台管理员执行");
        }
    }

    private Set<Long> ownerProjectIds(Long tenantId, Long actorId) {
        if (actorId == null) {
            return Set.of();
        }
        return membershipMapper.selectList(new LambdaQueryWrapper<PermissionProjectMembership>()
                        .eq(PermissionProjectMembership::getTenantId, normalizeTenantId(tenantId))
                        .eq(PermissionProjectMembership::getActorId, actorId)
                        .eq(PermissionProjectMembership::getEnabled, true)
                        .eq(PermissionProjectMembership::getProjectRole, OWNER_PROJECT_ROLE)
                        .isNotNull(PermissionProjectMembership::getProjectId))
                .stream()
                .map(PermissionProjectMembership::getProjectId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private PermissionProjectMembership findByIdOrThrow(Long membershipId) {
        Long id = requirePositive(membershipId, "membershipId");
        PermissionProjectMembership membership = membershipMapper.selectById(id);
        if (membership == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "项目成员授权不存在：" + id);
        }
        return membership;
    }

    private ProjectMembershipMutationResult result(PermissionProjectMembership membership, String message) {
        return new ProjectMembershipMutationResult(membership.getId(), membership.getTenantId(),
                membership.getActorId(), membership.getProjectId(), membership.getEnabled(), message);
    }
    private String requireRole(PermissionActorContext actorContext) {
        if (actorContext == null || actorContext.actorRole() == null || actorContext.actorRole().isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, "缺少可信操作者角色，不能管理项目成员授权");
        }
        return normalizeCode(actorContext.actorRole());
    }

    private void requireAnyRole(String actorRole, Set<String> allowedRoles, String message) {
        if (!allowedRoles.contains(actorRole)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, message + "，actorRole=" + actorRole);
        }
    }

    private Long resolveTargetTenantId(Long requestedTenantId, PermissionActorContext actorContext) {
        String actorRole = requireRole(actorContext);
        Long targetTenantId = requestedTenantId == null ? normalizeTenantId(actorContext.tenantId()) : requestedTenantId;
        if (!PermissionRoleCode.PLATFORM_ADMINISTRATOR.name().equals(actorRole)
                && !normalizeTenantId(actorContext.tenantId()).equals(normalizeTenantId(targetTenantId))) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "当前身份不能为其他租户新增项目成员授权");
        }
        return normalizeTenantId(targetTenantId);
    }

    private Long requirePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, fieldName + " 必须为正数");
        }
        return value;
    }

    private String normalizeProjectRole(String value) {
        return defaultText(normalizeCode(value), DEFAULT_PROJECT_ROLE);
    }

    private String normalizeGrantSource(String value) {
        return defaultText(normalizeCode(value), DEFAULT_GRANT_SOURCE);
    }

    private String normalizeCode(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private Long normalizeTenantId(Long tenantId) {
        return tenantId == null ? PLATFORM_TENANT_ID : tenantId;
    }

    private String defaultText(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private <T> Page<T> page(Long current, Long size) {
        long safeCurrent = current == null || current <= 0 ? DEFAULT_CURRENT : current;
        long safeSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return new Page<>(safeCurrent, safeSize);
    }

    private <T> void eqIfPresent(LambdaQueryWrapper<T> wrapper,
                                 com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> column,
                                 Long value) {
        if (value != null) { wrapper.eq(column, value); }
    }

    private <T> void eqIfPresent(LambdaQueryWrapper<T> wrapper,
                                 com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> column,
                                 String value) {
        if (value != null && !value.isBlank()) { wrapper.eq(column, value); }
    }
}

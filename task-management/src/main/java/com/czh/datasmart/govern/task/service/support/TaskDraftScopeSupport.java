/**
 * @Author : Cui
 * @Date: 2026/05/25 00:05
 * @Description DataSmart Govern Backend - TaskDraftScopeSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.entity.TaskDraft;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 任务草稿数据范围支持组件。
 *
 * <p>草稿虽然不会立即执行，但它已经包含任务目标、参数、项目、规则引用甚至数据源信息。
 * 因此草稿的创建、编辑、审批、转换也必须遵守租户和项目范围，不能因为“还没执行”就放松权限。</p>
 */
@Component
public class TaskDraftScopeSupport {

    private static final String PLATFORM_ADMINISTRATOR = "PLATFORM_ADMINISTRATOR";
    private static final String SERVICE_ACCOUNT = "SERVICE_ACCOUNT";
    private static final String ORDINARY_USER = "ORDINARY_USER";
    private static final String USER = "USER";

    public Long resolveTenantIdForCreate(Long requestedTenantId, TaskActorContext actorContext) {
        if (isPlatformWideActor(actorContext)) {
            return firstNonNull(requestedTenantId, actorContext == null ? null : actorContext.tenantId());
        }
        if (actorContext != null && actorContext.tenantId() != null) {
            return actorContext.tenantId();
        }
        return requestedTenantId;
    }

    public Long resolveOwnerIdForCreate(Long requestedOwnerId, TaskActorContext actorContext) {
        if (requestedOwnerId != null && isPrivilegedOwnerAssigner(actorContext)) {
            return requestedOwnerId;
        }
        if (actorContext != null && actorContext.actorId() != null) {
            return actorContext.actorId();
        }
        return requestedOwnerId;
    }

    public Long resolveProjectIdForCreate(Long requestedProjectId, TaskActorContext actorContext) {
        if (isPlatformWideActor(actorContext)) {
            return requestedProjectId;
        }
        if (actorContext != null && actorContext.projectScopeEnforced()) {
            List<Long> authorizedProjectIds = actorContext.safeAuthorizedProjectIds();
            if (authorizedProjectIds.isEmpty()) {
                throw new IllegalStateException("当前身份没有任何可见项目，不能创建任务草稿");
            }
            if (requestedProjectId == null) {
                throw new IllegalStateException("PROJECT 范围下创建任务草稿必须显式指定 projectId");
            }
            if (!authorizedProjectIds.contains(requestedProjectId)) {
                throw new IllegalStateException("无权在未授权项目中创建任务草稿，projectId="
                        + requestedProjectId + ", authorizedProjectIds=" + authorizedProjectIds);
            }
        }
        return requestedProjectId;
    }

    public void applyListScope(LambdaQueryWrapper<TaskDraft> wrapper,
                               Long requestedTenantId,
                               Long requestedOwnerId,
                               Long requestedProjectId,
                               TaskActorContext actorContext) {
        if (isProjectScopeBlocked(actorContext)) {
            wrapper.eq(TaskDraft::getId, -1L);
            return;
        }
        if (requestedTenantId != null) {
            wrapper.eq(TaskDraft::getTenantId, requestedTenantId);
        }
        if (requestedOwnerId != null) {
            wrapper.eq(TaskDraft::getOwnerId, requestedOwnerId);
        }
        if (requestedProjectId != null) {
            wrapper.eq(TaskDraft::getProjectId, requestedProjectId);
        }
        applyActorScope(wrapper, actorContext);
    }

    public void validateDraftInActorScope(TaskDraft draft, TaskActorContext actorContext, String actionName) {
        if (draft == null || isPlatformWideActor(actorContext)) {
            return;
        }
        if (actorContext == null || actorContext.tenantId() == null) {
            return;
        }
        if (isProjectScopeBlocked(actorContext)) {
            throw new IllegalStateException(actionName + " 当前身份没有任何可见项目，不能访问任务草稿");
        }
        if (draft.getTenantId() != null && !draft.getTenantId().equals(actorContext.tenantId())) {
            throw new IllegalStateException(actionName + " 无权跨租户操作任务草稿，draftTenantId="
                    + draft.getTenantId() + ", actorTenantId=" + actorContext.tenantId());
        }
        if (actorContext.projectScopeEnforced() && draft.getProjectId() != null
                && !actorContext.safeAuthorizedProjectIds().contains(draft.getProjectId())) {
            throw new IllegalStateException(actionName + " 无权访问未授权项目的任务草稿，draftProjectId="
                    + draft.getProjectId() + ", actorProjectIds=" + actorContext.safeAuthorizedProjectIds());
        }
        if (draft.getOwnerId() != null && isOrdinaryUser(actorContext) && actorContext.actorId() != null
                && !draft.getOwnerId().equals(actorContext.actorId())) {
            throw new IllegalStateException(actionName + " 无权操作其他负责人的任务草稿，draftOwnerId="
                    + draft.getOwnerId() + ", actorId=" + actorContext.actorId());
        }
    }

    private void applyActorScope(LambdaQueryWrapper<TaskDraft> wrapper, TaskActorContext actorContext) {
        if (actorContext == null || isPlatformWideActor(actorContext)) {
            return;
        }
        if (actorContext.tenantId() != null) {
            wrapper.eq(TaskDraft::getTenantId, actorContext.tenantId());
        }
        if (actorContext.projectScopeEnforced()) {
            wrapper.in(TaskDraft::getProjectId, actorContext.safeAuthorizedProjectIds());
        }
        if (isOrdinaryUser(actorContext) && actorContext.actorId() != null) {
            wrapper.eq(TaskDraft::getOwnerId, actorContext.actorId());
        }
    }

    private boolean isProjectScopeBlocked(TaskActorContext actorContext) {
        return actorContext != null
                && actorContext.projectScopeEnforced()
                && actorContext.safeAuthorizedProjectIds().isEmpty();
    }

    private boolean isPrivilegedOwnerAssigner(TaskActorContext actorContext) {
        String role = normalizeRole(actorContext);
        return isPlatformWideActor(actorContext)
                || "TENANT_ADMINISTRATOR".equals(role)
                || "PROJECT_OWNER".equals(role)
                || "OPERATOR".equals(role);
    }

    private boolean isOrdinaryUser(TaskActorContext actorContext) {
        String role = normalizeRole(actorContext);
        return ORDINARY_USER.equals(role) || USER.equals(role);
    }

    private boolean isPlatformWideActor(TaskActorContext actorContext) {
        String role = normalizeRole(actorContext);
        if (PLATFORM_ADMINISTRATOR.equals(role)) {
            return true;
        }
        return SERVICE_ACCOUNT.equals(role)
                && (actorContext == null || actorContext.tenantId() == null || actorContext.tenantId() == 0L);
    }

    private String normalizeRole(TaskActorContext actorContext) {
        if (actorContext == null || actorContext.actorRole() == null || actorContext.actorRole().isBlank()) {
            return "";
        }
        return actorContext.actorRole().trim().toUpperCase(Locale.ROOT);
    }

    private Long firstNonNull(Long first, Long second) {
        return first != null ? first : second;
    }
}

package com.czh.datasmart.govern.task.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.controller.dto.TaskQueueInspectionRequest;
import com.czh.datasmart.govern.task.entity.Task;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * @Author : Cui
 * @Date: 2026/05/06 21:42
 * @Description DataSmart Govern Backend - TaskDataScopeSupport.java
 * @Version:1.0.0
 *
 * 任务数据范围支持组件。
 *
 * <p>这个组件解决的是 task-management 进入商业化多租户阶段后最基础、也最容易被忽略的问题：
 * “同一张 task 表里的任务，哪些用户、哪些租户、哪些服务账号可以看见和操作？”
 *
 * <p>为什么不把这些判断直接写在 Controller 或 ServiceImpl 里：
 * 1. Controller 应该主要负责 HTTP 契约，不应该理解复杂的数据范围规则；
 * 2. TaskServiceImpl 已经被治理为薄门面，不能再把租户、负责人、项目、角色判断重新堆进去；
 * 3. 队列查询、普通列表、执行器认领、管理员操作都会复用同一套范围语义，集中在这里可以避免规则漂移；
 * 4. 后续 permission-admin 的数据范围策略真正下沉时，可以把这里替换为远程策略结果或本地策略缓存。
 *
 * <p>当前实现是“轻量本地规则”，目标不是一次性做完 ABAC/数据权限引擎，而是先把关键承载位补齐：
 * tenantId 用于租户隔离，ownerId 用于个人任务归属，projectId 用于租户内部二级项目隔离。
 */
@Component
public class TaskDataScopeSupport {

    private static final String PLATFORM_ADMINISTRATOR = "PLATFORM_ADMINISTRATOR";
    private static final String SERVICE_ACCOUNT = "SERVICE_ACCOUNT";
    private static final String ORDINARY_USER = "ORDINARY_USER";
    private static final String USER = "USER";

    /**
     * 解析创建任务时最终写入 task.tenant_id 的值。
     *
     * <p>业务原则：
     * 1. 平台管理员可以跨租户创建或导入任务，因此优先采纳请求体中的 tenantId；
     * 2. tenantId=0 的 SERVICE_ACCOUNT 视为平台级服务账号，允许代表具体租户提交任务；
     * 3. 普通用户、租户管理员、租户运营等非平台级身份不能通过请求体扩大租户范围，最终以 Header 中的可信租户为准；
     * 4. 如果没有任何可信上下文，则保留请求体值，用于兼容本地联调和旧脚本。
     *
     * @param requestedTenantId 请求体里的租户 ID，可能来自前端、服务账号或批量脚本。
     * @param actorContext gateway 或服务间调用透传的操作者上下文。
     * @return 最终写入任务主表的租户 ID；允许为空以兼容历史数据。
     */
    public Long resolveTenantIdForCreate(Long requestedTenantId, TaskActorContext actorContext) {
        if (isPlatformWideActor(actorContext)) {
            return firstNonNull(requestedTenantId, actorContext == null ? null : actorContext.tenantId());
        }
        if (actorContext != null && actorContext.tenantId() != null) {
            return actorContext.tenantId();
        }
        return requestedTenantId;
    }

    /**
     * 解析创建任务时最终写入 task.owner_id 的值。
     *
     * <p>负责人是任务运营闭环的重要入口。普通用户创建任务时，默认负责人应为自己；
     * 管理员或服务账号代表团队创建任务时，可以显式指定 ownerId。
     *
     * @param requestedOwnerId 请求体里的负责人 ID。
     * @param actorContext 当前操作者上下文。
     * @return 最终负责人 ID；允许为空以兼容历史数据和纯系统任务。
     */
    public Long resolveOwnerIdForCreate(Long requestedOwnerId, TaskActorContext actorContext) {
        if (requestedOwnerId != null && isPrivilegedOwnerAssigner(actorContext)) {
            return requestedOwnerId;
        }
        if (actorContext != null && actorContext.actorId() != null) {
            return actorContext.actorId();
        }
        return requestedOwnerId;
    }

    /**
     * 解析创建任务时最终写入 task.project_id 的值。
     *
     * <p>PROJECT 范围的核心边界不是“能不能看”，而是“能不能往哪个项目里写”。
     * 如果当前身份已经被 gateway 限定在某个项目集合中，那么创建任务时不能再由请求体随意填一个项目 ID。
     *
     * <p>这里采取保守策略：
     * 1. 平台级身份仍然可以代表多个项目创建任务；
     * 2. PROJECT 范围下如果没有任何授权项目，直接拒绝创建；
     * 3. PROJECT 范围下必须显式提供 projectId，避免前端空值被误写入数据库；
     * 4. 非 PROJECT 范围则保留请求体值，兼容早期联调和迁移脚本。
     */
    public Long resolveProjectIdForCreate(Long requestedProjectId, TaskActorContext actorContext) {
        if (isPlatformWideActor(actorContext)) {
            return requestedProjectId;
        }
        if (actorContext != null && actorContext.projectScopeEnforced()) {
            List<Long> authorizedProjectIds = actorContext.safeAuthorizedProjectIds();
            if (authorizedProjectIds.isEmpty()) {
                throw new IllegalStateException("当前身份没有任何可见项目，不能创建任务");
            }
            if (requestedProjectId == null) {
                throw new IllegalStateException("PROJECT 范围下创建任务必须显式指定 projectId");
            }
            if (!authorizedProjectIds.contains(requestedProjectId)) {
                throw new IllegalStateException("无权在未授权项目中创建任务，projectId="
                        + requestedProjectId + ", authorizedProjectIds=" + authorizedProjectIds);
            }
        }
        return requestedProjectId;
    }

    /**
     * 普通任务列表查询的数据范围收口。
     *
     * <p>这里采用“请求过滤 + 身份范围”叠加模式：
     * 请求参数只能缩小结果集，不能扩大权限。例如租户管理员传入其他 tenantId 时，
     * wrapper 会同时追加“请求 tenantId”和“当前操作者 tenantId”，最终不会返回越权数据。
     */
    public void applyListScope(LambdaQueryWrapper<Task> wrapper,
                               Long requestedTenantId,
                               Long requestedOwnerId,
                               Long requestedProjectId,
                               TaskActorContext actorContext) {
        if (isProjectScopeBlocked(actorContext)) {
            applyNoRows(wrapper);
            return;
        }
        applyRequestedScope(wrapper, requestedTenantId, requestedOwnerId, requestedProjectId);
        applyActorScope(wrapper, actorContext);
    }

    /**
     * 队列运维查询的数据范围收口。
     *
     * <p>队列视图比普通列表更敏感，因为它会暴露积压、失败、执行器、死信和 SLA 风险。
     * 因此它也必须走同一套 tenant/owner/project 规则，避免运营接口成为绕过普通列表权限的旁路。
     */
    public void applyQueueScope(LambdaQueryWrapper<Task> wrapper,
                                TaskQueueInspectionRequest request,
                                TaskActorContext actorContext) {
        if (isProjectScopeBlocked(actorContext)) {
            applyNoRows(wrapper);
            return;
        }
        if (request != null) {
            applyRequestedScope(wrapper, request.getTenantId(), request.getOwnerId(), request.getProjectId());
        }
        applyActorScope(wrapper, actorContext);
    }

    /**
     * 解析执行器认领任务时可使用的租户过滤条件。
     *
     * <p>平台级服务账号通常需要消费多个租户的后台任务，因此 tenantId=0 或无租户上下文的 SERVICE_ACCOUNT
     * 不强制收口；租户级服务账号或租户内运营身份则只能消费本租户任务。
     */
    public Long resolveClaimTenantFilter(Long requestedTenantId, TaskActorContext actorContext) {
        if (isPlatformWideActor(actorContext)) {
            return requestedTenantId;
        }
        if (actorContext != null && actorContext.tenantId() != null) {
            return actorContext.tenantId();
        }
        return requestedTenantId;
    }

    /**
     * 校验某个已加载任务是否处在操作者允许访问的数据范围内。
     *
     * <p>该方法主要用于管理员强制操作场景：即使某个角色具备“强制取消/强制重试”的动作权限，
     * 也不代表它可以跨租户操作其他客户的数据。动作权限和数据范围权限必须同时成立。
     */
    public void validateTaskInActorScope(Task task, TaskActorContext actorContext, String actionName) {
        if (task == null || isPlatformWideActor(actorContext)) {
            return;
        }
        if (actorContext == null || actorContext.tenantId() == null) {
            return;
        }
        if (isProjectScopeBlocked(actorContext)) {
            throw new IllegalStateException(actionName + " 当前身份没有任何可见项目，不能访问任务数据");
        }
        Long taskTenantId = task.getTenantId();
        if (taskTenantId != null && !taskTenantId.equals(actorContext.tenantId())) {
            throw new IllegalStateException(actionName + " 无权跨租户操作任务，taskTenantId="
                    + taskTenantId + ", actorTenantId=" + actorContext.tenantId());
        }
        Long taskProjectId = task.getProjectId();
        if (actorContext.projectScopeEnforced() && taskProjectId != null
                && !actorContext.safeAuthorizedProjectIds().contains(taskProjectId)) {
            throw new IllegalStateException(actionName + " 无权访问未授权项目的任务，taskProjectId="
                    + taskProjectId + ", actorProjectIds=" + actorContext.safeAuthorizedProjectIds());
        }
        /*
         * 普通用户的范围还要继续收窄到 ownerId。
         * 这条规则服务的是“个人任务中心 / 我负责的任务 / 我发起的质量扫描”等场景：
         * 同一个租户内可能有很多项目组和成员，普通用户即使属于同一租户，也不应该默认看到或操作同事负责的任务。
         *
         * 这里刻意只在 task.ownerId 非空时校验，原因是历史任务、系统任务或早期脚本导入任务可能还没有负责人字段。
         * 对这些兼容数据先不做硬阻断，后续可以通过数据治理脚本补齐 ownerId 后再收紧策略。
         */
        Long taskOwnerId = task.getOwnerId();
        if (taskOwnerId != null && isOrdinaryUser(actorContext) && actorContext.actorId() != null
                && !taskOwnerId.equals(actorContext.actorId())) {
            throw new IllegalStateException(actionName + " 无权操作其他负责人的任务，taskOwnerId="
                    + taskOwnerId + ", actorId=" + actorContext.actorId());
        }
    }

    private void applyRequestedScope(LambdaQueryWrapper<Task> wrapper,
                                     Long requestedTenantId,
                                     Long requestedOwnerId,
                                     Long requestedProjectId) {
        if (requestedTenantId != null) {
            wrapper.eq(Task::getTenantId, requestedTenantId);
        }
        if (requestedOwnerId != null) {
            wrapper.eq(Task::getOwnerId, requestedOwnerId);
        }
        if (requestedProjectId != null) {
            wrapper.eq(Task::getProjectId, requestedProjectId);
        }
    }

    private void applyActorScope(LambdaQueryWrapper<Task> wrapper, TaskActorContext actorContext) {
        if (actorContext == null || isPlatformWideActor(actorContext)) {
            return;
        }
        if (actorContext.tenantId() != null) {
            wrapper.eq(Task::getTenantId, actorContext.tenantId());
        }
        if (actorContext.projectScopeEnforced()) {
            List<Long> authorizedProjectIds = actorContext.safeAuthorizedProjectIds();
            if (authorizedProjectIds.isEmpty()) {
                applyNoRows(wrapper);
                return;
            }
            wrapper.in(Task::getProjectId, authorizedProjectIds);
        }
        if (isOrdinaryUser(actorContext) && actorContext.actorId() != null) {
            wrapper.eq(Task::getOwnerId, actorContext.actorId());
        }
    }

    /**
     * 判断 PROJECT 数据范围是否已经把当前请求收口到“无可见项目”。
     *
     * <p>这里区分两种不同语义：
     * 1. 没有启用 PROJECT 范围时，不做项目集合过滤；
     * 2. 启用了 PROJECT 范围但授权集合为空时，应明确视为“无可见任务”而不是退化为租户全量。
     */
    private boolean isProjectScopeBlocked(TaskActorContext actorContext) {
        return actorContext != null
                && actorContext.projectScopeEnforced()
                && actorContext.safeAuthorizedProjectIds().isEmpty();
    }

    /**
     * 追加一个永远不会命中的条件，作为“无可见项目”场景的数据库侧收口。
     *
     * <p>这里不用直接抛异常，是为了让列表/队列类接口仍然保持可查询的产品体验：
     * 调用方得到空列表，比直接 500 更容易理解，也更符合“无权限即无数据”的权限语义。
     */
    private void applyNoRows(LambdaQueryWrapper<Task> wrapper) {
        wrapper.eq(Task::getId, -1L);
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

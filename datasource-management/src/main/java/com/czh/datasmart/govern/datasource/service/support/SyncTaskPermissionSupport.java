/**
 * @Author : Cui
 * @Date: 2026/05/05 18:43
 * @Description DataSmart Govern Backend - SyncTaskPermissionSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.datasource.controller.dto.CreateSyncTaskRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncApprovalRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncExecutorClaimRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncExecutorHeartbeatRequest;
import com.czh.datasmart.govern.datasource.controller.dto.UpdateSyncTaskRequest;
import com.czh.datasmart.govern.datasource.entity.SyncTask;
import com.czh.datasmart.govern.datasource.support.ActorRole;
import com.czh.datasmart.govern.datasource.support.SyncPermissionAction;
import com.czh.datasmart.govern.datasource.support.SyncPermissionContext;
import com.czh.datasmart.govern.datasource.support.SyncPermissionEvaluator;
import com.czh.datasmart.govern.datasource.support.SyncPermissionResource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 同步任务权限守卫组件。
 *
 * <p>这个类专门处理“谁能对同步任务、同步执行器、同步队列做什么”的问题。
 * 把权限逻辑从 `SyncTaskServiceImpl` 拆出来，是为了让主服务从大量前置校验中解脱出来，
 * 继续聚焦事务编排、状态流转和持久化副作用。
 *
 * <p>本项目当前同时存在两层权限语义：
 * 1. `SyncPermissionEvaluator`：偏平台化的资源-动作权限模型，未来可以对接 permission-admin；
 * 2. `ActorRole`：领域内的角色能力兜底，用于在本模块尚未完全接入统一权限中心前保持业务安全。
 *
 * <p>这里保留两层检查，而不是只留其中一个，原因是商用产品通常需要“统一权限中心 + 领域防线”双保险：
 * 1. 统一权限中心便于后台配置、审计和租户差异化；
 * 2. 领域防线可以防止配置缺失、灰度期间策略未同步、或者内部接口绕过网关时造成越权。
 */
@Component
@RequiredArgsConstructor
public class SyncTaskPermissionSupport {

    /**
     * 同步领域权限判定器。
     *
     * <p>当前判定器仍然在 datasource-management 内部实现，
     * 后续当 permission-admin 成为统一权限中心后，这个判定器可以演进为远程策略客户端或本地缓存客户端。
     */
    private final SyncPermissionEvaluator syncPermissionEvaluator;

    /**
     * 校验任务创建权限。
     *
     * <p>创建任务时不仅要校验“是否有 CREATE 权限”，还要校验“是否能替别人创建任务”。
     * 这属于真实产品中很常见的边界：项目负责人可以创建自己负责的同步任务，
     * 但运营人员或管理员才适合代建跨负责人任务。
     */
    public void assertTaskCreationPermission(CreateSyncTaskRequest request) {
        syncPermissionEvaluator.assertAllowed(SyncPermissionContext.builder()
                        .actorId(request.getCreatedBy())
                        .actorRole(request.getActorRole())
                        .actorTenantId(request.getActorTenantId())
                        .resourceTenantId(request.getTenantId())
                        .resourceOwnerId(request.getOwnerId())
                        .resourceCreatedBy(request.getCreatedBy())
                        .build(),
                SyncPermissionResource.SYNC_TASK, SyncPermissionAction.CREATE);
        ActorRole role = ActorRole.fromValue(request.getActorRole());
        if (!role.canCreateSyncTasks()) {
            throw new IllegalStateException("当前角色无创建同步任务权限: " + role.name());
        }
        if (role.canOperateOwnedSyncTasks() && !request.getCreatedBy().equals(request.getOwnerId())) {
            throw new IllegalStateException("项目负责人创建任务时，创建人与负责人必须保持一致");
        }
    }

    /**
     * 校验任务更新权限。
     *
     * <p>先尝试 `UPDATE_ANY`，允许运营、租户管理员、平台管理员这类治理角色跨任务修改。
     * 如果没有任意更新权限，再退回 `UPDATE_OWNED`，并继续检查任务是否属于当前操作者。
     */
    public void assertTaskUpdatePermission(SyncTask task, UpdateSyncTaskRequest request) {
        SyncPermissionContext context = buildTaskContext(
                task,
                request.getUpdatedBy(),
                request.getActorRole(),
                request.getActorTenantId()
        );
        if (syncPermissionEvaluator.canAccess(context,
                SyncPermissionResource.SYNC_TASK, SyncPermissionAction.UPDATE_ANY)) {
            return;
        }
        syncPermissionEvaluator.assertAllowed(context,
                SyncPermissionResource.SYNC_TASK, SyncPermissionAction.UPDATE_OWNED);
        ActorRole role = ActorRole.fromValue(request.getActorRole());
        if (role.canOperateAnySyncTasks()) {
            return;
        }
        if (role.canOperateOwnedSyncTasks() && isTaskOwnedByActor(task, request.getUpdatedBy())) {
            if (!request.getUpdatedBy().equals(request.getOwnerId())) {
                throw new IllegalStateException("项目负责人更新任务时不允许把负责人改成其他人");
            }
            return;
        }
        throw new IllegalStateException("当前角色无更新该同步任务权限: " + role.name());
    }

    /**
     * 校验普通任务操作权限。
     *
     * <p>提交审批、调度、入队、运行、暂停、恢复、重试、取消等动作都会改变任务生命周期。
     * 它们共用同一类权限守卫，可以避免某个入口遗漏越权检查。
     */
    public void assertTaskOperationPermission(SyncTask task, Long actorId, String actorRole, Long actorTenantId) {
        SyncPermissionContext context = buildTaskContext(task, actorId, actorRole, actorTenantId);
        if (syncPermissionEvaluator.canAccess(context,
                SyncPermissionResource.SYNC_TASK, SyncPermissionAction.OPERATE_ANY)) {
            return;
        }
        syncPermissionEvaluator.assertAllowed(context,
                SyncPermissionResource.SYNC_TASK, SyncPermissionAction.OPERATE_OWNED);
        ActorRole role = ActorRole.fromValue(actorRole);
        if (role.canOperateAnySyncTasks()) {
            return;
        }
        if (role.canOperateOwnedSyncTasks() && isTaskOwnedByActor(task, actorId)) {
            return;
        }
        throw new IllegalStateException("当前角色无操作该同步任务权限: " + role.name());
    }

    /**
     * 校验审批权限。
     *
     * <p>审批动作和普通任务操作分开，是因为审批通常涉及合规授权、数据风险确认和变更留痕，
     * 后续可以接入更严格的审批策略，例如四眼原则、敏感数据源二次审批、跨租户审批禁止等。
     */
    public void assertApprovalPermission(SyncApprovalRequest request, SyncTask task) {
        syncPermissionEvaluator.assertAllowed(buildTaskContext(
                        task,
                        request.getActorId(),
                        request.getActorRole(),
                        request.getActorTenantId()
                ),
                SyncPermissionResource.SYNC_APPROVAL, SyncPermissionAction.APPROVE);
        ActorRole actorRole = ActorRole.fromValue(request.getActorRole());
        if (!actorRole.canApprove()) {
            throw new IllegalStateException("当前角色无审批权限: " + actorRole.name());
        }
    }

    /**
     * 校验执行器认领权限。
     *
     * <p>任务认领属于执行器平面能力，不能开放给普通业务用户。
     * 这里后续可扩展执行器池、服务账号签名、IP 白名单、实例心跳状态等生产级控制。
     */
    public void assertClaimPermission(SyncExecutorClaimRequest request) {
        syncPermissionEvaluator.assertAllowed(buildActorContext(
                        request.getActorId(),
                        request.getActorRole(),
                        request.getActorTenantId()
                ),
                SyncPermissionResource.SYNC_EXECUTOR, SyncPermissionAction.CLAIM);
        ActorRole actorRole = ActorRole.fromValue(request.getActorRole());
        if (!actorRole.canClaimQueuedTasks()) {
            throw new IllegalStateException("当前角色无队列任务认领权限: " + actorRole.name());
        }
    }

    /**
     * 校验执行器心跳权限。
     *
     * <p>心跳会延长任务租约，是防止任务被错误恢复或重复执行的关键控制点。
     * 因此它必须限定为执行器或具备执行器能力的服务账号，而不是普通用户接口。
     */
    public void assertHeartbeatPermission(SyncExecutorHeartbeatRequest request) {
        syncPermissionEvaluator.assertAllowed(buildActorContext(
                        request.getActorId(),
                        request.getActorRole(),
                        request.getActorTenantId()
                ),
                SyncPermissionResource.SYNC_EXECUTOR, SyncPermissionAction.HEARTBEAT);
        ActorRole actorRole = ActorRole.fromValue(request.getActorRole());
        if (!actorRole.canReportExecutionHeartbeat()) {
            throw new IllegalStateException("当前角色无执行器心跳上报权限: " + actorRole.name());
        }
    }

    /**
     * 校验执行进度回写权限。
     *
     * <p>进度回写通常由执行器调用，用于刷新处理行数、当前阶段和检查点。
     * 若该接口开放过宽，用户可能伪造执行状态，造成运营看板和审计数据失真。
     */
    public void assertExecutionProgressPermission(String actorRole, Long actorId, Long actorTenantId, SyncTask task) {
        syncPermissionEvaluator.assertAllowed(buildTaskContext(task, actorId, actorRole, actorTenantId),
                SyncPermissionResource.SYNC_EXECUTOR, SyncPermissionAction.REPORT_PROGRESS);
        ActorRole role = ActorRole.fromValue(actorRole);
        if (!role.canReportExecutionProgress()) {
            throw new IllegalStateException("当前角色无执行进度回写权限: " + role.name());
        }
    }

    /**
     * 校验执行结果回写权限。
     *
     * <p>完成和失败回写会改变任务主状态，比普通进度更新更敏感。
     * 后续可在这里接入“执行器是否持有当前租约”“结果签名是否可信”等更强约束。
     */
    public void assertExecutionResultPermission(String actorRole, Long actorId, Long actorTenantId, SyncTask task) {
        syncPermissionEvaluator.assertAllowed(buildTaskContext(task, actorId, actorRole, actorTenantId),
                SyncPermissionResource.SYNC_EXECUTOR, SyncPermissionAction.REPORT_RESULT);
        ActorRole role = ActorRole.fromValue(actorRole);
        if (!role.canReportExecutionResult()) {
            throw new IllegalStateException("当前角色无执行结果回写权限: " + role.name());
        }
    }

    /**
     * 校验队列健康查看权限。
     *
     * <p>队列健康包含跨任务积压、租户积压和老化风险，不应复用普通任务查看权限。
     * 它更接近运营工作台能力，默认只允许运营、租户管理员、平台管理员等治理角色访问。
     */
    public void assertQueueHealthPermission(String actorRole, Long actorTenantId) {
        syncPermissionEvaluator.assertAllowed(buildActorContext(null, actorRole, actorTenantId),
                SyncPermissionResource.SYNC_QUEUE, SyncPermissionAction.VIEW_QUEUE_HEALTH);
        ActorRole role = ActorRole.fromValue(actorRole);
        if (!role.canInspectQueueHealth()) {
            throw new IllegalStateException("当前角色无查看队列健康权限: " + role.name());
        }
    }

    /**
     * 校验队列老化巡检权限。
     *
     * <p>老化巡检会批量标记任务需要人工关注，并触发治理告警。
     * 这类批量运维动作必须比普通查看更严格，否则容易造成误告警或运营噪声。
     */
    public void assertQueueAgingPermission(String actorRole, Long actorTenantId) {
        syncPermissionEvaluator.assertAllowed(buildActorContext(null, actorRole, actorTenantId),
                SyncPermissionResource.SYNC_QUEUE, SyncPermissionAction.SCAN_QUEUE_AGING);
        ActorRole role = ActorRole.fromValue(actorRole);
        if (!role.canScanQueueAging()) {
            throw new IllegalStateException("当前角色无执行队列老化巡检权限: " + role.name());
        }
    }

    /**
     * 校验管理员强制控制权限。
     *
     * <p>强制重试、强制取消、优先级覆盖、超时覆盖、归档等动作会绕过普通用户生命周期限制，
     * 因此必须独立使用 `SYNC_ADMIN/ADMIN_OVERRIDE`，不能只依赖普通任务操作权限。
     */
    public void assertAdminRole(String actorRole, Long actorTenantId) {
        syncPermissionEvaluator.assertAllowed(buildActorContext(null, actorRole, actorTenantId),
                SyncPermissionResource.SYNC_ADMIN, SyncPermissionAction.ADMIN_OVERRIDE);
        ActorRole role = ActorRole.fromValue(actorRole);
        if (!role.canForceOverride()) {
            throw new IllegalStateException("当前角色无管理员强制控制权限: " + actorRole);
        }
    }

    /**
     * 构造任务资源权限上下文。
     *
     * <p>这里把 tenantId、ownerId、createdBy 都放入上下文，是为了支持后续更细的数据范围策略：
     * 例如“只能访问本租户”“只能访问自己负责的任务”“平台管理员可跨租户”等。
     */
    private SyncPermissionContext buildTaskContext(SyncTask task, Long actorId, String actorRole, Long actorTenantId) {
        return SyncPermissionContext.builder()
                .actorId(actorId)
                .actorRole(actorRole)
                .actorTenantId(actorTenantId)
                .resourceTenantId(task.getTenantId())
                .resourceOwnerId(task.getOwnerId())
                .resourceCreatedBy(task.getCreatedBy())
                .build();
    }

    /**
     * 构造不绑定具体任务的操作者上下文。
     *
     * <p>执行器认领、队列巡检、管理员动作有时并不天然绑定某一个任务，
     * 因此只传操作者身份和租户身份，让权限模型以“平台动作”方式判断。
     */
    private SyncPermissionContext buildActorContext(Long actorId, String actorRole, Long actorTenantId) {
        return SyncPermissionContext.builder()
                .actorId(actorId)
                .actorRole(actorRole)
                .actorTenantId(actorTenantId)
                .build();
    }

    /**
     * 判断任务是否属于当前操作者。
     *
     * <p>这里同时接受“负责人”和“创建人”命中，是因为很多企业平台中会出现：
     * A 创建任务，B 作为负责人持续维护；或者用户自己创建并自己负责两种常见模式。
     */
    private boolean isTaskOwnedByActor(SyncTask task, Long actorId) {
        if (task == null || actorId == null) {
            return false;
        }
        return actorId.equals(task.getOwnerId()) || actorId.equals(task.getCreatedBy());
    }
}

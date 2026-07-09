/**
 * @Author : Cui
 * @Date: 2026/07/07 18:15
 * @Description DataSmart Govern Backend - SyncTaskManagementOperationSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskCloneRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskLifecycleOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplateExecutionPrecheckResponse;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTemplateMapper;
import com.czh.datasmart.govern.datasync.support.SyncApprovalState;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

/**
 * 同步任务管理面操作支撑组件。
 *
 * <p>本组件承载“任务定义对象”的管理动作，而不是数据搬运动作。它和执行器链路的职责边界如下：</p>
 * <p>1. 下线、回收站、彻底删除、克隆属于任务定义管理，不直接读取源端或写目标端；</p>
 * <p>2. 手工调度会创建新的 QUEUED execution，但真实数据搬运仍由 worker loop、租约和回调协议完成；</p>
 * <p>3. 手工结束会写入 execution 控制信号，使 worker 在 heartbeat/checkpoint/complete/fail 阶段停止；</p>
 * <p>4. 克隆会生成全新的任务 ID 和生命周期，不能复制 execution、checkpoint、错误样本或审批事实。</p>
 *
 * <p>把这些逻辑从 DataSyncServiceImpl 拆出来，是为了让主 Service 保持“入口授权 + 领域组件编排”的角色，
 * 避免后续导入导出、批量下线、恢复回收站、任务发布等能力继续把主实现撑成大文件。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncTaskManagementOperationSupport {

    /**
     * 这些 execution 状态表示当前仍可能被 worker 认领、续租或回调，需要在手工结束时写入停止信号。
     */
    private static final Set<String> TERMINATABLE_EXECUTION_STATES = Set.of(
            SyncExecutionState.QUEUED.name(),
            SyncExecutionState.RUNNING.name(),
            SyncExecutionState.RETRYING.name(),
            SyncExecutionState.PAUSED.name()
    );

    /**
     * 操作原因低敏兜底关键字。
     *
     * <p>原因字段会进入审计摘要。这里不是完整 DLP，而是基础防线：
     * 避免用户把密码、token、SQL、prompt、样本行等内容粘贴到审计表中，造成二次泄露。</p>
     */
    private static final Set<String> SENSITIVE_REASON_KEYWORDS = Set.of(
            "password", "token", "secret", "credential", "access_key", "private_key",
            "jdbc:", "sql", "prompt", "payload", "sample", "密码", "密钥", "令牌", "凭据", "样本"
    );

    private static final DateTimeFormatter CLONE_SUFFIX_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SyncTaskMapper taskMapper;
    private final SyncTemplateMapper templateMapper;
    private final SyncExecutionMapper executionMapper;
    private final SyncTaskStateMachineSupport stateMachineSupport;
    private final SyncTemplateExecutionPrecheckSupport templateExecutionPrecheckSupport;
    private final SyncExecutionCreationSupport executionCreationSupport;
    private final SyncTaskGroupOperationSupport taskGroupOperationSupport;
    private final SyncAuditSupport auditSupport;

    /**
     * 手工调度任务立即执行一次。
     *
     * <p>该方法是 {@code /manual-dispatch} 的语义入口。它和 {@code runTask} 的执行效果接近，
     * 都会创建 MANUAL execution 并把任务置为 QUEUED；但审计动作独立记录为 MANUAL_DISPATCH_TASK，
     * 方便后续运营台区分“用户点击立即调度一次”和执行器内部 RUN_TASK 生命周期事件。</p>
     */
    public SyncTaskOperationResult manualDispatchTask(SyncTask task, SyncActorContext actorContext) {
        SyncTemplate template = getTemplateForTask(task);
        SyncTemplateExecutionPrecheckResponse precheck = templateExecutionPrecheckSupport.precheck(template);
        if (!canRunAfterPrecheck(precheck, task)) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "同步任务手工调度前预检查未通过，precheckStatus=" + precheck.precheckStatus()
                            + "，issueCodes=" + precheck.issueCodes()
                            + "，recommendedActions=" + precheck.recommendedActions());
        }
        SyncExecution execution = queueManualExecution(task, actorContext, SyncAuditActionType.MANUAL_DISPATCH_TASK);
        return new SyncTaskOperationResult(task.getId(), SyncTaskState.QUEUED.name(),
                "同步任务已手工调度并进入待执行队列，executionId=" + execution.getId());
    }

    /**
     * 手工结束当前任务运行。
     *
     * <p>手工结束不是物理 kill worker，而是写入控制面停止信号：
     * 1. 如果最近 execution 仍处于 QUEUED/RUNNING/RETRYING/PAUSED，则标记为 MANUALLY_TERMINATED；
     * 2. worker 下一次 heartbeat 会收到 STOP_FOR_MANUAL_TERMINATE；
     * 3. 如果 worker 延迟提交 checkpoint/complete/fail，回调保护也会拒绝继续写入。</p>
     */
    public SyncTaskOperationResult manualTerminateTask(SyncTask task,
                                                       SyncTaskLifecycleOperationRequest request,
                                                       SyncActorContext actorContext) {
        stateMachineSupport.assertCanManualTerminate(task.getCurrentState());
        String reason = sanitizeReason(request, "用户手工结束同步任务");
        SyncExecution execution = markLatestExecutionState(task, SyncExecutionState.MANUALLY_TERMINATED, reason);
        markManagementState(task, SyncTaskState.MANUALLY_TERMINATED, false, null,
                latestExecutionId(task, execution), false, null);
        auditSupport.saveAudit(task.getTenantId(), task.getId(), latestExecutionId(task, execution),
                SyncAuditActionType.MANUAL_TERMINATE_TASK, actorContext,
                auditPayload("manualTerminate", latestExecutionId(task, execution), reason));
        return new SyncTaskOperationResult(task.getId(), SyncTaskState.MANUALLY_TERMINATED.name(),
                "同步任务已手工结束；若最近 execution 仍在运行，worker 将收到停止信号并停止后续写入");
    }

    /**
     * 下线任务。
     *
     * <p>下线是删除前置动作，也是停止定时任务的正式管理动作。它会关闭 scheduleEnabled 并清空 nextFireTime，
     * 使 task scheduler 无法再扫描到该任务。活跃执行状态不能直接下线，需要先 pause/cancel/terminate。</p>
     */
    public SyncTaskOperationResult offlineTask(SyncTask task,
                                               SyncTaskLifecycleOperationRequest request,
                                               SyncActorContext actorContext) {
        stateMachineSupport.assertCanOffline(task.getCurrentState());
        String reason = sanitizeReason(request, "用户下线同步任务");
        markManagementState(task, SyncTaskState.OFFLINE, false, null, task.getLastExecutionId(), false, null);
        auditSupport.saveAudit(task.getTenantId(), task.getId(), task.getLastExecutionId(),
                SyncAuditActionType.OFFLINE_TASK, actorContext,
                auditPayload("offline", task.getLastExecutionId(), reason));
        return new SyncTaskOperationResult(task.getId(), SyncTaskState.OFFLINE.name(),
                "同步任务已下线，自动调度已关闭；如需删除，请继续执行回收站删除动作");
    }

    /**
     * 删除任务到回收站。
     *
     * <p>该方法只允许 OFFLINE -> RECYCLED。这样可以保证删除动作前已经显式停止调度，
     * 避免一个仍处于 SCHEDULED 的任务被移入回收站后还被后台 task scheduler 触发。</p>
     */
    public SyncTaskOperationResult recycleTask(SyncTask task,
                                               SyncTaskLifecycleOperationRequest request,
                                               SyncActorContext actorContext) {
        stateMachineSupport.assertCanRecycle(task.getCurrentState());
        String reason = sanitizeReason(request, "用户删除同步任务到回收站");
        markManagementState(task, SyncTaskState.RECYCLED, false, null, task.getLastExecutionId(), false, null);
        auditSupport.saveAudit(task.getTenantId(), task.getId(), task.getLastExecutionId(),
                SyncAuditActionType.RECYCLE_TASK, actorContext,
                auditPayload("recycle", task.getLastExecutionId(), reason));
        return new SyncTaskOperationResult(task.getId(), SyncTaskState.RECYCLED.name(),
                "同步任务已进入回收站；回收站内仍可查看详情和克隆，但不能执行或调度");
    }

    /**
     * 从回收站彻底删除任务。
     *
     * <p>当前“彻底删除”采用逻辑 DELETED，而非物理删除。这样既能满足产品层“不再可见、不再可操作”的语义，
     * 又不会破坏 execution、checkpoint、error sample、object ledger 和审计记录的历史归属。</p>
     */
    public SyncTaskOperationResult hardDeleteTask(SyncTask task,
                                                  SyncTaskLifecycleOperationRequest request,
                                                  SyncActorContext actorContext) {
        stateMachineSupport.assertCanHardDelete(task.getCurrentState());
        String reason = sanitizeReason(request, "用户彻底删除回收站同步任务");
        markManagementState(task, SyncTaskState.DELETED, false, null, task.getLastExecutionId(), false, null);
        auditSupport.saveAudit(task.getTenantId(), task.getId(), task.getLastExecutionId(),
                SyncAuditActionType.HARD_DELETE_TASK, actorContext,
                auditPayload("hardDelete", task.getLastExecutionId(), reason));
        return new SyncTaskOperationResult(task.getId(), SyncTaskState.DELETED.name(),
                "同步任务已逻辑彻底删除，普通列表和详情不再返回该任务；历史审计证据仍按保留策略保存");
    }

    /**
     * 克隆同步任务。
     *
     * <p>克隆只复制任务定义字段，不复制任何运行事实：
     * execution、checkpoint、错误样本、对象账本、审批事实和人工介入标记都不会复制。
     * 新任务默认进入 DRAFT，便于用户或 Agent 再次确认表映射、字段映射、where 条件、调度窗口和容量风险。</p>
     */
    public SyncTaskOperationResult cloneTask(SyncTask sourceTask,
                                             SyncTaskCloneRequest request,
                                             SyncActorContext actorContext) {
        if (SyncTaskState.DELETED.name().equals(sourceTask.getCurrentState())) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "已彻底删除的同步任务不能克隆");
        }
        SyncTemplate template = getTemplateForTask(sourceTask);
        SyncTemplateExecutionPrecheckResponse precheck = templateExecutionPrecheckSupport.precheck(template);
        if (!precheck.canCreateTaskDraft()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "来源任务关联模板当前不能创建克隆草稿，precheckStatus=" + precheck.precheckStatus()
                            + "，issueCodes=" + precheck.issueCodes());
        }

        boolean runImmediately = request != null && Boolean.TRUE.equals(request.getRunImmediately());
        String name = resolveCloneName(sourceTask, request);
        assertTaskNameNotExists(sourceTask.getTenantId(), sourceTask.getProjectId(), name);

        SyncTask cloned = new SyncTask();
        cloned.setTenantId(sourceTask.getTenantId());
        cloned.setProjectId(sourceTask.getProjectId());
        cloned.setWorkspaceId(sourceTask.getWorkspaceId());
        cloned.setTemplateId(sourceTask.getTemplateId());
        SyncTaskGroupOperationSupport.TaskGroupAssignment groupAssignment =
                resolveCloneGroup(sourceTask, request, actorContext);
        cloned.setGroupCode(groupAssignment.groupCode());
        cloned.setGroupName(groupAssignment.groupName());
        cloned.setName(name);
        cloned.setDescription(resolveCloneDescription(sourceTask, request));
        cloned.setCurrentState(SyncTaskState.DRAFT.name());
        /*
         * 普通同步任务现在是项目内用户自有资源，克隆结果只进入 DRAFT 等待用户继续编辑。
         * 即使预检查提示“高风险建议人工确认”，也不再把克隆任务写成 PENDING 审批状态，避免前端没有审批入口时
         * 旧兼容字段反过来阻塞任务发布或执行。真正的高风险治理应沉淀到独立审计/权限事实，而不是任务表审批列。
         */
        cloned.setApprovalState(SyncApprovalState.NOT_REQUIRED.name());
        cloned.setPriority(sourceTask.getPriority());
        cloned.setScheduleConfig(keepScheduleConfig(request) ? sourceTask.getScheduleConfig() : null);
        cloned.setScheduleEnabled(false);
        cloned.setNextFireTime(null);
        cloned.setLastFireTime(null);
        cloned.setScheduleMisfireCount(0);
        cloned.setScheduleDispatchCount(0L);
        cloned.setScheduleVersion(0L);
        cloned.setRunMode("CLONED_DRAFT");
        cloned.setTriggerType(SyncTriggerType.MANUAL.name());
        cloned.setOwnerId(resolveCloneOwner(sourceTask, request, actorContext));
        cloned.setLastExecutionId(null);
        cloned.setAttentionRequired(false);
        cloned.setAttentionReason(null);
        cloned.setCreateTime(LocalDateTime.now());
        cloned.setUpdateTime(LocalDateTime.now());
        taskMapper.insert(cloned);

        if (runImmediately) {
            if (!canRunAfterPrecheck(precheck, cloned)) {
                throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                        "克隆任务要求立即执行，但当前模板需要审批或不可执行，precheckStatus=" + precheck.precheckStatus());
            }
            /*
             * 克隆默认先落 DRAFT，是为了让“不立即执行”的路径安全可编辑。
             * 当调用方明确要求 runImmediately=true 且预检允许执行时，服务端在同一事务内把新任务推进到 CONFIGURED，
             * 再交给统一的队列状态机创建 execution。这样不会放宽 DRAFT 的普通运行准入，也不会复制来源任务的旧运行状态。
             */
            cloned.setCurrentState(SyncTaskState.CONFIGURED.name());
            SyncExecution execution = queueManualExecution(cloned, actorContext, SyncAuditActionType.CLONE_TASK);
            auditSupport.saveAudit(cloned.getTenantId(), cloned.getId(), execution.getId(),
                    SyncAuditActionType.MANUAL_DISPATCH_TASK, actorContext,
                    "sourceTaskId=" + sourceTask.getId() + ",clonedTaskId=" + cloned.getId()
                            + ",executionId=" + execution.getId() + ",runImmediately=true");
            return new SyncTaskOperationResult(cloned.getId(), SyncTaskState.QUEUED.name(),
                    "同步任务已克隆并立即进入待执行队列，sourceTaskId=" + sourceTask.getId()
                            + ", executionId=" + execution.getId());
        }

        auditSupport.saveAudit(cloned.getTenantId(), cloned.getId(), null, SyncAuditActionType.CLONE_TASK,
                actorContext, "sourceTaskId=" + sourceTask.getId()
                        + ",clonedTaskId=" + cloned.getId()
                        + ",groupCode=" + cloned.getGroupCode()
                        + ",keepScheduleConfig=" + keepScheduleConfig(request)
                        + ",runImmediately=false");
        return new SyncTaskOperationResult(cloned.getId(), SyncTaskState.DRAFT.name(),
                "同步任务已克隆为编辑中草稿，sourceTaskId=" + sourceTask.getId());
    }

    private SyncExecution queueManualExecution(SyncTask task,
                                               SyncActorContext actorContext,
                                               SyncAuditActionType auditActionType) {
        stateMachineSupport.assertCanQueue(task.getCurrentState());
        SyncExecution execution = executionCreationSupport.createQueuedExecution(task, actorContext, SyncTriggerType.MANUAL);
        task.setCurrentState(SyncTaskState.QUEUED.name());
        task.setTriggerType(SyncTriggerType.MANUAL.name());
        task.setLastExecutionId(execution.getId());
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
        auditSupport.saveAudit(task.getTenantId(), task.getId(), execution.getId(), auditActionType,
                actorContext, "taskId=" + task.getId() + ",executionId=" + execution.getId());
        return execution;
    }

    private SyncExecution markLatestExecutionState(SyncTask task,
                                                   SyncExecutionState targetState,
                                                   String reason) {
        if (task.getLastExecutionId() == null) {
            return null;
        }
        SyncExecution execution = executionMapper.selectById(task.getLastExecutionId());
        if (execution == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "同步任务最近执行记录不存在，taskId=" + task.getId() + ", executionId=" + task.getLastExecutionId());
        }
        if (!task.getId().equals(execution.getSyncTaskId())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "同步任务最近执行记录归属异常，taskId=" + task.getId() + ", executionId=" + execution.getId());
        }
        if (!TERMINATABLE_EXECUTION_STATES.contains(execution.getExecutionState())) {
            return null;
        }
        execution.setExecutionState(targetState.name());
        execution.setErrorSummary(reason);
        execution.setFinishedAt(LocalDateTime.now());
        execution.setUpdateTime(LocalDateTime.now());
        executionMapper.updateById(execution);
        return execution;
    }

    private void markManagementState(SyncTask task,
                                     SyncTaskState targetState,
                                     boolean scheduleEnabled,
                                     LocalDateTime nextFireTime,
                                     Long lastExecutionId,
                                     boolean attentionRequired,
                                     String attentionReason) {
        int updated = taskMapper.markManagementState(
                task.getId(),
                targetState.name(),
                scheduleEnabled,
                nextFireTime,
                targetState == SyncTaskState.MANUALLY_TERMINATED ? SyncTriggerType.MANUAL.name() : null,
                lastExecutionId,
                attentionRequired,
                attentionReason);
        if (updated == 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "同步任务管理状态更新失败，taskId=" + task.getId() + ", targetState=" + targetState.name());
        }
    }

    private SyncTemplate getTemplateForTask(SyncTask task) {
        SyncTemplate template = templateMapper.selectById(task.getTemplateId());
        if (template == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "同步任务关联模板不存在，templateId=" + task.getTemplateId());
        }
        if (!task.getTenantId().equals(template.getTenantId())
                || !sameNullable(task.getProjectId(), template.getProjectId())
                || !sameNullable(task.getWorkspaceId(), template.getWorkspaceId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "同步任务与模板归属不一致，拒绝管理动作，taskId=" + task.getId() + ", templateId=" + template.getId());
        }
        return template;
    }

    private boolean canRunAfterPrecheck(SyncTemplateExecutionPrecheckResponse precheck, SyncTask task) {
        if (precheck.canStartExecution()) {
            return true;
        }
        /*
         * REQUIRES_APPROVAL 在当前普通任务链路里只代表“需要在预检查结果中高亮风险并保留审计提示”，
         * 不再要求任务表里的 approvalState=APPROVED。这样可以和创建向导保持一致：页面不展示审批字段，
         * 后端也不能因为兼容列没有人工改写而把用户任务卡死。
         */
        return SyncTemplateExecutionPrecheckSupport.REQUIRES_APPROVAL.equals(precheck.precheckStatus());
    }

    private String resolveCloneName(SyncTask sourceTask, SyncTaskCloneRequest request) {
        String requested = request == null ? null : trimToNull(request.getName());
        if (requested != null) {
            return truncate(requested, 128);
        }
        String base = truncate(sourceTask.getName() + "-copy-" + LocalDateTime.now().format(CLONE_SUFFIX_FORMATTER), 128);
        String candidate = base;
        int suffix = 1;
        while (taskNameExists(sourceTask.getTenantId(), sourceTask.getProjectId(), candidate)) {
            String marker = "-" + suffix++;
            candidate = truncate(base, Math.max(1, 128 - marker.length())) + marker;
        }
        return candidate;
    }

    private String resolveCloneDescription(SyncTask sourceTask, SyncTaskCloneRequest request) {
        String requested = request == null ? null : trimToNull(request.getDescription());
        return requested == null ? sourceTask.getDescription() : truncate(requested, 512);
    }

    /**
     * 解析克隆任务的分组归属。
     *
     * <p>默认继承来源任务分组，是为了让用户从某个业务域分组里克隆任务时，新任务仍然留在同一个运营视图下。
     * 如果请求显式传入 groupCode，则表示把克隆任务放到另一个分组；此时 groupName 可选，缺省时由分组支撑组件用
     * groupCode 兜底。这里不支持“通过克隆请求传 null 清空分组”，因为 request 为空本身已经表示继承，
     * 清空分组应使用专门的移组接口，语义更清楚，也能独立审计。</p>
     */
    private SyncTaskGroupOperationSupport.TaskGroupAssignment resolveCloneGroup(SyncTask sourceTask,
                                                                                SyncTaskCloneRequest request,
                                                                                SyncActorContext actorContext) {
        String requestedGroupCode = request == null ? null : trimToNull(request.getGroupCode());
        String requestedGroupName = request == null ? null : trimToNull(request.getGroupName());
        if (requestedGroupCode == null && requestedGroupName == null) {
            return taskGroupOperationSupport.resolveAssignmentForTask(
                    sourceTask.getTenantId(),
                    sourceTask.getProjectId(),
                    sourceTask.getWorkspaceId(),
                    sourceTask.getGroupCode(),
                    sourceTask.getGroupName(),
                    actorContext);
        }
        return taskGroupOperationSupport.resolveAssignmentForTask(
                sourceTask.getTenantId(),
                sourceTask.getProjectId(),
                sourceTask.getWorkspaceId(),
                requestedGroupCode,
                requestedGroupName,
                actorContext);
    }

    private Long resolveCloneOwner(SyncTask sourceTask, SyncTaskCloneRequest request, SyncActorContext actorContext) {
        if (request != null && request.getOwnerId() != null) {
            return request.getOwnerId();
        }
        if (sourceTask.getOwnerId() != null) {
            return sourceTask.getOwnerId();
        }
        return actorContext == null ? null : actorContext.actorId();
    }

    private boolean keepScheduleConfig(SyncTaskCloneRequest request) {
        return request != null && Boolean.TRUE.equals(request.getKeepScheduleConfig());
    }

    private void assertTaskNameNotExists(Long tenantId, Long projectId, String name) {
        if (taskNameExists(tenantId, projectId, name)) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "同步任务名称已存在，请修改后再克隆，tenantId=" + tenantId + ", projectId=" + projectId + ", name=" + name);
        }
    }

    private boolean taskNameExists(Long tenantId, Long projectId, String name) {
        LambdaQueryWrapper<SyncTask> wrapper = new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getTenantId, tenantId)
                .eq(SyncTask::getName, name)
                .ne(SyncTask::getCurrentState, SyncTaskState.DELETED.name());
        if (projectId == null) {
            wrapper.isNull(SyncTask::getProjectId);
        } else {
            wrapper.eq(SyncTask::getProjectId, projectId);
        }
        Long count = taskMapper.selectCount(wrapper);
        return count != null && count > 0;
    }

    private Long latestExecutionId(SyncTask task, SyncExecution execution) {
        return execution == null ? task.getLastExecutionId() : execution.getId();
    }

    private String auditPayload(String action, Long executionId, String reason) {
        return "action=" + action
                + ",executionId=" + executionId
                + ",reason=" + reason;
    }

    private String sanitizeReason(SyncTaskLifecycleOperationRequest request, String defaultReason) {
        String reason = request == null ? null : request.getReason();
        if (reason == null || reason.isBlank()) {
            return defaultReason;
        }
        String compact = reason.trim().replaceAll("\\s+", " ");
        String lower = compact.toLowerCase(Locale.ROOT);
        for (String keyword : SENSITIVE_REASON_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return "操作原因包含敏感关键字，已按审计低敏策略脱敏";
            }
        }
        return truncate(compact, 500);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private boolean sameNullable(Long left, Long right) {
        return left == null ? right == null : left.equals(right);
    }
}

/**
 * @Author : Cui
 * @Date: 2026/07/07 19:12
 * @Description DataSmart Govern Backend - SyncTaskDefinitionOperationSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskPublishRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskUpdateRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplateExecutionPrecheckResponse;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncApprovalState;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncMode;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 同步任务定义编辑与发布支撑组件。
 *
 * <p>这个组件专门承接“任务定义管理”能力，避免 {@code DataSyncServiceImpl} 继续膨胀。
 * 从产品语义看，任务定义管理和任务执行管理是两条不同链路：</p>
 * <p>1. 定义管理：编辑名称、负责人、分组、调度配置、发布状态，核心目标是让配置进入正确生命周期；</p>
 * <p>2. 执行管理：run、manual-dispatch、worker claim、checkpoint、complete/fail，核心目标是搬运数据并记录执行事实。</p>
 *
 * <p>把二者分开后，后续 Agent 能更安全地调用控制面工具：Agent 可以先生成草稿、解释预检、请求用户确认发布，
 * 但不能因为“改了一个字段”就绕过审批或调度器直接执行真实数据同步。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncTaskDefinitionOperationSupport {

    private static final int APPROVAL_FACT_ID_MAX_LENGTH = 160;

    /**
     * 支持的任务优先级。
     *
     * <p>当前只做任务定义保存，不改变调度器排序；但提前收敛枚举值能避免前端、导入文件和 Agent
     * 写入各种自由文本，后续接入队列优先级时不必清洗历史脏值。</p>
     */
    private static final Set<String> ALLOWED_PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "URGENT");

    /**
     * 会进入审计摘要的原因文本敏感词。
     *
     * <p>编辑原因、发布原因都属于低敏审计摘要，不应成为 SQL、凭据、样本数据或 prompt 的二次泄露渠道。
     * 这里做基础兜底；真正的商业产品还应在前端表单和审计平台继续做数据分类提示。</p>
     */
    private static final Set<String> SENSITIVE_REASON_KEYWORDS = Set.of(
            "password", "token", "secret", "credential", "access_key", "private_key",
            "jdbc:", "sql", "prompt", "payload", "sample", "密码", "密钥", "令牌", "凭据", "样本"
    );

    private final SyncTaskMapper taskMapper;
    private final SyncTaskStateMachineSupport stateMachineSupport;
    private final SyncTaskGroupOperationSupport taskGroupOperationSupport;
    private final SyncTaskScheduleConfigSupport scheduleConfigSupport;
    private final SyncTemplateValidationSupport templateValidationSupport;
    private final SyncTemplateExecutionPrecheckSupport templateExecutionPrecheckSupport;
    private final SyncQuerySupport querySupport;
    private final SyncAuditSupport auditSupport;

    /**
     * 编辑同步任务定义。
     *
     * <p>业务前置条件：</p>
     * <p>1. 任务必须已经通过 Service 层读取，因此租户、项目、SELF 范围已经被校验；</p>
     * <p>2. 任务不能处于 QUEUED/RUNNING/RETRYING 等活跃执行窗口；</p>
     * <p>3. 如果编辑了 scheduleConfig，任务会退回 DRAFT，并关闭 scheduleEnabled 与 nextFireTime。</p>
     *
     * <p>为什么调度配置变更要退回 DRAFT：</p>
     * <p>周期任务的 scheduleConfig 决定“什么时候自动搬运数据”。如果用户把每天凌晨改成每 5 分钟，
     * 这不是普通展示字段变化，而是会显著影响源端压力、目标端写入和客户计费的运行策略变化。
     * 因此编辑阶段只保存草稿，发布阶段再重新预检、审批和计算 nextFireTime。</p>
     */
    public SyncTask updateTaskDefinition(SyncTask task,
                                         SyncTemplate template,
                                         SyncTaskUpdateRequest request,
                                         SyncActorContext actorContext) {
        SyncTaskUpdateRequest safeRequest = request == null ? new SyncTaskUpdateRequest() : request;
        stateMachineSupport.assertCanEditDefinition(task.getCurrentState());

        String name = resolveName(task.getName(), safeRequest.getName());
        String description = safeRequest.getDescription() == null
                ? task.getDescription()
                : querySupport.trimToNull(safeRequest.getDescription());
        String priority = resolvePriority(task.getPriority(), safeRequest.getPriority());
        Long ownerId = resolveOwnerId(task.getOwnerId(), safeRequest.getOwnerId());
        SyncTaskGroupOperationSupport.TaskGroupAssignment groupAssignment =
                resolveGroupAssignment(task, safeRequest, actorContext);
        ScheduleEditResult scheduleEditResult = resolveScheduleEdit(task, template, safeRequest);
        String runMode = resolveRunMode(task.getRunMode(), safeRequest.getRunMode());

        /*
         * 如果调度配置发生变化，任务退回 DRAFT 并清理人工介入标记。
         * 这里没有自动发布，是为了保留“编辑 -> 发布 -> 执行/等待调度”的清晰用户心智和审计边界。
         */
        String targetState = scheduleEditResult.scheduleChanged()
                ? SyncTaskState.DRAFT.name()
                : task.getCurrentState();
        Boolean attentionRequired = scheduleEditResult.scheduleChanged() ? Boolean.FALSE : task.getAttentionRequired();
        String attentionReason = scheduleEditResult.scheduleChanged() ? null : task.getAttentionReason();
        String triggerType = scheduleEditResult.scheduleChanged()
                ? SyncTriggerType.MANUAL.name()
                : task.getTriggerType();

        int updated = taskMapper.updateTaskDefinition(
                task.getId(),
                name,
                description,
                priority,
                ownerId,
                groupAssignment.groupCode(),
                groupAssignment.groupName(),
                scheduleEditResult.scheduleConfig(),
                scheduleEditResult.scheduleEnabled(),
                scheduleEditResult.nextFireTime(),
                runMode,
                triggerType,
                targetState,
                task.getApprovalState(),
                attentionRequired,
                attentionReason);
        if (updated == 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "同步任务定义编辑失败，taskId=" + task.getId());
        }
        auditSupport.saveAudit(task.getTenantId(), task.getId(), task.getLastExecutionId(),
                SyncAuditActionType.UPDATE_TASK, actorContext,
                buildUpdateAuditPayload(task, targetState, groupAssignment, scheduleEditResult, safeRequest));
        return reloadTask(task.getId());
    }

    /**
     * 发布同步任务定义。
     *
     * <p>发布会重新执行模板校验和执行前预检查，而不是相信任务创建时的旧结果。
     * 原因是模板、连接器能力矩阵、runner 支持边界或审批规则都可能在任务草稿保存后发生变化。
     * 只有发布时重新检查，才能避免旧草稿在系统升级后绕过新的安全规则。</p>
     */
    public SyncTaskOperationResult publishTaskDefinition(SyncTask task,
                                                         SyncTemplate template,
                                                         SyncTaskPublishRequest request,
                                                         SyncActorContext actorContext) {
        SyncTaskPublishRequest safeRequest = request == null ? new SyncTaskPublishRequest() : request;
        stateMachineSupport.assertCanPublishDefinition(task.getCurrentState());
        templateValidationSupport.validateTemplate(template);
        SyncTemplateExecutionPrecheckResponse precheck = templateExecutionPrecheckSupport.precheck(template);
        if (!precheck.canCreateTaskDraft()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "同步任务发布前预检查未通过，precheckStatus=" + precheck.precheckStatus()
                            + "，issueCodes=" + precheck.issueCodes()
                            + "，recommendedActions=" + precheck.recommendedActions());
        }

        String approvalState = resolveApprovalStateForPublish(precheck, safeRequest, actorContext);
        boolean scheduled = resolveScheduledOnPublish(template, task, safeRequest);
        String targetState = resolvePublishedTaskState(approvalState, scheduled);
        Boolean scheduleEnabled = scheduled && !SyncApprovalState.PENDING.name().equals(approvalState);
        LocalDateTime nextFireTime = scheduleEnabled
                ? scheduleConfigSupport.initialNextFireTime(task.getScheduleConfig(), LocalDateTime.now())
                : null;
        String triggerType = scheduled ? SyncTriggerType.SCHEDULED.name() : SyncTriggerType.MANUAL.name();
        String runMode = scheduled
                ? "SCHEDULED"
                : querySupport.defaultText(task.getRunMode(), "TEMPLATE").toUpperCase(Locale.ROOT);

        int updated = taskMapper.updateTaskDefinition(
                task.getId(),
                task.getName(),
                task.getDescription(),
                task.getPriority(),
                task.getOwnerId(),
                task.getGroupCode(),
                task.getGroupName(),
                task.getScheduleConfig(),
                scheduleEnabled,
                nextFireTime,
                runMode,
                triggerType,
                targetState,
                approvalState,
                Boolean.FALSE,
                null);
        if (updated == 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "同步任务发布失败，taskId=" + task.getId());
        }
        auditSupport.saveAudit(task.getTenantId(), task.getId(), task.getLastExecutionId(),
                SyncAuditActionType.PUBLISH_TASK, actorContext,
                buildPublishAuditPayload(task, precheck, approvalState, targetState, scheduleEnabled, nextFireTime, safeRequest));
        return new SyncTaskOperationResult(task.getId(), targetState, "同步任务已发布，当前状态=" + targetState);
    }

    private String resolveName(String currentName, String requestedName) {
        if (requestedName == null) {
            return currentName;
        }
        String name = querySupport.trimToNull(requestedName);
        if (name == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "同步任务名称不能为空");
        }
        return querySupport.truncate(name, 160);
    }

    private String resolvePriority(String currentPriority, String requestedPriority) {
        if (requestedPriority == null) {
            return currentPriority;
        }
        String priority = querySupport.trimToNull(requestedPriority);
        if (priority == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "同步任务优先级不能为空");
        }
        String normalized = priority.toUpperCase(Locale.ROOT);
        if (!ALLOWED_PRIORITIES.contains(normalized)) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "同步任务优先级仅支持 LOW、MEDIUM、HIGH、URGENT，当前值=" + requestedPriority);
        }
        return normalized;
    }

    private Long resolveOwnerId(Long currentOwnerId, Long requestedOwnerId) {
        if (requestedOwnerId == null) {
            return currentOwnerId;
        }
        if (requestedOwnerId <= 0) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "同步任务负责人 ID 必须为正数");
        }
        return requestedOwnerId;
    }

    private SyncTaskGroupOperationSupport.TaskGroupAssignment resolveGroupAssignment(SyncTask task,
                                                                                     SyncTaskUpdateRequest request,
                                                                                     SyncActorContext actorContext) {
        if (Boolean.TRUE.equals(request.getClearGroup())) {
            /*
             * 早期“清空分组”会把 group_code/group_name 写成 NULL，但当前产品已经把 DEFAULT/默认分组建模为
             * 一个真实资源。编辑页点击“清空分组”时，用户真正想表达的是“不要放在当前业务分组里”，而不是让任务
             * 变成前端分组树不可见的游离记录。因此这里统一回落到默认分组，和删除分组后的任务迁移规则保持一致。
             */
            return taskGroupOperationSupport.resolveAssignmentForTask(
                    task.getTenantId(),
                    task.getProjectId(),
                    task.getWorkspaceId(),
                    null,
                    null,
                    actorContext);
        }
        if (request.getGroupCode() != null || request.getGroupName() != null) {
            return taskGroupOperationSupport.resolveAssignmentForTask(
                    task.getTenantId(),
                    task.getProjectId(),
                    task.getWorkspaceId(),
                    request.getGroupCode(),
                    request.getGroupName(),
                    actorContext);
        }
        return new SyncTaskGroupOperationSupport.TaskGroupAssignment(task.getGroupCode(), task.getGroupName());
    }

    private ScheduleEditResult resolveScheduleEdit(SyncTask task,
                                                   SyncTemplate template,
                                                   SyncTaskUpdateRequest request) {
        String currentScheduleConfig = querySupport.trimToNull(task.getScheduleConfig());
        if (Boolean.TRUE.equals(request.getClearScheduleConfig())) {
            boolean changed = currentScheduleConfig != null
                    || Boolean.TRUE.equals(task.getScheduleEnabled())
                    || task.getNextFireTime() != null;
            return new ScheduleEditResult(null, Boolean.FALSE, null, changed);
        }
        if (request.getScheduleConfig() == null) {
            return new ScheduleEditResult(currentScheduleConfig, task.getScheduleEnabled(), task.getNextFireTime(), false);
        }
        String requestedScheduleConfig = querySupport.trimToNull(request.getScheduleConfig());
        if (requestedScheduleConfig == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "调度配置不能传空白字符串；如需清空请设置 clearScheduleConfig=true");
        }
        validateScheduleConfigAllowed(template, requestedScheduleConfig);
        boolean changed = !Objects.equals(currentScheduleConfig, requestedScheduleConfig)
                || Boolean.TRUE.equals(task.getScheduleEnabled())
                || task.getNextFireTime() != null;
        return new ScheduleEditResult(requestedScheduleConfig, Boolean.FALSE, null, changed);
    }

    private String resolveRunMode(String currentRunMode, String requestedRunMode) {
        if (requestedRunMode == null) {
            return currentRunMode;
        }
        String runMode = querySupport.trimToNull(requestedRunMode);
        if (runMode == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "同步任务运行模式不能为空");
        }
        return querySupport.truncate(runMode.toUpperCase(Locale.ROOT), 64);
    }

    private void validateScheduleConfigAllowed(SyncTemplate template, String scheduleConfig) {
        if (!scheduleConfigSupport.hasScheduleConfig(scheduleConfig)) {
            return;
        }
        String syncMode = normalizeCode(template.getSyncMode());
        if (!SyncMode.FULL.name().equals(syncMode) && !SyncMode.SCHEDULED_BATCH.name().equals(syncMode)) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "当前自动调度仅支持 FULL 定期全量和 SCHEDULED_BATCH 定期批量，syncMode=" + syncMode);
        }
        scheduleConfigSupport.parseRequired(scheduleConfig);
    }

    private String resolveApprovalStateForPublish(SyncTemplateExecutionPrecheckResponse precheck,
                                                  SyncTaskPublishRequest request,
                                                  SyncActorContext actorContext) {
        if (!precheck.approvalRequired()) {
            if (Boolean.TRUE.equals(request.getApprovalConfirmed()) || hasText(request.getApprovalFactId())) {
                assertTrustedApprovalSubmitter(actorContext, "SUBMIT_UNUSED_APPROVAL_FACT_FOR_PUBLISH");
                validateApprovalFactId(request.getApprovalFactId(), false);
            }
            return SyncApprovalState.NOT_REQUIRED.name();
        }
        if (Boolean.TRUE.equals(request.getApprovalConfirmed())) {
            assertTrustedApprovalSubmitter(actorContext, "CONFIRM_SYNC_TASK_PUBLISH_APPROVAL");
            validateApprovalFactId(request.getApprovalFactId(), true);
            return SyncApprovalState.APPROVED.name();
        }
        if (hasText(request.getApprovalFactId())) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "已提供审批事实 ID，但 approvalConfirmed 未显式为 true；高风险同步任务不能只凭事实引用发布");
        }
        return SyncApprovalState.PENDING.name();
    }

    private boolean resolveScheduledOnPublish(SyncTemplate template,
                                              SyncTask task,
                                              SyncTaskPublishRequest request) {
        String scheduleConfig = querySupport.trimToNull(task.getScheduleConfig());
        String syncMode = normalizeCode(template.getSyncMode());
        boolean hasScheduleConfig = scheduleConfigSupport.hasScheduleConfig(scheduleConfig);
        boolean scheduledBatch = SyncMode.SCHEDULED_BATCH.name().equals(syncMode);
        if (scheduledBatch && !hasScheduleConfig) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "SCHEDULED_BATCH 定时批量任务发布前必须配置 scheduleConfig");
        }
        if (Boolean.TRUE.equals(request.getEnableSchedule()) && !hasScheduleConfig) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "启用自动调度前必须先配置 scheduleConfig");
        }
        if (hasScheduleConfig) {
            validateScheduleConfigAllowed(template, scheduleConfig);
        }
        /*
         * enableSchedule 为空时采用“有 scheduleConfig 就启用”的产品默认；显式 false 表示保留配置但暂不进入 SCHEDULED。
         */
        return hasScheduleConfig && !Boolean.FALSE.equals(request.getEnableSchedule());
    }

    private String resolvePublishedTaskState(String approvalState, boolean scheduled) {
        if (SyncApprovalState.PENDING.name().equals(approvalState)) {
            return SyncTaskState.PENDING_APPROVAL.name();
        }
        return scheduled ? SyncTaskState.SCHEDULED.name() : SyncTaskState.CONFIGURED.name();
    }

    private void assertTrustedApprovalSubmitter(SyncActorContext actorContext, String operation) {
        String role = normalizeRole(actorContext);
        if (!"PLATFORM_ADMINISTRATOR".equals(role)
                && !"TENANT_ADMINISTRATOR".equals(role)
                && !"OPERATOR".equals(role)
                && !"SERVICE_ACCOUNT".equals(role)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "当前角色无权提交 data-sync 审批确认事实，operation=" + operation + ", role=" + role);
        }
    }

    private void validateApprovalFactId(String approvalFactId, boolean required) {
        String factId = querySupport.trimToNull(approvalFactId);
        if (factId == null) {
            if (required) {
                throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                        "高风险同步任务发布确认审批时必须提供低敏 approvalFactId");
            }
            return;
        }
        if (factId.length() > APPROVAL_FACT_ID_MAX_LENGTH
                || !factId.matches("[A-Za-z0-9:_./-]+")) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "approvalFactId 只能是低敏短引用，允许字母、数字、冒号、下划线、短横线、点和斜杠，且长度不超过 "
                            + APPROVAL_FACT_ID_MAX_LENGTH);
        }
    }

    private SyncTask reloadTask(Long taskId) {
        SyncTask reloaded = taskMapper.selectById(taskId);
        if (reloaded == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "同步任务不存在: " + taskId);
        }
        return reloaded;
    }

    private String buildUpdateAuditPayload(SyncTask oldTask,
                                           String targetState,
                                           SyncTaskGroupOperationSupport.TaskGroupAssignment groupAssignment,
                                           ScheduleEditResult scheduleEditResult,
                                           SyncTaskUpdateRequest request) {
        return "taskId=" + oldTask.getId()
                + ",oldState=" + oldTask.getCurrentState()
                + ",newState=" + targetState
                + ",scheduleChanged=" + scheduleEditResult.scheduleChanged()
                + ",scheduleEnabled=" + scheduleEditResult.scheduleEnabled()
                + ",nextFireTime=" + scheduleEditResult.nextFireTime()
                + ",groupCode=" + groupAssignment.groupCode()
                + ",reason=" + sanitizeReason(request.getReason(), "用户编辑同步任务定义");
    }

    private String buildPublishAuditPayload(SyncTask task,
                                            SyncTemplateExecutionPrecheckResponse precheck,
                                            String approvalState,
                                            String targetState,
                                            Boolean scheduleEnabled,
                                            LocalDateTime nextFireTime,
                                            SyncTaskPublishRequest request) {
        String payload = "taskId=" + task.getId()
                + ",templateId=" + task.getTemplateId()
                + ",precheckStatus=" + precheck.precheckStatus()
                + ",approvalRequired=" + precheck.approvalRequired()
                + ",approvalState=" + approvalState
                + ",newState=" + targetState
                + ",scheduleEnabled=" + scheduleEnabled
                + ",nextFireTime=" + nextFireTime
                + ",reason=" + sanitizeReason(request.getReason(), "用户发布同步任务定义");
        String approvalFactId = querySupport.trimToNull(request.getApprovalFactId());
        if (approvalFactId != null) {
            payload = payload + ",approvalFactId=" + querySupport.truncate(approvalFactId, APPROVAL_FACT_ID_MAX_LENGTH);
        }
        return payload;
    }

    private String sanitizeReason(String reason, String defaultReason) {
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
        return querySupport.truncate(compact, 500);
    }

    private String normalizeCode(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeRole(SyncActorContext actorContext) {
        String role = actorContext == null ? null : actorContext.actorRole();
        return role == null || role.isBlank() ? "USER" : role.trim().toUpperCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 编辑调度配置后的派生结果。
     *
     * @param scheduleConfig 最终写入任务表的调度配置；为空表示无调度配置
     * @param scheduleEnabled 编辑后是否启用自动调度；编辑阶段一旦修改调度配置必须关闭
     * @param nextFireTime 下一次触发时间；编辑阶段一旦修改调度配置必须清空
     * @param scheduleChanged 本次请求是否改变了调度语义
     */
    private record ScheduleEditResult(String scheduleConfig,
                                      Boolean scheduleEnabled,
                                      LocalDateTime nextFireTime,
                                      boolean scheduleChanged) {
    }
}

/**
 * @Author : Cui
 * @Date: 2026/05/08 22:28
 * @Description DataSmart Govern Backend - DataSyncAttentionOperationServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.impl;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAttentionOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAttentionOperationResult;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncIncidentRecord;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncIncidentRecordMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.service.DataSyncAttentionOperationService;
import com.czh.datasmart.govern.datasync.service.support.SyncAuditSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncDataScopeSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionCreationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncOperatorPermissionSupport;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * data-sync 人工介入任务运营服务实现。
 *
 * <p>人工介入能力解决的是“自动化已经无法安全推进后，人怎么接手”的问题。
 * 在生产系统里，仅把任务标成 FAILED 或 AWAITING_OPERATOR_ACTION 还不够：
 * 运营人员需要确认问题、记录排障、创建事故、修复后重跑，或者取消/归档不再需要的任务。
 *
 * <p>本实现先提供模块内闭环，权限策略采取本地兜底：
 * PLATFORM_ADMINISTRATOR、TENANT_ADMINISTRATOR、OPERATOR、SERVICE_ACCOUNT 可以处理。
 * 后续接入 permission-admin 后，可以把这里的角色判断替换成远程策略决策或网关前置授权。
 */
@Service
@RequiredArgsConstructor
public class DataSyncAttentionOperationServiceImpl implements DataSyncAttentionOperationService {

    private final SyncTaskMapper taskMapper;
    private final SyncIncidentRecordMapper incidentRecordMapper;
    private final SyncDataScopeSupport dataScopeSupport;
    private final SyncExecutionCreationSupport executionCreationSupport;
    private final SyncAuditSupport auditSupport;
    private final SyncOperatorPermissionSupport operatorPermissionSupport;

    /**
     * 确认人工介入任务已被接手。
     *
     * <p>acknowledge 不改变任务的等待人工处理状态，它只更新原因摘要并写审计。
     * 这样做可以支持“有人已经看到并开始排查”的运营语义，避免多个同事重复处理同一个问题。
     */
    @Override
    @Transactional
    public SyncAttentionOperationResult acknowledge(Long taskId,
                                                    SyncAttentionOperationRequest request,
                                                    SyncActorContext actorContext) {
        SyncTask task = requireAttentionTask(taskId, actorContext);
        String reason = "已确认人工介入任务，处理备注：" + defaultText(request == null ? null : request.getNote(), "暂无备注");
        taskMapper.markAttentionAcknowledged(task.getId(), truncate(reason, 1000));
        audit(task, SyncAuditActionType.ACKNOWLEDGE_ATTENTION, actorContext, reason);
        return result(task.getId(), SyncTaskState.AWAITING_OPERATOR_ACTION.name(), true,
                "ACKNOWLEDGE", task.getLastExecutionId(), null, null, "人工介入任务已确认");
    }

    /**
     * 标记人工介入问题已解决。
     *
     * <p>resolve 会把任务从 AWAITING_OPERATOR_ACTION 回到 CONFIGURED。
     * 它不自动创建 execution，适合“先修配置、后由用户或调度器重新运行”的场景。
     */
    @Override
    @Transactional
    public SyncAttentionOperationResult resolve(Long taskId,
                                                SyncAttentionOperationRequest request,
                                                SyncActorContext actorContext) {
        SyncTask task = requireAttentionTask(taskId, actorContext);
        taskMapper.markAttentionResolved(task.getId());
        String note = defaultText(request == null ? null : request.getNote(), "人工确认问题已解决，任务回到可运行配置态");
        audit(task, SyncAuditActionType.RESOLVE_ATTENTION, actorContext, note);
        return result(task.getId(), SyncTaskState.CONFIGURED.name(), false,
                "RESOLVE", task.getLastExecutionId(), null, null, "人工介入问题已解决，任务已回到 CONFIGURED");
    }

    /**
     * 人工介入后立即重跑任务。
     *
     * <p>rerun 会创建一条新的 QUEUED execution，并清空人工介入标记。
     * 这比直接放开普通 `/run` 更安全，因为只有具备运营权限的操作者才能从人工介入态直接重跑。
     */
    @Override
    @Transactional
    public SyncAttentionOperationResult rerun(Long taskId,
                                             SyncAttentionOperationRequest request,
                                             SyncActorContext actorContext) {
        SyncTask task = requireAttentionTask(taskId, actorContext);
        SyncExecution execution = executionCreationSupport.createQueuedExecution(task, actorContext);
        taskMapper.markAttentionRerunQueued(task.getId(), execution.getId());
        String note = defaultText(request == null ? null : request.getNote(), "人工处理后立即重跑同步任务");
        auditSupport.saveAudit(task.getTenantId(), task.getId(), execution.getId(), SyncAuditActionType.RERUN_ATTENTION_TASK,
                actorContext, "rerunAttentionTask,executionId=" + execution.getId() + ",note=" + truncate(note, 300));
        return result(task.getId(), SyncTaskState.QUEUED.name(), false,
                "RERUN", execution.getId(), null, null, "人工处理后已创建新的执行记录并进入队列");
    }

    /**
     * 取消人工介入任务。
     *
     * <p>cancel 适用于业务已经不再需要同步、源目标关系废弃、或问题短期无法解决且不应继续占用运营视线的场景。
     */
    @Override
    @Transactional
    public SyncAttentionOperationResult cancel(Long taskId,
                                              SyncAttentionOperationRequest request,
                                              SyncActorContext actorContext) {
        SyncTask task = requireAttentionTask(taskId, actorContext);
        taskMapper.closeAttentionTask(task.getId(), SyncTaskState.CANCELLED.name());
        String note = defaultText(request == null ? null : request.getNote(), "人工取消同步任务");
        audit(task, SyncAuditActionType.CANCEL_ATTENTION_TASK, actorContext, note);
        return result(task.getId(), SyncTaskState.CANCELLED.name(), false,
                "CANCEL", task.getLastExecutionId(), null, null, "人工介入任务已取消");
    }

    /**
     * 归档人工介入任务。
     *
     * <p>archive 适用于历史任务、迁移完成后的旧链路或已经完成复盘但不再需要日常运营的任务。
     */
    @Override
    @Transactional
    public SyncAttentionOperationResult archive(Long taskId,
                                               SyncAttentionOperationRequest request,
                                               SyncActorContext actorContext) {
        SyncTask task = requireAttentionTask(taskId, actorContext);
        taskMapper.closeAttentionTask(task.getId(), SyncTaskState.ARCHIVED.name());
        String note = defaultText(request == null ? null : request.getNote(), "人工归档同步任务");
        audit(task, SyncAuditActionType.ARCHIVE_ATTENTION_TASK, actorContext, note);
        return result(task.getId(), SyncTaskState.ARCHIVED.name(), false,
                "ARCHIVE", task.getLastExecutionId(), null, null, "人工介入任务已归档");
    }

    /**
     * 为人工介入任务创建事故记录。
     *
     * <p>createIncident 不会关闭人工介入状态，因为创建事故只是“开始跟踪问题”，不是“问题已经解决”。
     * 事故记录可以承载严重级别、类型、标题和描述，后续可对接通知、工单、SLA 和事故复盘。
     */
    @Override
    @Transactional
    public SyncAttentionOperationResult createIncident(Long taskId,
                                                       SyncAttentionOperationRequest request,
                                                       SyncActorContext actorContext) {
        SyncTask task = requireAttentionTask(taskId, actorContext);
        SyncIncidentRecord incident = new SyncIncidentRecord();
        incident.setTenantId(task.getTenantId());
        incident.setProjectId(task.getProjectId());
        incident.setWorkspaceId(task.getWorkspaceId());
        incident.setSyncTaskId(task.getId());
        incident.setExecutionId(task.getLastExecutionId());
        incident.setIncidentType(normalizeCode(defaultText(request == null ? null : request.getIncidentType(), "EXECUTOR_UNSTABLE")));
        incident.setSeverity(normalizeCode(defaultText(request == null ? null : request.getSeverity(), "P3")));
        incident.setIncidentStatus("OPEN");
        incident.setTitle(truncate(defaultText(request == null ? null : request.getTitle(), "同步任务需要人工介入"), 256));
        incident.setDescription(truncate(defaultText(request == null ? null : request.getDescription(),
                defaultText(task.getAttentionReason(), "同步任务进入人工介入状态，需要运营人员排查")), 2000));
        incident.setOperatorId(actorContext == null ? null : actorContext.actorId());
        incident.setOperatorRole(actorContext == null ? null : actorContext.actorRole());
        incident.setCreateTime(LocalDateTime.now());
        incident.setUpdateTime(LocalDateTime.now());
        incidentRecordMapper.insert(incident);
        auditSupport.saveAudit(task.getTenantId(), task.getId(), task.getLastExecutionId(), SyncAuditActionType.CREATE_INCIDENT,
                actorContext, "incidentId=" + incident.getId()
                        + ",type=" + incident.getIncidentType()
                        + ",severity=" + incident.getSeverity());
        return result(task.getId(), SyncTaskState.AWAITING_OPERATOR_ACTION.name(), true,
                "CREATE_INCIDENT", task.getLastExecutionId(), incident.getId(), incident.getIncidentStatus(),
                "人工介入任务已创建事故记录");
    }

    private SyncTask requireAttentionTask(Long taskId, SyncActorContext actorContext) {
        operatorPermissionSupport.assertOperator(actorContext, "ATTENTION_OPERATION");
        SyncTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "同步任务不存在: " + taskId);
        }
        dataScopeSupport.validateTenantReadable(task.getTenantId(), actorContext);
        boolean waitingAction = SyncTaskState.AWAITING_OPERATOR_ACTION.name().equals(task.getCurrentState());
        boolean attentionRequired = Boolean.TRUE.equals(task.getAttentionRequired());
        if (!waitingAction && !attentionRequired) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "当前任务不处于人工介入状态，不能执行人工处理动作，taskId=" + taskId + ",state=" + task.getCurrentState());
        }
        return task;
    }

    private void audit(SyncTask task,
                       SyncAuditActionType actionType,
                       SyncActorContext actorContext,
                       String note) {
        auditSupport.saveAudit(task.getTenantId(), task.getId(), task.getLastExecutionId(), actionType,
                actorContext, "note=" + truncate(note, 500));
    }

    private SyncAttentionOperationResult result(Long taskId,
                                                String taskState,
                                                Boolean attentionRequired,
                                                String operation,
                                                Long executionId,
                                                Long incidentId,
                                                String incidentStatus,
                                                String message) {
        return new SyncAttentionOperationResult(taskId, taskState, attentionRequired,
                operation, executionId, incidentId, incidentStatus, message);
    }

    private String defaultText(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private String normalizeCode(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}

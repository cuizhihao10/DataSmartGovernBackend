/**
 * @Author : Cui
 * @Date: 2026/05/08 22:18
 * @Description DataSmart Govern Backend - SyncExecutionCreationSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 同步执行记录创建支撑组件。
 *
 * <p>execution 是 data-sync 的运行历史锚点。普通手动运行、人工介入后重跑、未来的定时调度、
 * 历史补数和回放都需要创建 execution。如果每个服务都手写一遍初始化逻辑，就容易出现 executionNo、
 * 默认计数字段、触发人和审计记录不一致的问题。
 *
 * <p>因此这里把“创建待执行记录”集中为一个组件，业务服务只需要表达“我现在要发起一次运行”，
 * 不需要关心 execution 默认字段如何填充。
 */
@Component
@RequiredArgsConstructor
public class SyncExecutionCreationSupport {

    private final SyncExecutionMapper executionMapper;
    private final SyncAuditSupport auditSupport;

    /**
     * 创建一条待执行同步记录。
     *
     * @param task 当前同步任务，提供租户、任务 ID 和运营上下文
     * @param actorContext 当前操作者，写入 triggeredBy 并进入审计记录
     * @return 已持久化的 QUEUED execution
     */
    public SyncExecution createQueuedExecution(SyncTask task, SyncActorContext actorContext) {
        return createQueuedExecution(task, actorContext, SyncTriggerType.MANUAL);
    }

    /**
     * 创建指定触发类型的待执行记录。
     *
     * <p>普通手动运行、恢复和重试可以继续使用默认 MANUAL；
     * replay/backfill 则必须写入专属 triggerType，否则执行历史、审计统计和未来 worker 调度都无法区分“普通运行”和“恢复性运行”。
     */
    public SyncExecution createQueuedExecution(SyncTask task,
                                               SyncActorContext actorContext,
                                               SyncTriggerType triggerType) {
        SyncExecution execution = new SyncExecution();
        execution.setTenantId(task.getTenantId());
        execution.setProjectId(task.getProjectId());
        execution.setWorkspaceId(task.getWorkspaceId());
        execution.setSyncTaskId(task.getId());
        execution.setExecutionNo(nextExecutionNo(task.getId()));
        execution.setExecutionState(SyncExecutionState.QUEUED.name());
        execution.setTriggerType(triggerType == null ? SyncTriggerType.MANUAL.name() : triggerType.name());
        execution.setQueuedAt(LocalDateTime.now());
        execution.setRecordsRead(0L);
        execution.setRecordsWritten(0L);
        execution.setFailedRecordCount(0L);
        execution.setTriggeredBy(actorContext == null ? null : actorContext.actorId());
        execution.setCreateTime(LocalDateTime.now());
        execution.setUpdateTime(LocalDateTime.now());
        executionMapper.insert(execution);
        auditSupport.saveAudit(task.getTenantId(), task.getId(), execution.getId(), SyncAuditActionType.CREATE_EXECUTION,
                actorContext, "executionNo=" + execution.getExecutionNo()
                        + ",state=" + execution.getExecutionState()
                        + ",triggerType=" + execution.getTriggerType());
        return execution;
    }

    /**
     * 计算同一同步任务下的下一次执行序号。
     *
     * <p>当前使用 count 方式便于理解和本地验证。后续如果出现高并发重跑或大规模调度，
     * 可以升级为任务表冗余 next_execution_no、数据库序列或号段服务，但这不影响外部 execution 契约。
     */
    private long nextExecutionNo(Long taskId) {
        Long count = executionMapper.selectCount(new LambdaQueryWrapper<SyncExecution>()
                .eq(SyncExecution::getSyncTaskId, taskId));
        return (count == null ? 0L : count) + 1L;
    }
}

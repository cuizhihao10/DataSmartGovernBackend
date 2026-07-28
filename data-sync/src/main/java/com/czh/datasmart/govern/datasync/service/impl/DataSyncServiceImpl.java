/**
 * @Author : Cui
 * @Date: 2026/05/07 21:31
 * @Description DataSmart Govern Backend - DataSyncServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAuditQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncCheckpointQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordReplayRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordReplayResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordQuarantineRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordQuarantineResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncRecoveryCasePublishRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncRecoveryCasePublishResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncErrorSampleQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCheckpointRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionLogQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionStartRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionDiagnosisResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectExecutionQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectExecutionView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskBatchOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskBatchOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskCloneRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskCreateWizardDraftSaveRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskCreateWizardDraftSaveResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskExportFile;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskFieldMappingSuggestionRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskFieldMappingSuggestionResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupCreateRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupSummary;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupTreeNode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskGroupUpdateRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportOptions;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskLifecycleOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskMetadataDiscoveryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskMetadataDiscoveryResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskPublishRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskRecoveryOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskUpdateRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskExecutionPrecheckResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskDefinitionExecutionPrecheckResponse;
import com.czh.datasmart.govern.datasync.entity.SyncAuditRecord;
import com.czh.datasmart.govern.datasync.entity.SyncCheckpoint;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionLog;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskGroup;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import com.czh.datasmart.govern.datasync.mapper.SyncAuditRecordMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncCheckpointMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncErrorSampleMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionLogMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskDefinitionMapper;
import com.czh.datasmart.govern.datasync.service.DataSyncService;
import com.czh.datasmart.govern.datasync.service.support.SyncAuditSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncAgentExecutionDiagnosisSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncDataScopeSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncDataVisibility;
import com.czh.datasmart.govern.datasync.service.support.SyncDirtyRecordReplaySupport;
import com.czh.datasmart.govern.datasync.service.support.SyncDirtyRecordQuarantineSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionCreationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncExecutionLifecycleSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncObjectExecutionOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncQuerySupport;
import com.czh.datasmart.govern.datasync.service.support.SyncRecoveryCasePublishSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskBatchOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskCreateWizardDraftSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskDefinitionOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskDefinitionExchangeSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskLifecycleOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskGroupOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskManagementOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskMetadataConfigurationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskRecoveryOperationSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskStateMachineSupport;
import com.czh.datasmart.govern.datasync.service.support.SyncTaskDefinitionExecutionPrecheckSupport;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 数据同步服务实现。
 *
 * <p>当前实现以任务聚合为边界：
 * 1. 一对一任务定义保存执行配置；
 * 2. 任务主表保存可运营状态；
 * 3. runTask 只把任务推进到 QUEUED，执行器、checkpoint 和吞吐控制由独立组件负责。
 *
 * <p>这样做能避免一开始就把连接器读写、任务状态、审计、checkpoint 全部耦合在一个大 Impl 里。
 */
@Service
@RequiredArgsConstructor
public class DataSyncServiceImpl implements DataSyncService {

    private final SyncTaskDefinitionMapper taskDefinitionMapper;
    private final SyncTaskMapper taskMapper;
    private final SyncExecutionMapper executionMapper;
    private final SyncExecutionLogMapper executionLogMapper;
    private final SyncCheckpointMapper checkpointMapper;
    private final SyncErrorSampleMapper errorSampleMapper;
    private final SyncAuditRecordMapper auditRecordMapper;
    private final SyncDataScopeSupport dataScopeSupport;
    private final SyncQuerySupport querySupport;
    private final SyncTaskStateMachineSupport stateMachineSupport;
    private final SyncAuditSupport auditSupport;
    private final SyncExecutionLifecycleSupport executionLifecycleSupport;
    private final SyncExecutionCreationSupport executionCreationSupport;
    private final SyncTaskBatchOperationSupport taskBatchOperationSupport;
    private final SyncTaskDefinitionOperationSupport taskDefinitionOperationSupport;
    private final SyncTaskDefinitionExchangeSupport taskDefinitionExchangeSupport;
    private final SyncTaskLifecycleOperationSupport taskLifecycleOperationSupport;
    private final SyncTaskGroupOperationSupport taskGroupOperationSupport;
    private final SyncTaskManagementOperationSupport taskManagementOperationSupport;
    private final SyncTaskMetadataConfigurationSupport taskMetadataConfigurationSupport;
    private final SyncTaskRecoveryOperationSupport taskRecoveryOperationSupport;
    private final SyncTaskDefinitionExecutionPrecheckSupport taskDefinitionExecutionPrecheckSupport;
    private final SyncObjectExecutionOperationSupport objectExecutionOperationSupport;
    private final SyncDirtyRecordReplaySupport dirtyRecordReplaySupport;
    private final SyncDirtyRecordQuarantineSupport dirtyRecordQuarantineSupport;
    private final SyncAgentExecutionDiagnosisSupport agentExecutionDiagnosisSupport;
    private final SyncTaskCreateWizardDraftSupport createWizardDraftSupport;
    private SyncRecoveryCasePublishSupport recoveryCasePublishSupport;

    @Autowired
    public void setRecoveryCasePublishSupport(SyncRecoveryCasePublishSupport recoveryCasePublishSupport) {
        this.recoveryCasePublishSupport = recoveryCasePublishSupport;
    }


    /**
     * 保存创建向导草稿。
     *
     * <p>主 Service 只负责声明事务边界并委托给 {@link SyncTaskCreateWizardDraftSupport}。
     * 这样“创建/更新 DRAFT 任务及定义”的规则不会继续塞进已经很重的 ServiceImpl，也方便后续单独补草稿恢复、草稿发布、
     * 草稿超期清理等能力。</p>
     */
    @Override
    @Transactional
    public SyncTaskCreateWizardDraftSaveResponse saveCreateWizardDraft(SyncTaskCreateWizardDraftSaveRequest request,
                                                                       SyncActorContext actorContext) {
        return createWizardDraftSupport.saveDraft(request, actorContext);
    }

    @Override
    public PlatformPageResponse<SyncTask> pageTasks(SyncTaskQueryCriteria criteria, SyncActorContext actorContext) {
        SyncDataVisibility visibility = dataScopeSupport.resolveVisibility(
                criteria.tenantId(), criteria.projectId(), criteria.workspaceId(), actorContext);
        LambdaQueryWrapper<SyncTask> wrapper = new LambdaQueryWrapper<SyncTask>()
                .orderByDesc(SyncTask::getId);
        if (visibility.tenantId() != null) {
            wrapper.eq(SyncTask::getTenantId, visibility.tenantId());
        }
        querySupport.eqIfPresent(wrapper, SyncTask::getProjectId, visibility.projectId());
        dataScopeSupport.applyAuthorizedProjectScope(wrapper, SyncTask::getProjectId, visibility);
        querySupport.eqIfPresent(wrapper, SyncTask::getWorkspaceId, visibility.workspaceId());
        if (visibility.selfOnly()) {
            wrapper.eq(SyncTask::getOwnerId, querySupport.actorId(actorContext));
        }
        querySupport.eqIfPresent(wrapper, SyncTask::getOwnerId, criteria.ownerId());
        applyTaskGroupFilter(wrapper, criteria.groupCode());
        String requestedState = querySupport.normalizeCode(criteria.currentState());
        if (requestedState == null) {
            /*
             * 普通任务列表默认不展示回收站和已彻底删除任务。
             * 回收站本身仍可通过 currentState=RECYCLED 显式查询，便于前端单独做“回收站”视图；
             * DELETED 则只保留给审计、历史执行和后续数据保留策略，不应出现在日常运营列表中。
             */
            wrapper.notIn(SyncTask::getCurrentState, SyncTaskState.RECYCLED.name(), SyncTaskState.DELETED.name());
        } else {
            querySupport.eqIfPresent(wrapper, SyncTask::getCurrentState, requestedState);
        }
        querySupport.eqIfPresent(wrapper, SyncTask::getTriggerType, querySupport.normalizeCode(criteria.triggerType()));
        applyTaskKeywordFilter(wrapper, criteria.keyword());
        Page<SyncTask> page = taskMapper.selectPage(querySupport.page(criteria.current(), criteria.size()), wrapper);
        page.getRecords().forEach(this::normalizeDefaultGroupForResponse);
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    @Override
    public SyncTask getTask(Long id, SyncActorContext actorContext) {
        SyncTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "同步任务不存在: " + id);
        }
        if (SyncTaskState.DELETED.name().equals(task.getCurrentState())) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "同步任务已彻底删除: " + id);
        }
        dataScopeSupport.validateOwnedReadable(task.getTenantId(), task.getProjectId(),
                task.getOwnerId(), actorContext, "同步任务");
        task.setDefinition(getDefinitionForTask(task));
        return task;
    }

    /**
     * 使用用户可见的 taskId 执行真实预检查。
     *
     * <p>先调用 {@link #getTask(Long, SyncActorContext)} 收口租户、项目和任务数据范围，
     * 再使用相同 taskId 的定义快照执行预检查。</p>
     */
    @Override
    public SyncTaskExecutionPrecheckResponse precheckTask(Long id, SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        SyncTaskDefinition definition = task.getDefinition();
        SyncTaskDefinitionExecutionPrecheckResponse precheck =
                taskDefinitionExecutionPrecheckSupport.precheck(definition, actorContext);
        return SyncTaskExecutionPrecheckResponse.from(task.getId(), precheck);
    }

    /**
     * 查询回收站任务。
     *
     * <p>普通 pageTasks 在 currentState 为空时会主动排除 RECYCLED/DELETED。
     * 这里构造一个强制 currentState=RECYCLED 的查询条件，让前端和 Agent 拥有清晰的回收站入口，
     * 不需要知道“普通列表传 currentState=RECYCLED 也能查到”这种内部兼容细节。</p>
     */
    @Override
    public PlatformPageResponse<SyncTask> pageRecycledTasks(SyncTaskQueryCriteria criteria,
                                                            SyncActorContext actorContext) {
        SyncTaskQueryCriteria safeCriteria = criteria == null
                ? new SyncTaskQueryCriteria(null, null, null, null, null,
                SyncTaskState.RECYCLED.name(), null, null, null)
                : new SyncTaskQueryCriteria(
                criteria.tenantId(),
                criteria.projectId(),
                criteria.workspaceId(),
                criteria.ownerId(),
                criteria.groupCode(),
                SyncTaskState.RECYCLED.name(),
                criteria.triggerType(),
                criteria.current(),
                criteria.size(),
                criteria.keyword());
        return pageTasks(safeCriteria, actorContext);
    }

    /**
     * 编辑任务定义。
     *
     * <p>主 Service 只负责复用 getTask(...) 完成入口校验并加载任务定义。
     * 具体“哪些状态可编辑、调度字段如何退回草稿、审计如何低敏记录”交给
     * {@link SyncTaskDefinitionOperationSupport}，避免 Impl 继续堆积任务定义细节。</p>
     */
    @Override
    @Transactional
    public SyncTask updateTask(Long id, SyncTaskUpdateRequest request, SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        assertTaskManageable(task, actorContext, "编辑同步任务");
        SyncTaskDefinition definition = task.getDefinition();
        return taskDefinitionOperationSupport.updateTaskDefinition(task, definition, request, actorContext);
    }

    /**
     * 发布任务定义。
     *
     * <p>发布不是运行任务，而是把任务重新推进到 CONFIGURED/SCHEDULED/PENDING_APPROVAL。
     * 真正创建 execution 仍然由 run/manual-dispatch/scheduler 完成，这样任务定义状态和执行历史能保持清晰分离。</p>
     */
    @Override
    @Transactional
    public SyncTaskOperationResult publishTask(Long id,
                                               SyncTaskPublishRequest request,
                                               SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        assertTaskManageable(task, actorContext, "发布同步任务");
        SyncTaskDefinition definition = task.getDefinition();
        return taskDefinitionOperationSupport.publishTaskDefinition(task, definition, request, actorContext);
    }

    /**
     * 导出任务定义文件。
     *
     * <p>Service 继续保持“入口编排”职责：Controller 负责 HTTP 文件响应，ExchangeSupport 负责文件编码和数据范围过滤。
     * 这样后续如果 Agent 工具也要导出任务定义，可以直接调用同一个 support，而不是复制 Controller 逻辑。</p>
     */
    @Override
    public SyncTaskExportFile exportTasks(SyncTaskQueryCriteria criteria,
                                          String format,
                                          SyncActorContext actorContext) {
        return taskDefinitionExchangeSupport.exportTasks(criteria, format, actorContext);
    }

    /**
     * 按选中的任务 ID 批量导出任务定义。
     *
     * <p>主 Service 仍然只做委托，具体的 ID 去重、可见性校验、低敏文件编码和审计写入都在
     * {@link SyncTaskDefinitionExchangeSupport} 内部完成，避免 Controller 或 ServiceImpl 直接拼导出规则。</p>
     */
    @Override
    public SyncTaskExportFile exportTasksByIds(List<Long> taskIds,
                                               String format,
                                               SyncActorContext actorContext) {
        return taskDefinitionExchangeSupport.exportTasksByIds(taskIds, format, actorContext);
    }

    /**
     * 导入任务定义文件。
     *
     * <p>导入可能批量创建任务，或在 runImmediately=true 时创建 execution，因此必须处于事务中。
     * 如果写入阶段出现异常，已插入的任务和执行记录会回滚，避免半批导入成功。</p>
     */
    @Override
    @Transactional
    public SyncTaskImportResult importTasks(byte[] content,
                                            SyncTaskImportOptions options,
                                            SyncActorContext actorContext) {
        return taskDefinitionExchangeSupport.importTasks(content, options, actorContext);
    }

    /**
     * 批量手工调度同步任务。
     *
     * <p>批量动作不在这里直接循环调用单任务方法，而是交给 {@link SyncTaskBatchOperationSupport} 使用逐条事务执行。
     * 这样某一条失败时只回滚该任务，已成功的任务仍能保留并返回清晰明细。</p>
     */
    @Override
    public SyncTaskBatchOperationResult batchManualDispatchTasks(SyncTaskBatchOperationRequest request,
                                                                 SyncActorContext actorContext) {
        return taskBatchOperationSupport.manualDispatchTasks(request, actorContext);
    }

    /**
     * 查询任务分组汇总。
     *
     * <p>主 Service 不直接写聚合 SQL，而是委托给 {@link SyncTaskGroupOperationSupport}：
     * 分组能力后续会继续扩展到批量移组、组级导出、组级手工调度和 Agent 查询工具，把规则集中在 support
     * 里更容易保持编码规范、SELF 数据范围和审计口径一致。</p>
     */
    @Override
    public List<SyncTaskGroupSummary> listTaskGroups(SyncTaskQueryCriteria criteria, SyncActorContext actorContext) {
        return taskGroupOperationSupport.listTaskGroups(criteria, actorContext);
    }

    /**
     * 查询可渲染为树形菜单的同步任务分组。
     *
     * <p>该方法面向前端“左侧导航栏 + 内容页中间分组菜单栏”的双菜单场景：
     * Service 层只负责暴露稳定契约，真正的默认分组兜底、历史分组合并、父子关系构建和任务数量聚合都由
     * {@link SyncTaskGroupOperationSupport} 统一处理，避免列表页、创建页、导入页各自解释一套分组规则。</p>
     */
    @Override
    public List<SyncTaskGroupTreeNode> listTaskGroupTree(SyncTaskQueryCriteria criteria,
                                                         SyncActorContext actorContext) {
        return taskGroupOperationSupport.listTaskGroupTree(criteria, actorContext);
    }

    /**
     * 创建同步任务分组资源。
     *
     * <p>新增分组会立即参与任务创建、任务编辑、克隆和导入校验；这意味着后端不能只把它当作 UI 菜单项，
     * 而要把它作为可审计、可删除、可迁移任务归属的业务资源落库。</p>
     */
    @Override
    @Transactional
    public SyncTaskGroup createTaskGroup(SyncTaskGroupCreateRequest request,
                                         SyncActorContext actorContext) {
        return taskGroupOperationSupport.createTaskGroup(request, actorContext);
    }

    /**
     * 删除同步任务分组，并把受影响任务迁回默认分组。
     *
     * <p>删除分组属于高影响控制面动作：它不会删除任务，也不会停止执行中的任务，只改变运营归属。
     * 因此这里保留事务边界，保证“归档分组”和“任务迁回 DEFAULT”要么同时成功，要么同时回滚。</p>
     */
    @Override
    @Transactional
    public SyncTaskOperationResult deleteTaskGroup(String groupCode,
                                                   Long tenantId,
                                                   Long projectId,
                                                   Long workspaceId,
                                                   String reason,
                                                   SyncActorContext actorContext) {
        return taskGroupOperationSupport.deleteTaskGroup(groupCode, tenantId, projectId, workspaceId, reason, actorContext);
    }

    /**
     * 自动发现创建同步任务时可选的 schema/table/field 元数据。
     *
     * <p>data-sync 不直接连接源库或目标库，而是通过 datasource-management 的低敏元数据接口读取结构信息；
     * 这样数据源凭据、连接池和连接诊断仍然留在 datasource-management 模块内，data-sync 只负责同步配置语义。</p>
     */
    @Override
    public SyncTaskMetadataDiscoveryResponse discoverTaskMetadata(SyncTaskMetadataDiscoveryRequest request,
                                                                  SyncActorContext actorContext) {
        return taskMetadataConfigurationSupport.discoverTaskMetadata(request, actorContext);
    }

    /**
     * 根据源表和目标表生成字段映射建议。
     *
     * <p>字段映射建议只给出“默认是否同步”的保守判断，前端和 Agent 仍然需要允许用户最终确认。
     * 这样既能减少手工配置成本，又不会因为自动映射过于激进而把不兼容字段直接写入生产任务。</p>
     */
    @Override
    public SyncTaskFieldMappingSuggestionResponse suggestFieldMappings(SyncTaskFieldMappingSuggestionRequest request,
                                                                       SyncActorContext actorContext) {
        return taskMetadataConfigurationSupport.suggestFieldMappings(request, actorContext);
    }

    /**
     * 调整任务所属分组。
     *
     * <p>入口仍然先调用 getTask(...)，保证租户、项目和 SELF 范围校验一致；
     * 真正的分组编码规范化、持久化和审计由分组 support 负责。</p>
     */
    @Override
    @Transactional
    public SyncTaskOperationResult updateTaskGroup(Long id,
                                                   SyncTaskGroupUpdateRequest request,
                                                   SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        assertTaskManageable(task, actorContext, "调整同步任务分组");
        return taskGroupOperationSupport.updateTaskGroup(task, request, actorContext);
    }

    @Override
    @Transactional
    public SyncTaskOperationResult runTask(Long id, SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        assertTaskManageable(task, actorContext, "运行同步任务");
        SyncTaskDefinition definition = getDefinitionForTask(task);
        SyncTaskDefinitionExecutionPrecheckResponse precheck = taskDefinitionExecutionPrecheckSupport.precheck(definition);
        if (!canRunAfterPrecheck(precheck, task)) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "同步任务执行前预检查未通过，precheckStatus=" + precheck.precheckStatus()
                            + "，issueCodes=" + precheck.issueCodes()
                            + "，recommendedActions=" + precheck.recommendedActions());
        }
        stateMachineSupport.assertCanQueue(task.getCurrentState());
        SyncExecution execution = executionCreationSupport.createQueuedExecution(task, actorContext);
        task.setCurrentState(SyncTaskState.QUEUED.name());
        task.setTriggerType(SyncTriggerType.MANUAL.name());
        task.setLastExecutionId(execution.getId());
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
        auditSupport.saveAudit(task.getTenantId(), task.getId(), execution.getId(), SyncAuditActionType.RUN_TASK,
                actorContext, "taskId=" + task.getId() + ",executionId=" + execution.getId());
        return new SyncTaskOperationResult(task.getId(), task.getCurrentState(),
                "同步任务已进入待执行队列，执行记录 ID=" + execution.getId() + "；后续将接入执行器、checkpoint 和任务中心协议");
    }

    /**
     * 手工调度同步任务。
     *
     * <p>这里继续复用 getTask(...) 作为入口级数据范围校验，然后把“预检、状态机、execution 创建、审计”委托给
     * {@link SyncTaskManagementOperationSupport}。这样 runTask 的历史兼容语义不被破坏，同时新路由可以拥有更准确的
     * MANUAL_DISPATCH_TASK 审计动作。</p>
     */
    @Override
    @Transactional
    public SyncTaskOperationResult manualDispatchTask(Long id, SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        assertTaskManageable(task, actorContext, "手工调度同步任务");
        return taskManagementOperationSupport.manualDispatchTask(task, actorContext);
    }

    /**
     * 批量下线同步任务。
     *
     * <p>批量下线是批量删除前的正式治理入口：它会关闭自动调度，避免周期任务继续被 scheduler 扫描。
     * 逐条准入规则仍由单任务下线动作维护，例如活跃执行中的任务不能直接下线。</p>
     */
    @Override
    public SyncTaskBatchOperationResult batchOfflineTasks(SyncTaskBatchOperationRequest request,
                                                          SyncActorContext actorContext) {
        return taskBatchOperationSupport.offlineTasks(request, actorContext);
    }


    /**
     * 判断任务是否允许越过高风险提示状态进入队列。
     *
     * <p>当前产品口径下，新建同步任务不再要求用户填写审批事实。预检查如果返回 REQUIRES_APPROVAL，
     * 在用户界面应解释为“高风险但配置具备运行前提，需要重点展示风险、执行策略和审计提示”，而不是阻塞创建流程。
     * 真正阻断仍由 canStartExecution=false、权限拒绝、执行策略准入失败或 worker 执行异常负责。</p>
     */
    private boolean canRunAfterPrecheck(SyncTaskDefinitionExecutionPrecheckResponse precheck, SyncTask task) {
        if (precheck.canStartExecution()) {
            return true;
        }
        return SyncTaskDefinitionExecutionPrecheckSupport.REQUIRES_APPROVAL.equals(precheck.precheckStatus());
    }


    @Override
    @Transactional
    public SyncTaskOperationResult pauseTask(Long id,
                                             SyncTaskLifecycleOperationRequest request,
                                             SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        assertTaskManageable(task, actorContext, "暂停同步任务");
        return taskLifecycleOperationSupport.pauseTask(task, request, actorContext);
    }

    @Override
    @Transactional
    public SyncTaskOperationResult resumeTask(Long id,
                                              SyncTaskLifecycleOperationRequest request,
                                              SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        assertTaskManageable(task, actorContext, "恢复同步任务");
        return taskLifecycleOperationSupport.resumeTask(task, request, actorContext);
    }

    @Override
    @Transactional
    public SyncTaskOperationResult retryTask(Long id,
                                             SyncTaskLifecycleOperationRequest request,
                                             SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        assertTaskManageable(task, actorContext, "重试同步任务");
        return taskLifecycleOperationSupport.retryTask(task, request, actorContext);
    }

    @Override
    @Transactional
    public SyncTaskOperationResult cancelTask(Long id,
                                              SyncTaskLifecycleOperationRequest request,
                                              SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        assertTaskManageable(task, actorContext, "取消同步任务");
        return taskLifecycleOperationSupport.cancelTask(task, request, actorContext);
    }

    @Override
    @Transactional
    public SyncTaskOperationResult manualTerminateTask(Long id,
                                                       SyncTaskLifecycleOperationRequest request,
                                                       SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        assertTaskManageable(task, actorContext, "手工结束同步任务");
        return taskManagementOperationSupport.manualTerminateTask(task, request, actorContext);
    }

    @Override
    @Transactional
    public SyncTaskOperationResult offlineTask(Long id,
                                               SyncTaskLifecycleOperationRequest request,
                                               SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        assertTaskManageable(task, actorContext, "下线同步任务");
        return taskManagementOperationSupport.offlineTask(task, request, actorContext);
    }

    /**
     * 批量删除同步任务到回收站。
     *
     * <p>该入口不会自动把任务下线后再删除，因为“下线”和“删除到回收站”在审计、告警和用户确认上是两件事。
     * 如果任务尚未 OFFLINE，单条结果会失败并提示调用方先执行批量下线。</p>
     */
    @Override
    public SyncTaskBatchOperationResult batchRecycleTasks(SyncTaskBatchOperationRequest request,
                                                          SyncActorContext actorContext) {
        return taskBatchOperationSupport.recycleTasks(request, actorContext);
    }

    @Override
    @Transactional
    public SyncTaskOperationResult recycleTask(Long id,
                                               SyncTaskLifecycleOperationRequest request,
                                               SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        assertTaskManageable(task, actorContext, "删除同步任务到回收站");
        return taskManagementOperationSupport.recycleTask(task, request, actorContext);
    }

    /**
     * 批量彻底删除回收站任务。
     *
     * <p>彻底删除仍采用逻辑 DELETED，且只允许 RECYCLED 任务进入该状态。
     * 这样可以兼顾“普通列表不可见”和“历史执行/审计证据可追溯”。</p>
     */
    @Override
    public SyncTaskBatchOperationResult batchHardDeleteTasks(SyncTaskBatchOperationRequest request,
                                                             SyncActorContext actorContext) {
        return taskBatchOperationSupport.hardDeleteTasks(request, actorContext);
    }

    @Override
    @Transactional
    public SyncTaskOperationResult hardDeleteTask(Long id,
                                                  SyncTaskLifecycleOperationRequest request,
                                                  SyncActorContext actorContext) {
        /*
         * hard-delete 必须能读取 RECYCLED 任务，但不能读取 DELETED 任务。
         * getTask(...) 已经会对 DELETED 返回 NOT_FOUND；RECYCLED 仍可通过数据范围校验后进入这里。
         */
        SyncTask task = getTask(id, actorContext);
        assertTaskManageable(task, actorContext, "彻底删除同步任务");
        return taskManagementOperationSupport.hardDeleteTask(task, request, actorContext);
    }

    @Override
    @Transactional
    public SyncTaskOperationResult cloneTask(Long id,
                                             SyncTaskCloneRequest request,
                                             SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        assertTaskManageable(task, actorContext, "克隆同步任务");
        return taskManagementOperationSupport.cloneTask(task, request, actorContext);
    }

    /**
     * 发起同步回放。
     *
     * <p>主 Service 只负责复用 getTask(...) 做租户、项目、SELF 范围校验，然后把恢复语义委托给
     * SyncTaskRecoveryOperationSupport。这样权限边界集中在入口，恢复计划、checkpoint 解析和审计细节集中在领域组件。
     */
    @Override
    @Transactional
    public SyncTaskOperationResult replayTask(Long id,
                                              SyncTaskRecoveryOperationRequest request,
                                              SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        assertTaskManageable(task, actorContext, "回放同步任务");
        return taskRecoveryOperationSupport.replayTask(task, request, actorContext);
    }

    /**
     * 发起同步补数。
     *
     * <p>补数属于高影响恢复动作，入口仍先读取任务并校验数据范围。
     * 真实窗口参数校验和恢复计划持久化由 support 负责，避免主 Service 继续膨胀。
     */
    @Override
    @Transactional
    public SyncTaskOperationResult backfillTask(Long id,
                                                SyncTaskRecoveryOperationRequest request,
                                                SyncActorContext actorContext) {
        SyncTask task = getTask(id, actorContext);
        assertTaskManageable(task, actorContext, "补数同步任务");
        return taskRecoveryOperationSupport.backfillTask(task, request, actorContext);
    }

    @Override
    @Transactional
    public SyncExecution startExecution(Long taskId,
                                        Long executionId,
                                        SyncExecutionStartRequest request,
                                        SyncActorContext actorContext) {
        SyncTask task = getTask(taskId, actorContext);
        SyncExecution execution = getExecutionForTask(executionId, task);
        return executionLifecycleSupport.startExecution(task, execution, request, actorContext);
    }

    @Override
    @Transactional
    public SyncCheckpoint writeCheckpoint(Long taskId,
                                          Long executionId,
                                          SyncExecutionCheckpointRequest request,
                                          SyncActorContext actorContext) {
        SyncTask task = getTask(taskId, actorContext);
        SyncExecution execution = getExecutionForTask(executionId, task);
        return executionLifecycleSupport.writeCheckpoint(task, execution, request, actorContext);
    }

    @Override
    @Transactional
    public SyncExecution completeExecution(Long taskId,
                                           Long executionId,
                                           SyncExecutionCompleteRequest request,
                                           SyncActorContext actorContext) {
        SyncTask task = getTask(taskId, actorContext);
        SyncExecution execution = getExecutionForTask(executionId, task);
        return executionLifecycleSupport.completeExecution(task, execution, request, actorContext);
    }

    @Override
    @Transactional
    public SyncErrorSample failExecution(Long taskId,
                                         Long executionId,
                                         SyncExecutionFailRequest request,
                                         SyncActorContext actorContext) {
        SyncTask task = getTask(taskId, actorContext);
        SyncExecution execution = getExecutionForTask(executionId, task);
        return executionLifecycleSupport.failExecution(task, execution, request, actorContext);
    }

    @Override
    public PlatformPageResponse<SyncExecution> pageExecutions(SyncExecutionQueryCriteria criteria,
                                                              SyncActorContext actorContext) {
        SyncDataVisibility visibility = resolveQueryVisibility(criteria.syncTaskId(), actorContext);
        LambdaQueryWrapper<SyncExecution> wrapper = new LambdaQueryWrapper<SyncExecution>()
                .orderByDesc(SyncExecution::getCreateTime)
                .orderByDesc(SyncExecution::getId);
        if (visibility.tenantId() != null) {
            wrapper.eq(SyncExecution::getTenantId, visibility.tenantId());
        }
        dataScopeSupport.applyAuthorizedProjectScope(wrapper, SyncExecution::getProjectId, visibility);
        eqIfPresent(wrapper, SyncExecution::getSyncTaskId, criteria.syncTaskId());
        eqIfPresent(wrapper, SyncExecution::getExecutionState, normalizeCode(criteria.executionState()));
        eqIfPresent(wrapper, SyncExecution::getTriggerType, normalizeCode(criteria.triggerType()));
        Page<SyncExecution> page = executionMapper.selectPage(querySupport.page(criteria.current(), criteria.size()), wrapper);
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    /**
     * 查询某次执行的运行日志。
     *
     * <p>这里故意不直接按 executionId 查询，而是先执行两步校验：</p>
     * <p>1. {@link #getTask(Long, SyncActorContext)} 校验调用者是否能读取该任务；</p>
     * <p>2. {@link #getExecutionForTask(Long, SyncTask)} 校验 execution 是否确实属于该任务。</p>
     *
     * <p>完成这两步后，再从日志表查询。这样即使调用者猜到了别的 executionId，也无法绕过任务级数据范围。
     * 日志表虽然只保存低敏信息，但运行速度、对象顺序、失败阶段仍属于运营证据，不能变成无权限可枚举资源。</p>
     */
    @Override
    public PlatformPageResponse<SyncExecutionLog> pageExecutionLogs(SyncExecutionLogQueryCriteria criteria,
                                                                    SyncActorContext actorContext) {
        SyncTask task = getTask(criteria.syncTaskId(), actorContext);
        SyncExecution execution = getExecutionForTask(criteria.executionId(), task);
        LambdaQueryWrapper<SyncExecutionLog> wrapper = new LambdaQueryWrapper<SyncExecutionLog>()
                .eq(SyncExecutionLog::getSyncTaskId, task.getId())
                .eq(SyncExecutionLog::getExecutionId, execution.getId())
                .orderByAsc(SyncExecutionLog::getEventTime)
                .orderByAsc(SyncExecutionLog::getId);
        eqIfPresent(wrapper, SyncExecutionLog::getLogStage, normalizeCode(criteria.logStage()));
        eqIfPresent(wrapper, SyncExecutionLog::getLogLevel, normalizeCode(criteria.logLevel()));
        Page<SyncExecutionLog> page = executionLogMapper.selectPage(
                querySupport.page(criteria.current(), criteria.size()), wrapper);
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    /**
     * 查询父 execution 下的对象级执行明细。
     *
     * <p>Service 层在这里先复用 {@link #getTask(Long, SyncActorContext)} 和
     * {@link #getExecutionForTask(Long, SyncTask)} 完成数据范围与父子归属校验，再委托 support 查询对象账本。
     * 这样 Controller 不需要理解权限细节，support 也不需要重复读取任务做入口级授权。</p>
     */
    @Override
    public PlatformPageResponse<SyncObjectExecutionView> pageObjectExecutions(SyncObjectExecutionQueryCriteria criteria,
                                                                              SyncActorContext actorContext) {
        SyncTask task = getTask(criteria.syncTaskId(), actorContext);
        SyncExecution execution = getExecutionForTask(criteria.executionId(), task);
        return objectExecutionOperationSupport.pageObjectExecutions(task, execution, criteria);
    }

    /**
     * 发起对象级失败重试。
     *
     * <p>这不是普通整单 retry，而是 DataX-style “失败对象/分片重传”。因此入口先校验 task/execution 的可见性和归属，
     * 再由 {@link SyncObjectExecutionOperationSupport} 重置 FAILED 对象、重新排队父 execution 并写审计。</p>
     */
    @Override
    @Transactional
    public SyncObjectRetryResult retryObjectExecutions(Long taskId,
                                                       Long executionId,
                                                       SyncObjectRetryRequest request,
                                                       SyncActorContext actorContext) {
        SyncTask task = getTask(taskId, actorContext);
        assertTaskManageable(task, actorContext, "重试失败分片/对象");
        SyncExecution execution = getExecutionForTask(executionId, task);
        return objectExecutionOperationSupport.retryFailedObjects(task, execution, request, actorContext);
    }

    @Override
    public PlatformPageResponse<SyncCheckpoint> pageCheckpoints(SyncCheckpointQueryCriteria criteria,
                                                                SyncActorContext actorContext) {
        SyncDataVisibility visibility = resolveQueryVisibility(criteria.syncTaskId(), actorContext);
        LambdaQueryWrapper<SyncCheckpoint> wrapper = new LambdaQueryWrapper<SyncCheckpoint>()
                .orderByDesc(SyncCheckpoint::getCheckpointTime)
                .orderByDesc(SyncCheckpoint::getId);
        if (visibility.tenantId() != null) {
            wrapper.eq(SyncCheckpoint::getTenantId, visibility.tenantId());
        }
        dataScopeSupport.applyAuthorizedProjectScope(wrapper, SyncCheckpoint::getProjectId, visibility);
        eqIfPresent(wrapper, SyncCheckpoint::getSyncTaskId, criteria.syncTaskId());
        eqIfPresent(wrapper, SyncCheckpoint::getExecutionId, criteria.executionId());
        eqIfPresent(wrapper, SyncCheckpoint::getCheckpointType, normalizeCode(criteria.checkpointType()));
        Page<SyncCheckpoint> page = checkpointMapper.selectPage(querySupport.page(criteria.current(), criteria.size()), wrapper);
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    @Override
    public PlatformPageResponse<SyncErrorSample> pageErrorSamples(SyncErrorSampleQueryCriteria criteria,
                                                                  SyncActorContext actorContext) {
        SyncDataVisibility visibility = resolveQueryVisibility(criteria.syncTaskId(), actorContext);
        LambdaQueryWrapper<SyncErrorSample> wrapper = new LambdaQueryWrapper<SyncErrorSample>()
                .orderByDesc(SyncErrorSample::getCreateTime)
                .orderByDesc(SyncErrorSample::getId);
        if (visibility.tenantId() != null) {
            wrapper.eq(SyncErrorSample::getTenantId, visibility.tenantId());
        }
        dataScopeSupport.applyAuthorizedProjectScope(wrapper, SyncErrorSample::getProjectId, visibility);
        eqIfPresent(wrapper, SyncErrorSample::getSyncTaskId, criteria.syncTaskId());
        eqIfPresent(wrapper, SyncErrorSample::getExecutionId, criteria.executionId());
        eqIfPresent(wrapper, SyncErrorSample::getErrorType, normalizeCode(criteria.errorType()));
        if (criteria.retryable() != null) {
            wrapper.eq(SyncErrorSample::getRetryable, criteria.retryable());
        }
        Page<SyncErrorSample> page = errorSampleMapper.selectPage(querySupport.page(criteria.current(), criteria.size()), wrapper);
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    @Override
    public SyncExecutionDiagnosisResponse diagnoseExecution(Long taskId,
                                                            Long executionId,
                                                            SyncActorContext actorContext) {
        SyncTask task = getTask(taskId, actorContext);
        SyncTaskDefinition definition = getDefinitionForTask(task);
        return agentExecutionDiagnosisSupport.diagnose(task, definition, executionId);
    }

    @Override
    public SyncDirtyRecordQuarantineResult previewDirtyRecordQuarantine(
            Long taskId,
            SyncDirtyRecordQuarantineRequest request,
            SyncActorContext actorContext) {
        SyncTask task = getTask(taskId, actorContext);
        assertTaskManageable(task, actorContext, "预览脏数据隔离");
        return dirtyRecordQuarantineSupport.preview(task, request);
    }

    @Override
    @Transactional
    public SyncDirtyRecordQuarantineResult applyDirtyRecordQuarantine(
            Long taskId,
            SyncDirtyRecordQuarantineRequest request,
            SyncActorContext actorContext) {
        SyncTask task = getTask(taskId, actorContext);
        assertTaskManageable(task, actorContext, "应用脏数据隔离");
        return dirtyRecordQuarantineSupport.apply(task, request, actorContext);
    }

    @Override
    @Transactional
    public SyncRecoveryCasePublishResult publishRecoveryCase(
            Long taskId,
            SyncRecoveryCasePublishRequest request,
            SyncActorContext actorContext) {
        SyncTask task = getTask(taskId, actorContext);
        assertTaskManageable(task, actorContext, "发布 Agent 恢复案例");
        if (recoveryCasePublishSupport == null) {
            throw new IllegalStateException("Agent 恢复案例服务尚未就绪");
        }
        return recoveryCasePublishSupport.publish(task, request, actorContext);
    }

    /**
     * 基于错误样本创建脏数据修复重放计划。
     *
     * <p>Service 仍然只做入口级任务读取和权限收敛：先通过 {@link #getTask(Long, SyncActorContext)}
     * 校验租户、项目、SELF 范围，再把“错误样本选择、retryable 校验、修复确认、恢复计划创建、审计”等复杂规则委托给
     * {@link SyncDirtyRecordReplaySupport}。这样可以避免 DataSyncServiceImpl 因为数据治理细节继续膨胀。</p>
     */
    @Override
    @Transactional
    public SyncDirtyRecordReplayResult replayDirtyRecords(Long taskId,
                                                          SyncDirtyRecordReplayRequest request,
                                                          SyncActorContext actorContext) {
        SyncTask task = getTask(taskId, actorContext);
        assertTaskManageable(task, actorContext, "重放脏数据记录");
        return dirtyRecordReplaySupport.replayDirtyRecords(task, request, actorContext);
    }

    @Override
    public PlatformPageResponse<SyncAuditRecord> pageAuditRecords(SyncAuditQueryCriteria criteria,
                                                                  SyncActorContext actorContext) {
        SyncDataVisibility visibility = resolveQueryVisibility(criteria.syncTaskId(), actorContext);
        LambdaQueryWrapper<SyncAuditRecord> wrapper = new LambdaQueryWrapper<SyncAuditRecord>()
                .orderByDesc(SyncAuditRecord::getCreateTime)
                .orderByDesc(SyncAuditRecord::getId);
        if (visibility.tenantId() != null) {
            wrapper.eq(SyncAuditRecord::getTenantId, visibility.tenantId());
        }
        dataScopeSupport.applyAuthorizedProjectScope(wrapper, SyncAuditRecord::getProjectId, visibility);
        eqIfPresent(wrapper, SyncAuditRecord::getSyncTaskId, criteria.syncTaskId());
        eqIfPresent(wrapper, SyncAuditRecord::getExecutionId, criteria.executionId());
        eqIfPresent(wrapper, SyncAuditRecord::getActionType, normalizeCode(criteria.actionType()));
        eqIfPresent(wrapper, SyncAuditRecord::getActorId, criteria.actorId());
        Page<SyncAuditRecord> page = auditRecordMapper.selectPage(querySupport.page(criteria.current(), criteria.size()), wrapper);
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    /**
     * 解析执行追踪类列表的可见范围。
     *
     * <p>执行记录、checkpoint、错误样本和审计记录有两种查询形态：
     * 1. 从某个任务详情页进入，此时 criteria 中带 syncTaskId，必须先读取任务并复用 getTask(...) 做租户、项目和 SELF 校验；
     * 2. 从运营台横向检索，此时可能没有 syncTaskId，需要直接依赖这些子表冗余的 tenantId/projectId 做范围收口。
     *
     * <p>这也是为什么 execution/checkpoint/error/audit 表要冗余 projectId：
     * 生产环境下全局执行历史和错误样本列表不能每次都 join 任务表，否则在高吞吐同步场景下会放大查询成本。
     */
    private SyncDataVisibility resolveQueryVisibility(Long syncTaskId, SyncActorContext actorContext) {
        if (syncTaskId != null) {
            SyncTask task = getTask(syncTaskId, actorContext);
            return new SyncDataVisibility(task.getTenantId(), task.getProjectId(), List.of(), task.getWorkspaceId(),
                    false, false, "TASK_SCOPED", null, false);
        }
        return dataScopeSupport.resolveVisibility(null, null, null, actorContext);
    }

    /**
     * 校验当前操作者是否可以管理指定同步任务。
     *
     * <p>{@link #getTask(Long, SyncActorContext)} 只回答“是否可读”：它保护任务详情、运行历史、日志、
     * 错误样本等低敏读取入口，允许 READER 在授权项目内查看。运行、编辑、删除、回放、补数、失败对象重试等动作
     * 会改变任务生命周期或真实数据同步结果，因此必须额外要求项目内 MANAGER/OWNER/SERVICE 角色。</p>
     *
     * <p>把校验集中在这个方法里还有一个好处：后续如果项目角色模型扩展出 OPERATOR、DATA_STEWARD、
     * CONNECTOR_ADMIN 等更细角色，只需要在 data-scope support 的角色集合中调整，不必到每个业务入口里改判断。</p>
     */
    private void assertTaskManageable(SyncTask task, SyncActorContext actorContext, String actionName) {
        if (task == null) {
            return;
        }
        dataScopeSupport.validateProjectManageable(
                task.getTenantId(), task.getProjectId(), task.getWorkspaceId(), actorContext, actionName);
    }

    private SyncExecution getExecutionForTask(Long executionId, SyncTask task) {
        SyncExecution execution = executionMapper.selectById(executionId);
        if (execution == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "同步执行记录不存在: " + executionId);
        }
        if (!task.getId().equals(execution.getSyncTaskId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "执行记录不属于当前同步任务，taskId=" + task.getId() + ", executionId=" + executionId);
        }
        return execution;
    }

    /** 按 taskId 读取一对一任务定义，并验证数据范围没有漂移。 */
    private SyncTaskDefinition getDefinitionForTask(SyncTask task) {
        SyncTaskDefinition definition = taskDefinitionMapper.selectById(task.getId());
        if (definition == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "同步任务定义不存在，taskId=" + task.getId());
        }
        if (!task.getTenantId().equals(definition.getTenantId())
                || !sameNullable(task.getProjectId(), definition.getProjectId())
                || !sameNullable(task.getWorkspaceId(), definition.getWorkspaceId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "同步任务与定义归属不一致，拒绝执行，taskId=" + task.getId());
        }
        return definition;
    }

    private <T, V> void eqIfPresent(LambdaQueryWrapper<T> wrapper,
                                    com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, V> column,
                                    V value) {
        if (value != null) {
            if (value instanceof String text && text.isBlank()) {
                return;
            }
            wrapper.eq(column, value);
        }
    }

    /**
     * 给任务列表追加分组筛选条件。
     *
     * <p>这里不能把“默认分组”简单写成 {@code group_code = 'DEFAULT'}。原因是项目在持续演进过程中，
     * 历史草稿、导入任务、旧版未分组任务以及删除分组后迁回的任务，可能分别留下 {@code NULL}、空字符串
     * 或 {@code DEFAULT} 三种存储形态。分组树和分组汇总已经把它们聚合成一个“默认分组”节点；
     * 如果列表接口仍只精确匹配 {@code DEFAULT}，页面就会出现“左侧默认分组 22 条，点击后列表只有 2 条”
     * 这种前后端语义错位。</p>
     *
     * <p>因此当筛选值为默认分组时，列表、回收站和导出等任务明细入口必须统一采用
     * {@code DEFAULT OR NULL OR ''} 的等价条件。普通业务分组仍然使用精确匹配，避免不同业务分组互相串数据。</p>
     */
    private void applyTaskGroupFilter(LambdaQueryWrapper<SyncTask> wrapper, String rawGroupCode) {
        String groupCode = taskGroupOperationSupport.normalizeGroupCodeForFilter(rawGroupCode);
        if (!hasText(groupCode)) {
            return;
        }
        if (SyncTaskGroupOperationSupport.DEFAULT_GROUP_CODE.equals(groupCode)) {
            /*
             * MyBatis-Plus can be configured to ignore empty-string equality conditions.
             * Use one explicit SQL expression so legacy '' and NULL rows are guaranteed
             * to be treated exactly like DEFAULT on PostgreSQL and MySQL.
             */
            wrapper.and(groupWrapper -> groupWrapper.apply(
                    "COALESCE(NULLIF(group_code, ''), {0}) = {0}",
                    SyncTaskGroupOperationSupport.DEFAULT_GROUP_CODE));
            return;
        }
        wrapper.eq(SyncTask::getGroupCode, groupCode);
    }

    /**
     * Normalize legacy default-group storage before returning task records.
     *
     * <p>Older task rows may store the default group as {@code NULL} or an empty string.
     * The query layer already treats those rows as DEFAULT; the response should do the same
     * so table rendering, export previews, and keyword search do not show an apparently
     * ungrouped task after the user has selected "默认分组".</p>
     */
    private void normalizeDefaultGroupForResponse(SyncTask task) {
        if (task == null || hasText(task.getGroupCode())) {
            return;
        }
        task.setGroupCode(SyncTaskGroupOperationSupport.DEFAULT_GROUP_CODE);
        if (!hasText(task.getGroupName())) {
            task.setGroupName(SyncTaskGroupOperationSupport.DEFAULT_GROUP_NAME);
        }
    }

    /**
     * Apply the list search box as a backend filter so pagination and search share one total.
     */
    private void applyTaskKeywordFilter(LambdaQueryWrapper<SyncTask> wrapper, String rawKeyword) {
        String keyword = querySupport.trimToNull(rawKeyword);
        if (keyword == null) {
            return;
        }
        wrapper.and(keywordWrapper -> keywordWrapper
                .like(SyncTask::getName, keyword)
                .or()
                .like(SyncTask::getGroupCode, keyword)
                .or()
                .like(SyncTask::getGroupName, keyword)
                .or()
                .like(SyncTask::getCurrentState, keyword)
                .or()
                .like(SyncTask::getRunMode, keyword));
    }

    private void assertTaskNameAvailable(Long tenantId, Long projectId, String name, Long currentTaskId) {
        LambdaQueryWrapper<SyncTask> wrapper = new LambdaQueryWrapper<SyncTask>()
                .eq(SyncTask::getTenantId, tenantId)
                .eq(SyncTask::getName, name)
                .ne(currentTaskId != null, SyncTask::getId, currentTaskId)
                .ne(SyncTask::getCurrentState, SyncTaskState.DELETED.name());
        if (projectId == null) {
            wrapper.isNull(SyncTask::getProjectId);
        } else {
            wrapper.eq(SyncTask::getProjectId, projectId);
        }
        Long count = taskMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.DUPLICATE_OPERATION,
                    "当前项目下已存在同名同步任务，请修改任务名称后再保存；已彻底删除的任务不会占用名称: " + name);
        }
    }

    private boolean sameNullable(Long left, Long right) {
        return left == null ? right == null : left.equals(right);
    }

    private String normalizeCode(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}

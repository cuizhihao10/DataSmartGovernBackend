/**
 * @Author : Cui
 * @Date: 2026/07/07 19:34
 * @Description DataSmart Govern Backend - SyncTaskDefinitionExchangeSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskExportFile;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportOptions;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskImportRowResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskPublishRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplateExecutionPrecheckResponse;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTemplateMapper;
import com.czh.datasmart.govern.datasync.support.SyncApprovalState;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncMode;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 同步任务定义导入/导出业务组件。
 *
 * <p>导入/导出是任务运营能力，不是数据搬运能力。它解决的是“如何批量迁移、备份、复用和审查任务定义”，
 * 不应该绕过模板预检、权限范围、审批边界和任务状态机。因此本组件坚持几个原则：</p>
 * <p>1. 导出只导出低敏任务定义摘要，不导出连接串、密码、完整 SQL、样本数据或 worker 内部计划；</p>
 * <p>2. 导入先全量校验，发现任何冲突或失败时不写入，避免半批成功造成运营混乱；</p>
 * <p>3. 导入后不立即执行时一律进入 DRAFT，由用户继续编辑/发布；</p>
 * <p>4. 导入后立即执行时，服务端先发布任务，再创建 MANUAL execution，仍然复用现有预检和状态机。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncTaskDefinitionExchangeSupport {

    private static final int DEFAULT_EXPORT_SIZE = 500;
    private static final int MAX_EXPORT_SIZE = 1000;
    private static final int MAX_BATCH_EXPORT_IDS = 1000;
    private static final int MAX_IMPORT_ROWS = 500;
    private static final DateTimeFormatter FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Set<String> ALLOWED_PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "URGENT");

    private final SyncTaskMapper taskMapper;
    private final SyncTemplateMapper templateMapper;
    private final SyncDataScopeSupport dataScopeSupport;
    private final SyncQuerySupport querySupport;
    private final SyncTaskGroupOperationSupport taskGroupOperationSupport;
    private final SyncTemplateValidationSupport templateValidationSupport;
    private final SyncTemplateExecutionPrecheckSupport templateExecutionPrecheckSupport;
    private final SyncTaskDefinitionOperationSupport taskDefinitionOperationSupport;
    private final SyncTaskManagementOperationSupport taskManagementOperationSupport;
    private final SyncTaskScheduleConfigSupport scheduleConfigSupport;
    private final SyncTaskDefinitionExchangeCodecSupport codecSupport;
    private final SyncAuditSupport auditSupport;

    /**
     * 导出同步任务定义文件。
     *
     * <p>导出会复用与任务列表一致的数据范围规则：普通用户只能导出自己可见的任务，项目负责人只能导出授权项目，
     * 审计员可导出租户内只读证据。默认不导出 RECYCLED/DELETED，除非调用方显式指定 currentState=RECYCLED。</p>
     */
    public SyncTaskExportFile exportTasks(SyncTaskQueryCriteria criteria,
                                          String requestedFormat,
                                          SyncActorContext actorContext) {
        String format = codecSupport.resolveFormat(requestedFormat, null);
        List<SyncTask> tasks = queryExportTasks(criteria, actorContext);
        byte[] content = codecSupport.encodeTasks(tasks, format);
        String fileName = "datasmart-sync-tasks-"
                + FILE_TIME_FORMATTER.format(LocalDateTime.now())
                + "." + codecSupport.fileExtension(format);
        auditSupport.saveAudit(resolveAuditTenant(criteria, actorContext), null, null,
                SyncAuditActionType.EXPORT_TASKS, actorContext,
                "format=" + format + ",exportedRows=" + tasks.size()
                        + ",currentState=" + (criteria == null ? null : criteria.currentState())
                        + ",groupCode=" + (criteria == null ? null : criteria.groupCode()));
        return new SyncTaskExportFile(fileName, codecSupport.contentType(format), content);
    }

    /**
     * 按用户或 Agent 明确选中的任务 ID 批量导出任务定义。
     *
     * <p>该方法和 {@link #exportTasks(SyncTaskQueryCriteria, String, SyncActorContext)} 的核心区别是导出范围来源不同：</p>
     * <p>1. 普通导出：通过查询条件导出一个列表视图，例如某个项目、分组或状态下的任务；</p>
     * <p>2. 批量导出：通过 taskId 列表导出明确选中的任务，适合前端复选框、Agent 工具确认后的精确导出。</p>
     *
     * <p>批量导出采用 fail-closed 策略：只要任意 taskId 不存在、已彻底删除或当前操作者不可见，就拒绝生成文件。
     * 这样可以避免“文件生成成功但悄悄漏掉某些任务”导致用户误以为备份完整。</p>
     */
    public SyncTaskExportFile exportTasksByIds(List<Long> taskIds,
                                               String requestedFormat,
                                               SyncActorContext actorContext) {
        String format = codecSupport.resolveFormat(requestedFormat, null);
        List<Long> safeTaskIds = normalizeBatchExportTaskIds(taskIds);
        List<SyncTask> tasks = new ArrayList<>();
        for (Long taskId : safeTaskIds) {
            SyncTask task = taskMapper.selectById(taskId);
            if (task == null) {
                throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "同步任务不存在: " + taskId);
            }
            if (SyncTaskState.DELETED.name().equals(task.getCurrentState())) {
                throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "同步任务已彻底删除，不能导出: " + taskId);
            }
            dataScopeSupport.validateOwnedReadable(task.getTenantId(), task.getProjectId(),
                    task.getOwnerId(), actorContext, "同步任务");
            tasks.add(task);
        }

        byte[] content = codecSupport.encodeTasks(tasks, format);
        String fileName = "datasmart-sync-tasks-selected-"
                + FILE_TIME_FORMATTER.format(LocalDateTime.now())
                + "." + codecSupport.fileExtension(format);
        auditSupport.saveAudit(resolveBatchAuditTenant(tasks, actorContext), null, null,
                SyncAuditActionType.BATCH_EXPORT_TASKS, actorContext,
                "format=" + format + ",requestedTaskCount=" + safeTaskIds.size()
                        + ",exportedRows=" + tasks.size());
        return new SyncTaskExportFile(fileName, codecSupport.contentType(format), content);
    }

    /**
     * 导入同步任务定义。
     *
     * <p>该方法是一个事务友好的“先校验后写入”流程。调用方可以传 dryRun=true 做导入预演；
     * 如果 dryRun=false，但任一行存在冲突或校验失败，本方法也不会写入任何任务，只返回行级诊断。</p>
     */
    public SyncTaskImportResult importTasks(byte[] content,
                                            SyncTaskImportOptions options,
                                            SyncActorContext actorContext) {
        SyncTaskImportOptions safeOptions = options == null ? new SyncTaskImportOptions() : options;
        String format = codecSupport.resolveFormat(safeOptions.getFormat(), safeOptions.getFileName());
        List<SyncTaskDefinitionExchangeCodecSupport.TaskDefinitionImportRow> rows =
                codecSupport.decodeRows(content, format);
        if (rows.size() > MAX_IMPORT_ROWS) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "单次同步任务导入最多支持 " + MAX_IMPORT_ROWS + " 行，当前行数=" + rows.size());
        }

        boolean dryRun = Boolean.TRUE.equals(safeOptions.getDryRun());
        boolean runImmediately = Boolean.TRUE.equals(safeOptions.getRunImmediately());
        SyncTaskImportResult result = new SyncTaskImportResult();
        result.setDryRun(dryRun);
        result.setRunImmediately(runImmediately);
        result.setTotalRows(rows.size());

        List<ValidatedImportTask> validatedTasks = validateImportRows(rows, runImmediately, actorContext, result);
        if (result.getConflictCount() > 0 || result.getFailedCount() > 0) {
            result.setStatus(result.getConflictCount() > 0 ? "BLOCKED_BY_CONFLICT" : "BLOCKED_BY_VALIDATION");
            result.setMessage("导入文件存在冲突或校验失败，未创建任何同步任务；请根据 rows 明细修改后重试");
            return result;
        }
        if (dryRun) {
            result.setStatus("VALIDATED");
            result.setValidRows(validatedTasks.size());
            result.setMessage("导入预检通过，未创建同步任务；确认无误后可关闭 dryRun 再导入");
            return result;
        }

        for (ValidatedImportTask validatedTask : validatedTasks) {
            SyncTask task = createDraftTask(validatedTask, actorContext);
            if (runImmediately) {
                taskDefinitionOperationSupport.publishTaskDefinition(
                        task, validatedTask.template(), new SyncTaskPublishRequest(), actorContext);
                SyncTask publishedTask = taskMapper.selectById(task.getId());
                taskManagementOperationSupport.manualDispatchTask(publishedTask, actorContext);
                result.getRows().add(new SyncTaskImportRowResult(
                        validatedTask.row().rowNumber(),
                        task.getId(),
                        task.getName(),
                        "QUEUED",
                        SyncTaskState.QUEUED.name(),
                        "同步任务已导入、发布并立即创建 MANUAL execution"));
                result.setQueuedCount(result.getQueuedCount() + 1);
            } else {
                result.getRows().add(new SyncTaskImportRowResult(
                        validatedTask.row().rowNumber(),
                        task.getId(),
                        task.getName(),
                        "CREATED_DRAFT",
                        SyncTaskState.DRAFT.name(),
                        "同步任务已导入为 DRAFT，后续需编辑/发布后才能执行"));
                result.setDraftCount(result.getDraftCount() + 1);
            }
            auditSupport.saveAudit(task.getTenantId(), task.getId(), task.getLastExecutionId(),
                    SyncAuditActionType.IMPORT_TASKS, actorContext,
                    "rowNumber=" + validatedTask.row().rowNumber()
                            + ",runImmediately=" + runImmediately
                            + ",taskName=" + task.getName());
            result.setCreatedCount(result.getCreatedCount() + 1);
        }
        result.setValidRows(validatedTasks.size());
        result.setStatus("IMPORTED");
        result.setMessage(runImmediately
                ? "同步任务导入成功，并已按选项立即创建执行"
                : "同步任务导入成功，任务已进入 DRAFT 编辑中");
        return result;
    }

    private List<SyncTask> queryExportTasks(SyncTaskQueryCriteria criteria, SyncActorContext actorContext) {
        SyncTaskQueryCriteria safeCriteria = criteria == null
                ? new SyncTaskQueryCriteria(null, null, null, null, null, null, null, null, null, 1L, (long) DEFAULT_EXPORT_SIZE)
                : criteria;
        SyncDataVisibility visibility = dataScopeSupport.resolveVisibility(
                safeCriteria.tenantId(), safeCriteria.projectId(), safeCriteria.workspaceId(), actorContext);
        LambdaQueryWrapper<SyncTask> wrapper = new LambdaQueryWrapper<SyncTask>()
                .orderByDesc(SyncTask::getUpdateTime)
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
        querySupport.eqIfPresent(wrapper, SyncTask::getTemplateId, safeCriteria.templateId());
        querySupport.eqIfPresent(wrapper, SyncTask::getOwnerId, safeCriteria.ownerId());
        querySupport.eqIfPresent(wrapper, SyncTask::getGroupCode,
                taskGroupOperationSupport.normalizeGroupCodeForFilter(safeCriteria.groupCode()));
        String requestedState = querySupport.normalizeCode(safeCriteria.currentState());
        if (requestedState == null) {
            wrapper.notIn(SyncTask::getCurrentState, SyncTaskState.RECYCLED.name(), SyncTaskState.DELETED.name());
        } else {
            wrapper.eq(SyncTask::getCurrentState, requestedState);
        }
        querySupport.eqIfPresent(wrapper, SyncTask::getApprovalState, querySupport.normalizeCode(safeCriteria.approvalState()));
        querySupport.eqIfPresent(wrapper, SyncTask::getTriggerType, querySupport.normalizeCode(safeCriteria.triggerType()));
        long safeCurrent = safeCriteria.current() == null || safeCriteria.current() <= 0 ? 1L : safeCriteria.current();
        long requestedSize = safeCriteria.size() == null || safeCriteria.size() <= 0 ? DEFAULT_EXPORT_SIZE : safeCriteria.size();
        long safeSize = Math.min(requestedSize, MAX_EXPORT_SIZE);
        Page<SyncTask> page = taskMapper.selectPage(new Page<>(safeCurrent, safeSize), wrapper);
        return page.getRecords();
    }

    private List<ValidatedImportTask> validateImportRows(List<SyncTaskDefinitionExchangeCodecSupport.TaskDefinitionImportRow> rows,
                                                         boolean runImmediately,
                                                         SyncActorContext actorContext,
                                                         SyncTaskImportResult result) {
        List<ValidatedImportTask> validatedTasks = new ArrayList<>();
        Set<String> namesInFile = new HashSet<>();
        for (SyncTaskDefinitionExchangeCodecSupport.TaskDefinitionImportRow row : rows) {
            try {
                ValidatedImportTask validatedTask = validateImportRow(row, runImmediately, actorContext, namesInFile);
                validatedTasks.add(validatedTask);
                result.getRows().add(new SyncTaskImportRowResult(
                        row.rowNumber(), null, validatedTask.name(), "VALIDATED", null,
                        "导入行校验通过"));
            } catch (ImportRowConflictException exception) {
                result.setConflictCount(result.getConflictCount() + 1);
                result.getRows().add(new SyncTaskImportRowResult(
                        row.rowNumber(), null, row.name(), "CONFLICT", null, exception.getMessage()));
            } catch (PlatformBusinessException exception) {
                result.setFailedCount(result.getFailedCount() + 1);
                result.getRows().add(new SyncTaskImportRowResult(
                        row.rowNumber(), null, row.name(), "FAILED", null, exception.getMessage()));
            }
        }
        result.setValidRows(validatedTasks.size());
        return validatedTasks;
    }

    private ValidatedImportTask validateImportRow(SyncTaskDefinitionExchangeCodecSupport.TaskDefinitionImportRow row,
                                                  boolean runImmediately,
                                                  SyncActorContext actorContext,
                                                  Set<String> namesInFile) {
        String name = normalizeName(row.name(), row.rowNumber());
        SyncTemplate template = templateMapper.selectById(row.templateId());
        if (template == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "第 " + row.rowNumber() + " 行引用的同步模板不存在，templateId=" + row.templateId());
        }
        dataScopeSupport.validateOwnedReadable(template.getTenantId(), template.getProjectId(),
                template.getCreatedBy(), actorContext, "同步模板");
        dataScopeSupport.validateProjectWritable(template.getTenantId(), template.getProjectId(),
                template.getWorkspaceId(), actorContext, "同步任务导入");
        validateDeclaredScope(row, template);
        templateValidationSupport.validateTemplate(template);
        SyncTemplateExecutionPrecheckResponse precheck = templateExecutionPrecheckSupport.precheck(template);
        if (!precheck.canCreateTaskDraft()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "第 " + row.rowNumber() + " 行模板当前不能创建任务草稿，precheckStatus=" + precheck.precheckStatus()
                            + "，issueCodes=" + precheck.issueCodes());
        }
        if (runImmediately && !precheck.canStartExecution()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "第 " + row.rowNumber() + " 行要求导入后立即执行，但模板当前不能直接执行，precheckStatus="
                            + precheck.precheckStatus() + "；请先导入为 DRAFT 或完成审批后再运行");
        }
        validateScheduleConfig(template, row.scheduleConfig(), row.rowNumber());
        SyncTaskGroupOperationSupport.TaskGroupAssignment groupAssignment =
                taskGroupOperationSupport.resolveAssignmentForTask(
                        template.getTenantId(),
                        template.getProjectId(),
                        template.getWorkspaceId(),
                        row.groupCode(),
                        row.groupName(),
                        actorContext);
        String uniqueKey = uniqueKey(template.getTenantId(), template.getProjectId(), name);
        if (!namesInFile.add(uniqueKey)) {
            throw new ImportRowConflictException("第 " + row.rowNumber()
                    + " 行与导入文件内其它行任务名称重复，请修改 name，uniqueKey=" + uniqueKey);
        }
        if (taskNameExists(template.getTenantId(), template.getProjectId(), name)) {
            throw new ImportRowConflictException("第 " + row.rowNumber()
                    + " 行任务名称与现有任务冲突，请修改 name 后重试，uniqueKey=" + uniqueKey);
        }
        return new ValidatedImportTask(
                row,
                template,
                precheck,
                name,
                normalizeDescription(row.description()),
                normalizePriority(row.priority(), row.rowNumber()),
                row.ownerId(),
                groupAssignment,
                querySupport.trimToNull(row.scheduleConfig()),
                normalizeRunMode(row.runMode()));
    }

    private SyncTask createDraftTask(ValidatedImportTask validatedTask, SyncActorContext actorContext) {
        SyncTemplate template = validatedTask.template();
        SyncTask task = new SyncTask();
        task.setTenantId(template.getTenantId());
        task.setProjectId(template.getProjectId());
        task.setWorkspaceId(template.getWorkspaceId());
        task.setTemplateId(template.getId());
        task.setGroupCode(validatedTask.groupAssignment().groupCode());
        task.setGroupName(validatedTask.groupAssignment().groupName());
        task.setName(validatedTask.name());
        task.setDescription(validatedTask.description());
        task.setCurrentState(SyncTaskState.DRAFT.name());
        task.setApprovalState(validatedTask.precheck().approvalRequired()
                ? SyncApprovalState.PENDING.name()
                : SyncApprovalState.NOT_REQUIRED.name());
        task.setPriority(validatedTask.priority());
        task.setScheduleConfig(validatedTask.scheduleConfig());
        task.setScheduleEnabled(false);
        task.setNextFireTime(null);
        task.setLastFireTime(null);
        task.setScheduleMisfireCount(0);
        task.setScheduleDispatchCount(0L);
        task.setScheduleVersion(0L);
        task.setRunMode(validatedTask.runMode());
        task.setTriggerType(SyncTriggerType.MANUAL.name());
        task.setOwnerId(validatedTask.ownerId() == null ? querySupport.actorId(actorContext) : validatedTask.ownerId());
        task.setLastExecutionId(null);
        task.setAttentionRequired(false);
        task.setAttentionReason(null);
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.insert(task);
        return task;
    }

    private void validateDeclaredScope(SyncTaskDefinitionExchangeCodecSupport.TaskDefinitionImportRow row,
                                       SyncTemplate template) {
        if (row.tenantId() != null && !row.tenantId().equals(template.getTenantId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "第 " + row.rowNumber() + " 行 tenantId 与模板租户不一致，rowTenantId="
                            + row.tenantId() + ", templateTenantId=" + template.getTenantId());
        }
        if (row.projectId() != null && !sameNullable(row.projectId(), template.getProjectId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "第 " + row.rowNumber() + " 行 projectId 与模板项目不一致，rowProjectId="
                            + row.projectId() + ", templateProjectId=" + template.getProjectId());
        }
        if (row.workspaceId() != null && !sameNullable(row.workspaceId(), template.getWorkspaceId())) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "第 " + row.rowNumber() + " 行 workspaceId 与模板工作空间不一致，rowWorkspaceId="
                            + row.workspaceId() + ", templateWorkspaceId=" + template.getWorkspaceId());
        }
    }

    private void validateScheduleConfig(SyncTemplate template, String scheduleConfig, int rowNumber) {
        String config = querySupport.trimToNull(scheduleConfig);
        if (config == null) {
            return;
        }
        String syncMode = querySupport.normalizeCode(template.getSyncMode());
        if (!SyncMode.FULL.name().equals(syncMode) && !SyncMode.SCHEDULED_BATCH.name().equals(syncMode)) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "第 " + rowNumber + " 行声明了 scheduleConfig，但当前自动调度仅支持 FULL 和 SCHEDULED_BATCH，syncMode=" + syncMode);
        }
        /*
         * 这里不是简单判断字符串非空，而是复用现有调度配置解析器检查 cron/interval/timezone/misfirePolicy。
         * 这样导入阶段暴露非法调度配置，避免后台 scheduler 之后反复跳过问题任务。
         */
        scheduleConfigSupport.parseRequired(config);
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

    private Long resolveAuditTenant(SyncTaskQueryCriteria criteria, SyncActorContext actorContext) {
        SyncDataVisibility visibility = dataScopeSupport.resolveVisibility(
                criteria == null ? null : criteria.tenantId(),
                criteria == null ? null : criteria.projectId(),
                criteria == null ? null : criteria.workspaceId(),
                actorContext);
        if (visibility.tenantId() != null) {
            return visibility.tenantId();
        }
        Long actorTenantId = actorContext == null ? null : actorContext.tenantId();
        return actorTenantId == null ? 0L : actorTenantId;
    }

    private List<Long> normalizeBatchExportTaskIds(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "批量导出任务 ID 列表不能为空");
        }
        if (taskIds.size() > MAX_BATCH_EXPORT_IDS) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "单次批量导出最多支持 " + MAX_BATCH_EXPORT_IDS + " 个同步任务，当前数量=" + taskIds.size());
        }
        Set<Long> deduplicated = new LinkedHashSet<>();
        for (Long taskId : taskIds) {
            if (taskId == null || taskId <= 0) {
                throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                        "批量导出任务 ID 必须是正整数，当前值=" + taskId);
            }
            if (!deduplicated.add(taskId)) {
                throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                        "批量导出任务 ID 不能重复，重复值=" + taskId);
            }
        }
        return deduplicated.stream().toList();
    }

    private Long resolveBatchAuditTenant(List<SyncTask> tasks, SyncActorContext actorContext) {
        Long actorTenantId = actorContext == null ? null : actorContext.tenantId();
        if (actorTenantId != null) {
            return actorTenantId;
        }
        if (tasks != null && !tasks.isEmpty() && tasks.get(0).getTenantId() != null) {
            return tasks.get(0).getTenantId();
        }
        return 0L;
    }

    private String normalizeName(String name, int rowNumber) {
        String trimmed = querySupport.trimToNull(name);
        if (trimmed == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "第 " + rowNumber + " 行缺少任务名称 name");
        }
        return querySupport.truncate(trimmed, 160);
    }

    private String normalizeDescription(String description) {
        return querySupport.truncate(querySupport.trimToNull(description), 1000);
    }

    private String normalizePriority(String priority, int rowNumber) {
        String normalized = querySupport.defaultText(priority, "MEDIUM").toUpperCase(Locale.ROOT);
        if (!ALLOWED_PRIORITIES.contains(normalized)) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "第 " + rowNumber + " 行 priority 仅支持 LOW、MEDIUM、HIGH、URGENT，当前值=" + priority);
        }
        return normalized;
    }

    private String normalizeRunMode(String runMode) {
        return querySupport.truncate(querySupport.defaultText(runMode, "IMPORTED_DRAFT").toUpperCase(Locale.ROOT), 64);
    }

    private String uniqueKey(Long tenantId, Long projectId, String name) {
        return tenantId + ":" + (projectId == null ? "NULL" : projectId) + ":" + name;
    }

    private boolean sameNullable(Long left, Long right) {
        return left == null ? right == null : left.equals(right);
    }

    /**
     * 单行导入校验通过后的内部对象。
     */
    private record ValidatedImportTask(SyncTaskDefinitionExchangeCodecSupport.TaskDefinitionImportRow row,
                                       SyncTemplate template,
                                       SyncTemplateExecutionPrecheckResponse precheck,
                                       String name,
                                       String description,
                                       String priority,
                                       Long ownerId,
                                       SyncTaskGroupOperationSupport.TaskGroupAssignment groupAssignment,
                                       String scheduleConfig,
                                       String runMode) {
    }

    private static class ImportRowConflictException extends RuntimeException {
        private ImportRowConflictException(String message) {
            super(message);
        }
    }
}

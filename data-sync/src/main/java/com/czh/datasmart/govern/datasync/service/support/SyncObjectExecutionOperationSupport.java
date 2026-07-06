/**
 * @Author : Cui
 * @Date: 2026/07/06 23:35
 * @Description DataSmart Govern Backend - SyncObjectExecutionOperationSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectExecutionQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectExecutionView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryResult;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncObjectExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncObjectExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncObjectExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 对象级执行账本查询与恢复操作组件。
 *
 * <p>为什么要单独拆出这个组件，而不是继续堆到 {@code DataSyncServiceImpl}：</p>
 * <p>1. 对象级账本属于“运行事实”，不属于模板或任务定义；</p>
 * <p>2. 选择性重试会同时改写对象状态、父 execution 状态、任务主状态和审计记录，规则比普通查询更复杂；</p>
 * <p>3. 后续如果继续扩展 splitPk 分片、对象级 checkpoint、脏数据落盘或 TaskGroup 并发，都会复用这里的
 * “明细查询 + 失败单元重置 + 父执行重新排队”能力。</p>
 *
 * <p>和 DataX 的类比：DataX 一个 Job 会被拆成多个 Task/Channel，失败 Task 通常可以单独重传。
 * 本项目当前还不是完整 DataX 引擎，但通过 {@code data_sync_object_execution} 先建立对象级事实账本，
 * 就可以让父 execution 从“整体失败/整体成功”的粗粒度，升级为“成功对象跳过、失败对象重传”的可恢复模型。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncObjectExecutionOperationSupport {

    private static final int DEFAULT_RETRY_ATTEMPT_BUDGET = 3;
    private static final int MAX_RETRY_ATTEMPT_BUDGET = 10;

    /**
     * 审计 reason 的兜底敏感词。
     *
     * <p>这里不是完整 DLP，只是避免明显的 SQL、凭据、连接串和样本数据进入审计摘要。
     * 生产环境如果要更严格，可以把这层替换为统一敏感信息检测组件或网关 DLP 过滤器。</p>
     */
    private static final Set<String> SENSITIVE_REASON_KEYWORDS = Set.of(
            "password",
            "token",
            "secret",
            "credential",
            "access_key",
            "private_key",
            "jdbc:",
            "sql",
            "where",
            "payload",
            "sample",
            "密码",
            "密钥",
            "令牌",
            "凭据",
            "样本",
            "连接串"
    );

    private final SyncObjectExecutionMapper objectExecutionMapper;
    private final SyncExecutionMapper executionMapper;
    private final SyncTaskMapper taskMapper;
    private final SyncQuerySupport querySupport;
    private final SyncAuditSupport auditSupport;

    /**
     * 分页查询某个父 execution 下的对象级执行明细。
     *
     * <p>调用方在进入本方法前应该已经通过 {@code DataSyncServiceImpl#getTask} 和 {@code getExecutionForTask}
     * 完成租户、项目、SELF 数据范围和父子归属校验。本方法仍会在 SQL 条件中加上 taskId/executionId/tenantId/projectId，
     * 形成防御式二次收敛，避免未来调用入口遗漏路径校验。</p>
     *
     * @param task 已完成权限校验的同步任务。
     * @param execution 已确认属于该任务的父 execution。
     * @param criteria 查询条件。
     * @return 对象级执行账本分页视图。
     */
    public PlatformPageResponse<SyncObjectExecutionView> pageObjectExecutions(SyncTask task,
                                                                              SyncExecution execution,
                                                                              SyncObjectExecutionQueryCriteria criteria) {
        LambdaQueryWrapper<SyncObjectExecution> wrapper = new LambdaQueryWrapper<SyncObjectExecution>()
                .eq(SyncObjectExecution::getSyncTaskId, task.getId())
                .eq(SyncObjectExecution::getExecutionId, execution.getId())
                .orderByAsc(SyncObjectExecution::getObjectOrdinal)
                .orderByAsc(SyncObjectExecution::getId);
        querySupport.eqIfPresent(wrapper, SyncObjectExecution::getTenantId, task.getTenantId());
        querySupport.eqIfPresent(wrapper, SyncObjectExecution::getProjectId, task.getProjectId());
        querySupport.eqIfPresent(wrapper, SyncObjectExecution::getWorkspaceId, task.getWorkspaceId());
        querySupport.eqIfPresent(wrapper, SyncObjectExecution::getObjectState,
                querySupport.normalizeCode(criteria.objectState()));
        querySupport.eqIfPresent(wrapper, SyncObjectExecution::getObjectOrdinal, criteria.objectOrdinal());

        Page<SyncObjectExecution> page = objectExecutionMapper.selectPage(
                querySupport.page(criteria.current(), criteria.size()), wrapper);
        List<SyncObjectExecutionView> views = page.getRecords().stream()
                .map(this::toView)
                .toList();
        return PlatformPageResponse.of(page.getCurrent(), page.getSize(), page.getTotal(), views);
    }

    /**
     * 将当前父 execution 下的失败对象重置为可重试状态，并把父 execution 放回 worker 队列。
     *
     * <p>业务流转说明：</p>
     * <p>1. 只允许从 PARTIALLY_SUCCEEDED 或 FAILED 父 execution 发起。RUNNING/QUEUED 仍在执行窗口内，
     * SUCCEEDED 没有失败对象需要恢复，CANCELLED 表达用户终止意图；</p>
     * <p>2. 只重置 FAILED 对象。SUCCEEDED 对象不会被改回 PENDING，避免重复写入目标端；</p>
     * <p>3. 重置失败对象后，父 execution 从终态回到 QUEUED，并清理 executor/lease/finishedAt；
     * worker 重新认领后，fan-out 会跳过 SUCCEEDED 对象，只执行 PENDING 失败对象；</p>
     * <p>4. 任务主状态标记为 RETRYING，运营后台可以清楚看到这是一次失败对象重传，而不是普通首次运行。</p>
     *
     * <p>幂等与风险说明：本方法不直接调用 datasource-management，不在 HTTP 请求线程里搬运数据。
     * 它只做控制面状态重置，因此可与 worker 租约模型复用。对于 APPEND 写入策略，失败对象重传仍可能在远端
     * “部分提交后失败”的场景造成重复写入；生产上建议优先使用 UPSERT、主键去重或对象级 checkpoint。</p>
     */
    public SyncObjectRetryResult retryFailedObjects(SyncTask task,
                                                    SyncExecution execution,
                                                    SyncObjectRetryRequest request,
                                                    SyncActorContext actorContext) {
        assertExecutionRetryable(execution);
        List<SyncObjectExecution> rows = objectExecutionMapper.selectByExecutionId(execution.getId());
        if (rows == null || rows.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "当前 execution 没有对象级执行账本，无法执行失败对象选择性重试，executionId=" + execution.getId());
        }

        List<SyncObjectExecution> selectedRows = selectFailedRows(rows, request);
        int retryBudget = resolveRetryBudget(request, selectedRows);
        boolean resetAttemptCount = request == null || !Boolean.FALSE.equals(request.getResetAttemptCount());
        LocalDateTime now = LocalDateTime.now();
        for (SyncObjectExecution row : selectedRows) {
            resetFailedObject(row, retryBudget, resetAttemptCount, now);
        }

        String reason = sanitizeReason(request == null ? null : request.getReason());
        int executionUpdated = executionMapper.requeueTerminalObjectLevelRetry(
                execution.getId(), "OBJECT_LEVEL_RETRY: " + reason);
        if (executionUpdated == 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "父 execution 状态已变化，无法重新排队失败对象，executionId=" + execution.getId());
        }
        int taskUpdated = taskMapper.markLifecycleState(
                task.getId(),
                SyncTaskState.RETRYING.name(),
                SyncTriggerType.MANUAL.name(),
                execution.getId());
        if (taskUpdated == 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "同步任务状态更新失败，无法完成失败对象重试排队，taskId=" + task.getId());
        }

        auditSupport.saveAudit(task.getTenantId(), task.getId(), execution.getId(),
                SyncAuditActionType.RETRY_OBJECT_EXECUTIONS,
                actorContext,
                auditPayload(selectedRows, reason, retryBudget, resetAttemptCount));
        return new SyncObjectRetryResult(
                task.getId(),
                execution.getId(),
                selectedRows.size(),
                SyncExecutionState.QUEUED.name(),
                SyncTaskState.RETRYING.name(),
                List.of("OBJECT_LEVEL_RETRY_REQUEUED", "FAILED_OBJECTS_RESET_TO_PENDING"),
                "已将 " + selectedRows.size() + " 个失败对象重置为可重试状态，父 execution 已重新进入 worker 队列"
        );
    }

    private void assertExecutionRetryable(SyncExecution execution) {
        String state = execution == null ? null : normalize(execution.getExecutionState());
        if (!SyncExecutionState.PARTIALLY_SUCCEEDED.name().equals(state)
                && !SyncExecutionState.FAILED.name().equals(state)) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "只有 PARTIALLY_SUCCEEDED 或 FAILED execution 支持失败对象选择性重试，currentState=" + state);
        }
    }

    private List<SyncObjectExecution> selectFailedRows(List<SyncObjectExecution> rows,
                                                       SyncObjectRetryRequest request) {
        Set<Long> selectedIds = new LinkedHashSet<>(request == null || request.getObjectExecutionIds() == null
                ? List.of()
                : request.getObjectExecutionIds());
        Set<Integer> selectedOrdinals = new LinkedHashSet<>(request == null || request.getObjectOrdinals() == null
                ? List.of()
                : request.getObjectOrdinals());
        boolean hasExplicitSelection = !selectedIds.isEmpty() || !selectedOrdinals.isEmpty();
        List<SyncObjectExecution> selectedRows = new ArrayList<>();
        List<String> invalidSelections = new ArrayList<>();
        for (SyncObjectExecution row : rows) {
            boolean failed = SyncObjectExecutionState.FAILED.name().equals(normalize(row.getObjectState()));

            /*
             * 默认场景：操作者没有传 objectExecutionIds/objectOrdinals。
             * 这时产品语义不是“选择当前 execution 下的所有对象并要求它们都失败”，而是
             * “自动找出所有 FAILED 对象进行恢复”。已经 SUCCEEDED 的对象必须保持跳过，
             * 否则会把成功表重新置为 PENDING，进而在 APPEND 写入策略下放大重复写入风险。
             */
            if (!hasExplicitSelection) {
                if (failed) {
                    selectedRows.add(row);
                }
                continue;
            }

            /*
             * 显式选择场景：操作者明确指定了对象 ID 或对象序号。
             * 这时如果选中了 SUCCEEDED/RUNNING/SKIPPED 等非失败对象，说明请求本身有风险：
             * 可能是前端误选、脚本误传，或者操作者想重跑成功对象。为了保持对象级重试的
             * 幂等边界，本接口只允许 FAILED 对象进入重试，其他重跑需求后续应走“强制重跑”
             * 或“回放/补数”这类带审批和去重策略的高风险操作。
             */
            boolean selected = selectedIds.contains(row.getId())
                    || selectedOrdinals.contains(row.getObjectOrdinal());
            if (!selected) {
                continue;
            }
            if (!failed) {
                invalidSelections.add("id=" + row.getId() + ",ordinal=" + row.getObjectOrdinal()
                        + ",state=" + row.getObjectState());
                continue;
            }
            selectedRows.add(row);
        }
        validateExplicitSelectionMatched(rows, selectedIds, selectedOrdinals);
        if (!invalidSelections.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "只能选择 FAILED 对象进行重试，以下对象不是失败状态: " + invalidSelections);
        }
        if (selectedRows.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    hasExplicitSelection
                            ? "选择范围内没有 FAILED 对象，无法发起对象级重试"
                            : "当前 execution 下没有 FAILED 对象，无法发起对象级重试");
        }
        return selectedRows;
    }

    private void validateExplicitSelectionMatched(List<SyncObjectExecution> rows,
                                                  Set<Long> selectedIds,
                                                  Set<Integer> selectedOrdinals) {
        if (!selectedIds.isEmpty()) {
            Set<Long> existingIds = new LinkedHashSet<>();
            rows.forEach(row -> existingIds.add(row.getId()));
            Set<Long> missingIds = new LinkedHashSet<>(selectedIds);
            missingIds.removeAll(existingIds);
            if (!missingIds.isEmpty()) {
                throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                        "请求中包含不属于当前 execution 的 objectExecutionIds: " + missingIds);
            }
        }
        if (!selectedOrdinals.isEmpty()) {
            Set<Integer> existingOrdinals = new LinkedHashSet<>();
            rows.forEach(row -> existingOrdinals.add(row.getObjectOrdinal()));
            Set<Integer> missingOrdinals = new LinkedHashSet<>(selectedOrdinals);
            missingOrdinals.removeAll(existingOrdinals);
            if (!missingOrdinals.isEmpty()) {
                throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                        "请求中包含不属于当前 execution 的 objectOrdinals: " + missingOrdinals);
            }
        }
    }

    private int resolveRetryBudget(SyncObjectRetryRequest request, List<SyncObjectExecution> selectedRows) {
        Integer requestedBudget = request == null ? null : request.getRetryAttemptBudget();
        if (requestedBudget != null) {
            return clamp(requestedBudget, 1, MAX_RETRY_ATTEMPT_BUDGET);
        }
        return selectedRows.stream()
                .map(SyncObjectExecution::getMaxAttemptCount)
                .filter(value -> value != null && value > 0)
                .findFirst()
                .map(value -> clamp(value, 1, MAX_RETRY_ATTEMPT_BUDGET))
                .orElse(DEFAULT_RETRY_ATTEMPT_BUDGET);
    }

    private void resetFailedObject(SyncObjectExecution row,
                                   int retryBudget,
                                   boolean resetAttemptCount,
                                   LocalDateTime now) {
        row.setObjectState(SyncObjectExecutionState.PENDING.name());
        row.setAttemptCount(resetAttemptCount ? 0 : Math.min(safeInt(row.getAttemptCount()), retryBudget - 1));
        row.setMaxAttemptCount(retryBudget);
        row.setRecordsRead(0L);
        row.setRecordsWritten(0L);
        row.setFailedRecordCount(0L);
        row.setStartedAt(null);
        row.setFinishedAt(null);
        row.setUpdateTime(now);
        objectExecutionMapper.updateById(row);
    }

    private String auditPayload(List<SyncObjectExecution> rows,
                                String reason,
                                int retryBudget,
                                boolean resetAttemptCount) {
        List<Integer> ordinals = rows.stream()
                .map(SyncObjectExecution::getObjectOrdinal)
                .toList();
        return "action=retryObjectExecutions"
                + ",objectCount=" + rows.size()
                + ",objectOrdinals=" + ordinals
                + ",retryAttemptBudget=" + retryBudget
                + ",resetAttemptCount=" + resetAttemptCount
                + ",reason=" + reason;
    }

    private SyncObjectExecutionView toView(SyncObjectExecution row) {
        return new SyncObjectExecutionView(
                row.getId(),
                row.getTenantId(),
                row.getProjectId(),
                row.getWorkspaceId(),
                row.getSyncTaskId(),
                row.getExecutionId(),
                row.getTemplateId(),
                row.getObjectOrdinal(),
                row.getSourceSchemaName(),
                row.getSourceObjectName(),
                row.getTargetSchemaName(),
                row.getTargetObjectName(),
                row.getObjectState(),
                row.getAttemptCount(),
                row.getMaxAttemptCount(),
                row.getRecordsRead(),
                row.getRecordsWritten(),
                row.getFailedRecordCount(),
                row.getLastErrorType(),
                row.getLastErrorCode(),
                row.getLastErrorMessage(),
                row.getStartedAt(),
                row.getFinishedAt(),
                row.getPayloadPolicy(),
                row.getCreateTime(),
                row.getUpdateTime()
        );
    }

    private String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "运维人员发起失败对象选择性重试";
        }
        String compact = reason.trim().replaceAll("\\s+", " ");
        String lower = compact.toLowerCase(Locale.ROOT);
        for (String keyword : SENSITIVE_REASON_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return "重试原因包含敏感关键字，已按审计低敏策略脱敏";
            }
        }
        return querySupport.truncate(compact, 500);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private int clamp(int value, int minValue, int maxValue) {
        return Math.max(minValue, Math.min(value, maxValue));
    }
}

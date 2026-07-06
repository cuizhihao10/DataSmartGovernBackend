/**
 * @Author : Cui
 * @Date: 2026/07/08 00:08
 * @Description DataSmart Govern Backend - SyncDirtyRecordReplaySupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordReplayRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordReplayResult;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncExecutionRecoveryPlan;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncErrorSampleMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionRecoveryPlanMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import com.czh.datasmart.govern.datasync.support.SyncTaskState;
import com.czh.datasmart.govern.datasync.support.SyncTriggerType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 脏数据修复重放支撑组件。
 *
 * <p>DataX 的脏数据阈值思想解决的是“少量坏数据不要拖垮整批任务”；
 * 但商业化产品还要继续回答“坏数据被记录后，用户如何修复、如何受控重放、如何留下审计证据”。
 * 本组件负责把 {@code data_sync_error_sample} 中的结构化错误样本转化为一次新的 replay 恢复计划。</p>
 *
 * <p>本组件刻意只做控制面动作，不直接读写源端或目标端：</p>
 * <p>1. 查询错误样本时复用任务、execution、租户、项目和工作空间范围，避免跨租户/跨项目修复；</p>
 * <p>2. 默认只允许 retryable=true 的样本进入修复重放，防止未修复配置时盲目重跑；</p>
 * <p>3. 新建 replay execution 与 recovery plan，后续 worker 通过恢复计划里的 selector 再执行真实搬运；</p>
 * <p>4. selector 只保存错误样本 ID、数量、来源 execution 和修复策略摘要，不保存原始坏行、SQL 或凭据。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncDirtyRecordReplaySupport {

    private static final int DEFAULT_MAX_SAMPLE_COUNT = 100;
    private static final int HARD_MAX_SAMPLE_COUNT = 200;
    private static final int REPAIR_STRATEGY_MAX_LENGTH = 80;
    private static final int REASON_MAX_LENGTH = 500;

    private static final String SELECTOR_MODE_SELECTED_IDS = "SELECTED_IDS";
    private static final String SELECTOR_MODE_ALL_RETRYABLE = "ALL_RETRYABLE_IN_EXECUTION";

    /**
     * 支持发起脏数据修复重放的任务稳定态。
     *
     * <p>SUCCEEDED 被允许是因为“脏数据阈值内继续成功”是 DataX-style 批处理的合理结果：
     * 整体 execution 可以成功，但其中仍有少量 dirty sample 等待后续修复。</p>
     */
    private static final Set<SyncTaskState> ALLOWED_TASK_STATES = Set.of(
            SyncTaskState.SUCCEEDED,
            SyncTaskState.PARTIALLY_SUCCEEDED,
            SyncTaskState.FAILED,
            SyncTaskState.AWAITING_OPERATOR_ACTION,
            SyncTaskState.CANCELLED
    );

    /**
     * 支持作为脏数据来源的 execution 稳定态。
     *
     * <p>RUNNING/QUEUED/RETRYING 不允许作为来源，因为错误样本和 checkpoint 仍可能继续变化。
     * 先等待当前执行进入稳定状态，再派生修复重放，审计和数据一致性都更清楚。</p>
     */
    private static final Set<String> STABLE_SOURCE_EXECUTION_STATES = Set.of(
            SyncExecutionState.SUCCEEDED.name(),
            SyncExecutionState.PARTIALLY_SUCCEEDED.name(),
            SyncExecutionState.FAILED.name(),
            SyncExecutionState.CANCELLED.name()
    );

    private static final Set<String> SENSITIVE_KEYWORDS = Set.of(
            "password",
            "passwd",
            "token",
            "secret",
            "credential",
            "access_key",
            "private_key",
            "jdbc:",
            "select ",
            "insert ",
            "update ",
            "delete ",
            "where ",
            "sql",
            "prompt",
            "payload",
            "sample payload",
            "密码",
            "密钥",
            "令牌",
            "凭据",
            "连接串"
    );

    private final SyncErrorSampleMapper errorSampleMapper;
    private final SyncExecutionMapper executionMapper;
    private final SyncExecutionRecoveryPlanMapper recoveryPlanMapper;
    private final SyncTaskMapper taskMapper;
    private final SyncExecutionCreationSupport executionCreationSupport;
    private final SyncAuditSupport auditSupport;
    private final ObjectMapper objectMapper;

    /**
     * 创建脏数据修复重放计划。
     *
     * @param task 已由上层服务完成数据范围校验的同步任务。
     * @param request 修复重放请求。
     * @param actorContext 操作者上下文，用于新 execution 的 triggeredBy 与审计记录。
     * @return 低敏重放创建结果。
     */
    public SyncDirtyRecordReplayResult replayDirtyRecords(SyncTask task,
                                                          SyncDirtyRecordReplayRequest request,
                                                          SyncActorContext actorContext) {
        assertTaskStable(task);
        SyncDirtyRecordReplayRequest safeRequest = request == null ? new SyncDirtyRecordReplayRequest() : request;
        assertRepairConfirmed(safeRequest);
        SyncExecution sourceExecution = loadStableSourceExecution(task, safeRequest.getExecutionId());
        SampleSelection selection = selectSamples(task, sourceExecution, safeRequest);
        String repairStrategy = sanitizeRepairStrategy(safeRequest.getRepairStrategy());
        String reason = sanitizeReason(safeRequest.getReason());

        SyncExecution replayExecution = executionCreationSupport.createQueuedExecution(
                task, actorContext, SyncTriggerType.REPLAY);
        String selectorJson = buildSelectorJson(sourceExecution, selection, repairStrategy, actorContext);
        SyncExecutionRecoveryPlan plan = createRecoveryPlan(
                task, sourceExecution, replayExecution, selectorJson, reason);
        markTaskQueued(task, replayExecution);
        auditSupport.saveAudit(task.getTenantId(), task.getId(), replayExecution.getId(),
                SyncAuditActionType.REPLAY_DIRTY_RECORDS,
                actorContext,
                auditPayload(selection, sourceExecution, replayExecution, plan, repairStrategy, reason));

        List<String> warnings = new ArrayList<>(selection.warnings());
        if (SELECTOR_MODE_ALL_RETRYABLE.equals(selection.selectorMode())
                && selection.samples().size() == effectiveMaxSampleCount(safeRequest)) {
            warnings.add("DIRTY_RECORD_REPLAY_REACHED_MAX_SAMPLE_COUNT_CHECK_IF_MORE_RETRYABLE_EXISTS");
        }
        return new SyncDirtyRecordReplayResult(
                task.getId(),
                sourceExecution.getId(),
                replayExecution.getId(),
                plan.getId(),
                selection.samples().size(),
                selection.selectorMode(),
                SyncTaskState.QUEUED.name(),
                List.copyOf(warnings),
                "脏数据修复重放计划已创建，worker 后续会按低敏 selector 受控重放选中的错误样本"
        );
    }

    private void assertTaskStable(SyncTask task) {
        SyncTaskState state = parseTaskState(task == null ? null : task.getCurrentState());
        if (!ALLOWED_TASK_STATES.contains(state)) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "当前任务仍处于活跃或不可修复重放状态，不能发起脏数据修复重放，currentState=" + state);
        }
    }

    private SyncTaskState parseTaskState(String state) {
        if (state == null || state.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, "同步任务状态不能为空");
        }
        try {
            return SyncTaskState.valueOf(state.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "未知同步任务状态: " + state);
        }
    }

    private void assertRepairConfirmed(SyncDirtyRecordReplayRequest request) {
        if (!Boolean.TRUE.equals(request.getRepairConfirmed())) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "脏数据修复重放必须显式确认 repairConfirmed=true，避免未修复字段映射、目标约束或坏数据内容时重复失败");
        }
    }

    private SyncExecution loadStableSourceExecution(SyncTask task, Long executionId) {
        if (executionId == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "脏数据修复重放必须指定来源 executionId");
        }
        SyncExecution execution = executionMapper.selectById(executionId);
        if (execution == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                    "来源同步执行记录不存在: " + executionId);
        }
        boolean owned = Objects.equals(task.getId(), execution.getSyncTaskId())
                && Objects.equals(task.getTenantId(), execution.getTenantId())
                && Objects.equals(task.getProjectId(), execution.getProjectId())
                && Objects.equals(task.getWorkspaceId(), execution.getWorkspaceId());
        if (!owned) {
            throw new PlatformBusinessException(PlatformErrorCode.TENANT_SCOPE_DENIED,
                    "来源 execution 不属于当前任务、租户、项目或工作空间，已拒绝脏数据修复重放");
        }
        if (!STABLE_SOURCE_EXECUTION_STATES.contains(normalize(execution.getExecutionState()))) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "来源 execution 尚未进入稳定终态，不能作为脏数据修复重放来源，executionState="
                            + execution.getExecutionState());
        }
        return execution;
    }

    private SampleSelection selectSamples(SyncTask task,
                                          SyncExecution execution,
                                          SyncDirtyRecordReplayRequest request) {
        List<Long> requestedIds = normalizeIds(request.getErrorSampleIds());
        boolean replayAllRetryable = Boolean.TRUE.equals(request.getReplayAllRetryableInExecution());
        if ((requestedIds.isEmpty() && !replayAllRetryable) || (!requestedIds.isEmpty() && replayAllRetryable)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "脏数据修复重放必须二选一：传 errorSampleIds 精确重放，或 replayAllRetryableInExecution=true 全选可重试样本");
        }
        if (replayAllRetryable) {
            return selectAllRetryableSamples(task, execution, effectiveMaxSampleCount(request));
        }
        return selectExplicitSamples(task, execution, requestedIds);
    }

    private SampleSelection selectAllRetryableSamples(SyncTask task, SyncExecution execution, int limit) {
        LambdaQueryWrapper<SyncErrorSample> wrapper = baseSampleScope(task, execution)
                .eq(SyncErrorSample::getRetryable, true)
                .orderByAsc(SyncErrorSample::getId)
                .last("LIMIT " + limit);
        List<SyncErrorSample> samples = errorSampleMapper.selectList(wrapper);
        if (samples == null || samples.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "来源 execution 下没有可重试脏数据样本，无法创建修复重放计划");
        }
        return new SampleSelection(SELECTOR_MODE_ALL_RETRYABLE, samples, List.of());
    }

    private SampleSelection selectExplicitSamples(SyncTask task, SyncExecution execution, List<Long> requestedIds) {
        if (requestedIds.size() > HARD_MAX_SAMPLE_COUNT) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "单次脏数据修复重放最多选择 " + HARD_MAX_SAMPLE_COUNT + " 条错误样本，当前选择数量=" + requestedIds.size());
        }
        List<SyncErrorSample> samples = errorSampleMapper.selectList(baseSampleScope(task, execution)
                .in(SyncErrorSample::getId, requestedIds));
        samples = samples == null ? List.of() : samples;
        List<Long> loadedIds = samples.stream().map(SyncErrorSample::getId).toList();
        List<Long> missingIds = requestedIds.stream()
                .filter(id -> !loadedIds.contains(id))
                .toList();
        if (!missingIds.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "请求中包含不属于当前任务/execution 的错误样本 ID: " + missingIds);
        }
        List<Long> notRetryableIds = samples.stream()
                .filter(sample -> !Boolean.TRUE.equals(sample.getRetryable()))
                .map(SyncErrorSample::getId)
                .toList();
        if (!notRetryableIds.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "以下错误样本 retryable=false，必须先人工修复或走更高风险审批流程: " + notRetryableIds);
        }
        if (samples.isEmpty()) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "未找到可用于修复重放的错误样本");
        }
        return new SampleSelection(SELECTOR_MODE_SELECTED_IDS, samples, List.of());
    }

    private LambdaQueryWrapper<SyncErrorSample> baseSampleScope(SyncTask task, SyncExecution execution) {
        LambdaQueryWrapper<SyncErrorSample> wrapper = new LambdaQueryWrapper<SyncErrorSample>()
                .eq(SyncErrorSample::getSyncTaskId, task.getId())
                .eq(SyncErrorSample::getExecutionId, execution.getId())
                .eq(SyncErrorSample::getTenantId, task.getTenantId());
        eqOrIsNull(wrapper, SyncErrorSample::getProjectId, task.getProjectId());
        eqOrIsNull(wrapper, SyncErrorSample::getWorkspaceId, task.getWorkspaceId());
        return wrapper;
    }

    private <T> void eqOrIsNull(LambdaQueryWrapper<SyncErrorSample> wrapper,
                                com.baomidou.mybatisplus.core.toolkit.support.SFunction<SyncErrorSample, T> column,
                                T value) {
        if (value == null) {
            wrapper.isNull(column);
            return;
        }
        wrapper.eq(column, value);
    }

    private List<Long> normalizeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0) {
                throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                        "errorSampleIds 只能包含正整数 ID");
            }
            normalized.add(id);
        }
        return List.copyOf(normalized);
    }

    private int effectiveMaxSampleCount(SyncDirtyRecordReplayRequest request) {
        Integer requested = request.getMaxSampleCount();
        if (requested == null) {
            return DEFAULT_MAX_SAMPLE_COUNT;
        }
        return Math.max(1, Math.min(requested, HARD_MAX_SAMPLE_COUNT));
    }

    private String buildSelectorJson(SyncExecution sourceExecution,
                                     SampleSelection selection,
                                     String repairStrategy,
                                     SyncActorContext actorContext) {
        Map<String, Object> selector = new LinkedHashMap<>();
        selector.put("selectorVersion", "1.0");
        selector.put("selectorMode", selection.selectorMode());
        selector.put("sourceExecutionId", sourceExecution.getId());
        selector.put("sampleCount", selection.samples().size());
        selector.put("errorSampleIds", selection.samples().stream().map(SyncErrorSample::getId).toList());
        selector.put("repairStrategy", repairStrategy);
        selector.put("createdBy", actorContext == null ? null : actorContext.actorId());
        selector.put("lowSensitiveOnly", true);
        try {
            String json = objectMapper.writeValueAsString(selector);
            if (json.length() > 4000) {
                throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                        "脏数据修复重放 selector 过大，请减少单次选择的错误样本数量");
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.INTERNAL_ERROR,
                    "脏数据修复重放 selector 序列化失败");
        }
    }

    private SyncExecutionRecoveryPlan createRecoveryPlan(SyncTask task,
                                                         SyncExecution sourceExecution,
                                                         SyncExecution replayExecution,
                                                         String selectorJson,
                                                         String reason) {
        SyncExecutionRecoveryPlan plan = new SyncExecutionRecoveryPlan();
        plan.setTenantId(task.getTenantId());
        plan.setProjectId(task.getProjectId());
        plan.setWorkspaceId(task.getWorkspaceId());
        plan.setSyncTaskId(task.getId());
        plan.setExecutionId(replayExecution.getId());
        plan.setRecoveryType(SyncTriggerType.REPLAY.name());
        plan.setSourceExecutionId(sourceExecution.getId());
        plan.setSourceCheckpointId(null);
        plan.setWindowStart(null);
        plan.setWindowEnd(null);
        plan.setShardOrPartition("DIRTY_RECORD_REPLAY");
        plan.setErrorSampleSelector(selectorJson);
        plan.setReason(reason);
        plan.setPlanState("CREATED");
        plan.setCreateTime(LocalDateTime.now());
        plan.setUpdateTime(LocalDateTime.now());
        recoveryPlanMapper.insert(plan);
        return plan;
    }

    private void markTaskQueued(SyncTask task, SyncExecution replayExecution) {
        int updated = taskMapper.markLifecycleState(
                task.getId(),
                SyncTaskState.QUEUED.name(),
                SyncTriggerType.REPLAY.name(),
                replayExecution.getId());
        if (updated == 0) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "脏数据修复重放计划创建后更新任务状态失败，taskId=" + task.getId()
                            + ", executionId=" + replayExecution.getId());
        }
    }

    private String sanitizeRepairStrategy(String value) {
        String compact = compact(value);
        if (compact == null) {
            return "MANUAL_REPAIR_CONFIRMED_AND_REPLAY";
        }
        assertNoSensitiveKeyword(compact, "repairStrategy");
        String normalized = compact.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_\\-]", "_");
        return truncate(normalized, REPAIR_STRATEGY_MAX_LENGTH);
    }

    private String sanitizeReason(String value) {
        String compact = compact(value);
        if (compact == null) {
            return "操作者确认脏数据已修复，发起错误样本修复重放";
        }
        if (containsSensitiveKeyword(compact)) {
            return "修复重放原因包含敏感关键词，已按审计低敏策略脱敏";
        }
        return truncate(compact, REASON_MAX_LENGTH);
    }

    private void assertNoSensitiveKeyword(String value, String fieldName) {
        if (containsSensitiveKeyword(value)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    fieldName + " 包含敏感关键词，不能写入脏数据修复重放计划");
        }
    }

    private boolean containsSensitiveKeyword(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String keyword : SENSITIVE_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String compact(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String auditPayload(SampleSelection selection,
                                SyncExecution sourceExecution,
                                SyncExecution replayExecution,
                                SyncExecutionRecoveryPlan plan,
                                String repairStrategy,
                                String reason) {
        return "action=replayDirtyRecords"
                + ",selectorMode=" + selection.selectorMode()
                + ",sampleCount=" + selection.samples().size()
                + ",sourceExecutionId=" + sourceExecution.getId()
                + ",replayExecutionId=" + replayExecution.getId()
                + ",recoveryPlanId=" + plan.getId()
                + ",repairStrategy=" + repairStrategy
                + ",reason=" + reason;
    }

    /**
     * 错误样本选择结果。
     *
     * <p>record 让 selectorMode、样本集合和提示信息作为一个整体传递，避免后续方法只拿到样本列表后忘记区分
     * “精确选择”和“全选可重试样本”两种业务语义。</p>
     */
    private record SampleSelection(String selectorMode,
                                   List<SyncErrorSample> samples,
                                   List<String> warnings) {
    }
}

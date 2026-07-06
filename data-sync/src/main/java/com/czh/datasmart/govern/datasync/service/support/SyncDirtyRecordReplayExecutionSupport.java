/**
 * @Author : Cui
 * @Date: 2026/07/08 01:12
 * @Description DataSmart Govern Backend - SyncDirtyRecordReplayExecutionSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionPartialSuccessRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncRecoveryPlanWorkerResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceResponse;
import com.czh.datasmart.govern.datasync.mapper.SyncErrorSampleMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 脏数据修复重放执行支撑组件。
 *
 * <p>上一层 {@link SyncDirtyRecordReplaySupport} 负责“用户确认已修复 -> 创建 replay execution ->
 * 写入低敏 errorSampleSelector”；本组件负责更靠近 worker 的下一步：把 selector 指向的错误样本转换成
 * 可执行的、受权限约束的、PreparedStatement 参数化的行级重放。</p>
 *
 * <p>为什么这里采用“逐样本小批次”而不是一次性拼 IN/OR：</p>
 * <p>1. 当前 JDBC 方言层只开放 AND 条件和 EQ/GTE/LT 等基础操作符，尚未开放 IN/OR，是为了避免 where 表达式过早复杂化；</p>
 * <p>2. dirty replay 单次样本数有上限，逐样本执行虽然吞吐不如批量 IN，但更容易定位哪条样本仍失败；</p>
 * <p>3. 每个样本都只生成 {@code primaryKeyColumn EQ ?} 条件，值通过 datasource-management 的 PreparedStatement 绑定，
 * 不会把 SQL、连接串、凭据或完整行数据放进控制面。</p>
 *
 * <p>当前可执行边界：</p>
 * <p>1. 只支持样本 sourceRecordKey 为 {@code PRIMARY_KEY_EQ} 的单字段主键定位；</p>
 * <p>2. 旧样本如果只有 rowHash/rowIndex，会被标记为不可重放，因为这些信息不能稳定查询源端行；</p>
 * <p>3. 若部分样本成功、部分样本仍失败，父 execution 进入 PARTIALLY_SUCCEEDED，便于后续继续修复剩余样本。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncDirtyRecordReplayExecutionSupport {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final String DIRTY_RECORD_REPLAY = "DIRTY_RECORD_REPLAY";

    private final SyncErrorSampleMapper errorSampleMapper;
    private final SyncBatchRunnerBridgePlanSupport bridgePlanSupport;
    private final SyncBatchRunOnceDispatchService runOnceDispatchService;
    private final SyncExecutionLifecycleSupport lifecycleSupport;
    private final DataSyncTaskManagementReceiptPublisher receiptPublisher;
    private final ObjectMapper objectMapper;

    /**
     * 判断 worker 恢复计划是否属于“脏数据修复重放”。
     *
     * <p>普通 replay/backfill 可能只带 checkpoint、window 或 shard；只有 errorSampleSelector 存在时，
     * 才表示本组件应该接管执行，并把样本 ID 转成主键过滤条件。</p>
     */
    public boolean supports(SyncRecoveryPlanWorkerResult recoveryPlan) {
        return recoveryPlan != null
                && recoveryPlan.hasRecoveryPlan()
                && hasText(recoveryPlan.errorSampleSelector());
    }

    /**
     * 执行脏数据修复重放。
     *
     * @param execution 当前已被 worker claim 的 replay execution。
     * @param task 当前同步任务。
     * @param template 同步模板，提供源/目标对象、字段映射和写入策略。
     * @param workerPlan claim 阶段生成的低敏 worker 执行计划。
     * @param recoveryPlan worker 已 claim/consume 的恢复计划低敏结果。
     * @param actorContext 当前服务账号上下文。
     * @return 离线 Runner 统一调度摘要。
     */
    public SyncOfflineRunnerDispatchResult dispatchDirtyRecordReplay(SyncExecution execution,
                                                                     SyncTask task,
                                                                     SyncTemplate template,
                                                                     SyncWorkerExecutionPlanView workerPlan,
                                                                     SyncRecoveryPlanWorkerResult recoveryPlan,
                                                                     SyncActorContext actorContext) {
        SyncBatchRunnerBridgePlan bridgePlan = bridgePlanSupport.buildPlan(execution, task, template, workerPlan);
        if (!bridgePlan.isDispatchable()) {
            return failReplay(task, execution, actorContext,
                    false,
                    0L,
                    0L,
                    1L,
                    "DIRTY_RECORD_REPLAY_BRIDGE_PLAN_BLOCKED",
                    "DIRTY_RECORD_REPLAY_BRIDGE_PLAN_BLOCKED",
                    "脏数据修复重放的基础 bridge plan 被阻断，不能生成受控主键过滤条件执行",
                    bridgePlan.getIssueCodes());
        }

        DirtyReplaySelector selector = parseSelector(recoveryPlan);
        List<SyncErrorSample> samples = loadSamples(task, selector);
        List<DirtySampleReplayOutcome> outcomes = new ArrayList<>();
        for (SyncErrorSample sample : samples) {
            outcomes.add(dispatchOneSample(task, execution, actorContext, bridgePlan, sample));
        }
        return aggregateReplayResult(task, execution, actorContext, outcomes);
    }

    /**
     * 解析控制面写入的低敏 selector。
     *
     * <p>selector 只允许表达样本 ID、来源 execution 和选择模式，不允许携带 SQL、where 条件或行数据。
     * 真正的主键条件来自受权限约束查询到的 {@code data_sync_error_sample.source_record_key}。</p>
     */
    private DirtyReplaySelector parseSelector(SyncRecoveryPlanWorkerResult recoveryPlan) {
        try {
            JsonNode root = objectMapper.readTree(recoveryPlan.errorSampleSelector());
            Long sourceExecutionId = root.path("sourceExecutionId").isIntegralNumber()
                    ? root.path("sourceExecutionId").longValue()
                    : null;
            List<Long> sampleIds = new ArrayList<>();
            JsonNode ids = root.path("errorSampleIds");
            if (ids.isArray()) {
                ids.forEach(id -> {
                    if (id.isIntegralNumber() && id.longValue() > 0) {
                        sampleIds.add(id.longValue());
                    }
                });
            }
            if (sourceExecutionId == null || sampleIds.isEmpty()) {
                throw new IllegalArgumentException("selector 缺少 sourceExecutionId 或 errorSampleIds");
            }
            return new DirtyReplaySelector(sourceExecutionId, distinct(sampleIds));
        } catch (Exception exception) {
            throw new IllegalArgumentException("脏数据修复重放 selector 无法解析", exception);
        }
    }

    /**
     * 按 selector 重新查询错误样本。
     *
     * <p>不能直接相信 selector 中的 ID。这里必须重新绑定 task、tenant、project、workspace 和 sourceExecutionId，
     * 防止旧计划、人工脚本或异常数据把其它任务/租户的错误样本混入当前 replay。</p>
     */
    private List<SyncErrorSample> loadSamples(SyncTask task, DirtyReplaySelector selector) {
        LambdaQueryWrapper<SyncErrorSample> wrapper = new LambdaQueryWrapper<SyncErrorSample>()
                .eq(SyncErrorSample::getSyncTaskId, task.getId())
                .eq(SyncErrorSample::getExecutionId, selector.sourceExecutionId())
                .eq(SyncErrorSample::getTenantId, task.getTenantId())
                .in(SyncErrorSample::getId, selector.errorSampleIds());
        eqOrIsNull(wrapper, SyncErrorSample::getProjectId, task.getProjectId());
        eqOrIsNull(wrapper, SyncErrorSample::getWorkspaceId, task.getWorkspaceId());
        List<SyncErrorSample> samples = errorSampleMapper.selectList(wrapper);
        Map<Long, SyncErrorSample> byId = samples == null ? Map.of() : samples.stream()
                .collect(Collectors.toMap(SyncErrorSample::getId, sample -> sample, (left, right) -> left,
                        LinkedHashMap::new));
        List<Long> missing = selector.errorSampleIds().stream()
                .filter(id -> !byId.containsKey(id))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("selector 包含不属于当前任务范围的错误样本: " + missing);
        }
        return selector.errorSampleIds().stream().map(byId::get).toList();
    }

    private <T> void eqOrIsNull(LambdaQueryWrapper<SyncErrorSample> wrapper,
                                SFunction<SyncErrorSample, T> column,
                                T value) {
        if (value == null) {
            wrapper.isNull(column);
            return;
        }
        wrapper.eq(column, value);
    }

    /**
     * 执行单条错误样本重放。
     *
     * <p>每个样本转换为一个低敏 shardOrPartition，例如 {@code dirty-sample-501}。
     * 真实过滤条件只有一个：主键字段 EQ 主键值。该条件会叠加模板中已有的 filterConfig，
     * 因此用户配置的租户/日期/状态范围仍然生效，修复重放不会绕过原始数据范围。</p>
     */
    private DirtySampleReplayOutcome dispatchOneSample(SyncTask task,
                                                       SyncExecution execution,
                                                       SyncActorContext actorContext,
                                                       SyncBatchRunnerBridgePlan bridgePlan,
                                                       SyncErrorSample sample) {
        if (!Boolean.TRUE.equals(sample.getRetryable())) {
            return DirtySampleReplayOutcome.failed(sample.getId(), false, 0L, 0L, 1L,
                    "DIRTY_RECORD_REPLAY_SAMPLE_NOT_RETRYABLE");
        }
        PrimaryKeyLocator locator = parsePrimaryKeyLocator(sample);
        if (locator == null) {
            return DirtySampleReplayOutcome.failed(sample.getId(), false, 0L, 0L, 1L,
                    "DIRTY_RECORD_REPLAY_PRIMARY_KEY_LOCATOR_MISSING");
        }
        SyncBatchRunOnceRemoteExecutionResult remoteResult =
                runOnceDispatchService.executePreparedRunOnceRemoteOnly(
                        bridgePlan,
                        childExecutionView(execution),
                        task,
                        actorContext,
                        "dirty-sample-" + sample.getId(),
                        List.of(new SyncFilterExecutionCondition(locator.column(), "EQ", locator.value(), true)));
        boolean success = remoteResult != null
                && remoteResult.completed()
                && !remoteResult.failed()
                && safeLong(remoteResult.totalRecordsRead()) > 0L
                && safeLong(remoteResult.totalFailedRecordCount()) == 0L;
        if (success) {
            return DirtySampleReplayOutcome.succeeded(sample.getId(), remoteResult);
        }
        String issueCode = remoteResult == null
                ? "DIRTY_RECORD_REPLAY_REMOTE_RESULT_EMPTY"
                : safeLong(remoteResult.totalRecordsRead()) == 0L
                ? "DIRTY_RECORD_REPLAY_SOURCE_ROW_NOT_FOUND"
                : firstText(remoteResult.errorCode(), "DIRTY_RECORD_REPLAY_REMOTE_FAILED");
        return DirtySampleReplayOutcome.failed(sample.getId(),
                remoteResult != null && remoteResult.remoteCalled(),
                safeLong(remoteResult == null ? null : remoteResult.totalRecordsRead()),
                safeLong(remoteResult == null ? null : remoteResult.totalRecordsWritten()),
                Math.max(1L, safeLong(remoteResult == null ? null : remoteResult.totalFailedRecordCount())),
                issueCode);
    }

    /**
     * 从错误样本中解析 PRIMARY_KEY_EQ 定位。
     *
     * <p>优先读取 sourceRecordKey；如果历史数据把定位放在 samplePayload.sourceRecordKey 中，也做兼容解析。
     * 但 rowHash/rowIndex 只适合诊断，不适合执行重放，因此不会被转换成查询条件。</p>
     */
    private PrimaryKeyLocator parsePrimaryKeyLocator(SyncErrorSample sample) {
        PrimaryKeyLocator direct = parseLocatorJson(sample.getSourceRecordKey());
        if (direct != null) {
            return direct;
        }
        try {
            if (!hasText(sample.getSamplePayload())) {
                return null;
            }
            JsonNode payload = objectMapper.readTree(sample.getSamplePayload());
            JsonNode nested = payload.path("sourceRecordKey");
            if (nested.isObject()) {
                return parseLocatorNode(nested);
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private PrimaryKeyLocator parseLocatorJson(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return parseLocatorNode(objectMapper.readTree(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private PrimaryKeyLocator parseLocatorNode(JsonNode node) {
        if (node == null || !node.isObject() || !"PRIMARY_KEY_EQ".equals(node.path("strategy").asText())) {
            return null;
        }
        String column = node.path("column").asText(null);
        if (!hasText(column) || !SAFE_IDENTIFIER.matcher(column).matches() || node.get("value") == null
                || node.get("value").isNull()) {
            return null;
        }
        return new PrimaryKeyLocator(column, jsonNodeValue(node.get("value")));
    }

    private Object jsonNodeValue(JsonNode valueNode) {
        if (valueNode.isIntegralNumber()) {
            return valueNode.longValue();
        }
        if (valueNode.isFloatingPointNumber()) {
            return valueNode.doubleValue();
        }
        if (valueNode.isBoolean()) {
            return valueNode.booleanValue();
        }
        return valueNode.asText();
    }

    private SyncExecution childExecutionView(SyncExecution execution) {
        SyncExecution child = new SyncExecution();
        child.setId(execution.getId());
        child.setTenantId(execution.getTenantId());
        child.setProjectId(execution.getProjectId());
        child.setWorkspaceId(execution.getWorkspaceId());
        child.setSyncTaskId(execution.getSyncTaskId());
        child.setExecutionNo(execution.getExecutionNo());
        child.setExecutionState(execution.getExecutionState());
        child.setTriggerType(execution.getTriggerType());
        child.setExecutorId(execution.getExecutorId());
        child.setLeaseExpireTime(execution.getLeaseExpireTime());
        child.setRecordsRead(0L);
        child.setRecordsWritten(0L);
        child.setFailedRecordCount(0L);
        child.setTriggeredBy(execution.getTriggeredBy());
        return child;
    }

    private SyncOfflineRunnerDispatchResult aggregateReplayResult(SyncTask task,
                                                                  SyncExecution execution,
                                                                  SyncActorContext actorContext,
                                                                  List<DirtySampleReplayOutcome> outcomes) {
        boolean remoteCalled = outcomes.stream().anyMatch(DirtySampleReplayOutcome::remoteCalled);
        long recordsRead = outcomes.stream().mapToLong(DirtySampleReplayOutcome::recordsRead).sum();
        long recordsWritten = outcomes.stream().mapToLong(DirtySampleReplayOutcome::recordsWritten).sum();
        long failedCount = outcomes.stream().filter(outcome -> !outcome.succeeded()).count();
        List<String> issueCodes = outcomes.stream()
                .flatMap(outcome -> outcome.issueCodes().stream())
                .distinct()
                .toList();
        if (failedCount == 0L) {
            completeReplay(task, execution, actorContext, recordsRead, recordsWritten);
            return new SyncOfflineRunnerDispatchResult(
                    remoteCalled,
                    true,
                    false,
                    "DIRTY_RECORD_REPLAY_COMPLETED",
                    execution.getId(),
                    "DIRTY_RECORD_REPLAY_ALL_SAMPLES_COMPLETED",
                    DIRTY_RECORD_REPLAY,
                    mergeIssueCodes(issueCodes, List.of(), "DIRTY_RECORD_REPLAY_COMPLETED"),
                    SyncOfflineRunnerDispatchResult.PAYLOAD_POLICY
            );
        }
        if (failedCount < outcomes.size()) {
            partialReplay(task, execution, actorContext, recordsRead, recordsWritten, failedCount);
            return new SyncOfflineRunnerDispatchResult(
                    remoteCalled,
                    false,
                    false,
                    "DIRTY_RECORD_REPLAY_PARTIALLY_SUCCEEDED",
                    execution.getId(),
                    "DIRTY_RECORD_REPLAY_RETRY_REMAINING_SAMPLES",
                    DIRTY_RECORD_REPLAY,
                    mergeIssueCodes(issueCodes, List.of(), "DIRTY_RECORD_REPLAY_PARTIALLY_SUCCEEDED"),
                    SyncOfflineRunnerDispatchResult.PAYLOAD_POLICY
            );
        }
        return failReplay(task, execution, actorContext,
                remoteCalled,
                recordsRead,
                recordsWritten,
                Math.max(1L, failedCount),
                "DIRTY_RECORD_REPLAY_FAILED",
                "DIRTY_RECORD_REPLAY_ALL_SAMPLES_FAILED",
                "脏数据修复重放的所有样本均未成功，请检查主键定位、源端行是否仍存在、目标端约束和字段映射修复是否完成",
                mergeIssueCodes(issueCodes, List.of(), "DIRTY_RECORD_REPLAY_ALL_SAMPLES_FAILED"));
    }

    private void completeReplay(SyncTask task,
                                SyncExecution execution,
                                SyncActorContext actorContext,
                                long recordsRead,
                                long recordsWritten) {
        SyncExecutionCompleteRequest request = new SyncExecutionCompleteRequest();
        request.setExecutorId(execution.getExecutorId());
        request.setRecordsRead(recordsRead);
        request.setRecordsWritten(recordsWritten);
        request.setCheckpointRef(null);
        request.setIdempotencyKey("dirty-record-replay-complete-" + execution.getId());
        lifecycleSupport.completeExecution(task, execution, request, actorContext);
        receiptPublisher.publishComplete(task, execution, actorContext,
                aggregateResponse(recordsRead, recordsWritten, 0L, "DIRTY_RECORD_REPLAY_COMPLETED", true, false));
    }

    private void partialReplay(SyncTask task,
                               SyncExecution execution,
                               SyncActorContext actorContext,
                               long recordsRead,
                               long recordsWritten,
                               long failedCount) {
        SyncExecutionPartialSuccessRequest request = new SyncExecutionPartialSuccessRequest();
        request.setExecutorId(execution.getExecutorId());
        request.setRecordsRead(recordsRead);
        request.setRecordsWritten(recordsWritten);
        request.setFailedRecordCount(failedCount);
        request.setErrorSummary("DIRTY_RECORD_REPLAY partially succeeded, failedSamples=" + failedCount);
        request.setIdempotencyKey("dirty-record-replay-partial-" + execution.getId());
        lifecycleSupport.partiallySucceedExecution(task, execution, request, actorContext);
        receiptPublisher.publishPartiallySucceeded(task, execution, actorContext,
                aggregateResponse(recordsRead, recordsWritten, failedCount,
                        "DIRTY_RECORD_REPLAY_PARTIALLY_SUCCEEDED", false, false),
                List.of("DIRTY_RECORD_REPLAY_PARTIALLY_SUCCEEDED"));
    }

    private SyncOfflineRunnerDispatchResult failReplay(SyncTask task,
                                                       SyncExecution execution,
                                                       SyncActorContext actorContext,
                                                       boolean remoteCalled,
                                                       long recordsRead,
                                                       long recordsWritten,
                                                       long failedCount,
                                                       String dispatchStatus,
                                                       String errorCode,
                                                       String errorMessage,
                                                       List<String> issueCodes) {
        SyncExecutionFailRequest request = new SyncExecutionFailRequest();
        request.setExecutorId(execution.getExecutorId());
        request.setErrorType("DIRTY_RECORD_REPLAY_FAILED");
        request.setErrorCode(errorCode);
        request.setErrorMessage(errorMessage);
        request.setSourceRecordKey(null);
        request.setTargetRecordKey(null);
        request.setSamplePayload(null);
        request.setRecordsRead(recordsRead);
        request.setRecordsWritten(recordsWritten);
        request.setFailedRecordCount(Math.max(1L, failedCount));
        request.setRetryable(false);
        request.setIdempotencyKey("dirty-record-replay-fail-" + execution.getId() + "-" + errorCode);
        lifecycleSupport.failExecution(task, execution, request, actorContext);
        receiptPublisher.publishFailed(task, execution, actorContext, errorCode, issueCodes);
        return new SyncOfflineRunnerDispatchResult(
                remoteCalled,
                false,
                true,
                dispatchStatus,
                execution.getId(),
                null,
                DIRTY_RECORD_REPLAY,
                mergeIssueCodes(issueCodes, List.of(), errorCode),
                SyncOfflineRunnerDispatchResult.PAYLOAD_POLICY
        );
    }

    private DatasourceRunOnceResponse aggregateResponse(long recordsRead,
                                                        long recordsWritten,
                                                        long failedCount,
                                                        String runStatus,
                                                        boolean endOfSource,
                                                        boolean failed) {
        DatasourceRunOnceResponse response = new DatasourceRunOnceResponse();
        response.setRunStatus(runStatus);
        response.setBatchRecordsRead(recordsRead);
        response.setBatchRecordsWritten(recordsWritten);
        response.setBatchFailedRecordCount(failedCount);
        response.setTotalRecordsRead(recordsRead);
        response.setTotalRecordsWritten(recordsWritten);
        response.setTotalFailedRecordCount(failedCount);
        response.setEndOfSource(endOfSource);
        response.setFailed(failed);
        response.setCompleteCallbackRecommended(!failed && endOfSource);
        response.setFailCallbackRecommended(failed);
        response.setProgressCallbackRecommended(false);
        response.setCheckpointCandidateProduced(false);
        response.setPayloadPolicy("LOW_SENSITIVE_DIRTY_RECORD_REPLAY_RESULT_NO_ROWS_NO_SQL");
        return response;
    }

    private List<Long> distinct(List<Long> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private List<String> mergeIssueCodes(List<String> first, List<String> second, String issueCode) {
        List<String> values = new ArrayList<>();
        values.addAll(first == null ? List.of() : first);
        values.addAll(second == null ? List.of() : second);
        if (hasText(issueCode)) {
            values.add(issueCode);
        }
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private Long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private String firstText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record DirtyReplaySelector(Long sourceExecutionId, List<Long> errorSampleIds) {
    }

    private record PrimaryKeyLocator(String column, Object value) {
    }

    private record DirtySampleReplayOutcome(Long sampleId,
                                            boolean succeeded,
                                            boolean remoteCalled,
                                            long recordsRead,
                                            long recordsWritten,
                                            long failedRecords,
                                            List<String> issueCodes) {

        private static DirtySampleReplayOutcome succeeded(Long sampleId,
                                                          SyncBatchRunOnceRemoteExecutionResult remoteResult) {
            return new DirtySampleReplayOutcome(
                    sampleId,
                    true,
                    remoteResult.remoteCalled(),
                    remoteResult.totalRecordsRead() == null ? 0L : remoteResult.totalRecordsRead(),
                    remoteResult.totalRecordsWritten() == null ? 0L : remoteResult.totalRecordsWritten(),
                    0L,
                    remoteResult.issueCodes()
            );
        }

        private static DirtySampleReplayOutcome failed(Long sampleId,
                                                       boolean remoteCalled,
                                                       long recordsRead,
                                                       long recordsWritten,
                                                       long failedRecords,
                                                       String issueCode) {
            return new DirtySampleReplayOutcome(
                    sampleId,
                    false,
                    remoteCalled,
                    recordsRead,
                    recordsWritten,
                    Math.max(1L, failedRecords),
                    List.of(issueCode)
            );
        }

        private DirtySampleReplayOutcome {
            issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
        }
    }
}

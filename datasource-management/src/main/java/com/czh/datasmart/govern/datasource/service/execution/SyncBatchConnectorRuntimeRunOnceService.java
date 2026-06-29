/**
 * @Author : Cui
 * @Date: 2026/06/29 12:04
 * @Description DataSmart Govern Backend - SyncBatchConnectorRuntimeRunOnceService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchExecutionPlan;
import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchRunOnceInternalRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchRunOnceInternalResponse;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncPreparedJdbcStatement;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 连接器运行时单批执行服务。
 *
 * <p>该服务是 data-sync 闭环的下一块拼图：data-sync 负责模板、任务、execution、lease、checkpoint 和 complete/fail，
 * datasource-management 负责在受控边界内读取数据源配置、准备 JDBC 语句、执行一批 read/write，并返回低敏结果摘要。</p>
 *
 * <p>它和 {@link SyncBatchExecutionRunner} 的关键区别：</p>
 * <p>1. 本服务不调用 {@code SyncTaskService.reportProgress/completeExecution/failExecution}，因此不会修改 datasource-management
 * 自己的 legacy 同步任务状态；</p>
 * <p>2. 本服务不发布 task-management receipt，避免 connector runtime 同时承担任务中心回执责任；</p>
 * <p>3. 本服务返回“建议上游回调什么”的低敏事实，由 data-sync 决定如何推进自身 execution 状态机。</p>
 *
 * <p>安全边界：</p>
 * <p>服务内部短暂持有真实行数据和 checkpoint 候选值，但响应不返回 recordBatch、SQL、连接串、账号、密码、失败样本或 checkpoint 原始值。
 * 任何异常都被压缩为低敏摘要，避免 JDBC 驱动把 SQL、URL 或凭据信息带入错误响应。</p>
 */
@Service
@RequiredArgsConstructor
public class SyncBatchConnectorRuntimeRunOnceService {

    private static final String CHECKPOINT_VALUE_PARAMETER = "checkpointValue";
    private static final String LIMIT_PARAMETER = "limit";
    private static final String CHECKPOINT_HANDOFF_NOT_RETURNED =
            "CHECKPOINT_VALUE_NOT_RETURNED_USE_SECURE_HANDOFF_BEFORE_INCREMENTAL_CLOSURE";
    private static final int MAX_ERROR_SUMMARY_LENGTH = 260;

    /**
     * 执行准备服务。
     */
    private final SyncBatchExecutionPreparationService preparationService;

    /**
     * 批处理读取器。
     */
    private final SyncBatchReader syncBatchReader;

    /**
     * 批处理写入器。
     */
    private final SyncBatchWriter syncBatchWriter;

    /**
     * 执行一批 read/write，不回写任何控制面状态。
     *
     * @param request data-sync 或后续 connector-runtime worker 传入的内部请求。
     * @return 低敏执行摘要，由上游决定是否回写 progress、checkpoint、complete 或 fail。
     */
    public SyncBatchRunOnceInternalResponse runOnce(SyncBatchRunOnceInternalRequest request) {
        validateRequest(request);
        SyncBatchExecutionPlan plan = request.getExecutionPlan();
        SyncBatchWorkerExecutionBundle bundle = null;

        try {
            bundle = preparationService.prepareJdbcExecution(toPreparationRequest(request));
            prepareReadParameters(bundle.getReadContext(), request);

            SyncBatchReadResult readResult = requireReadResult(syncBatchReader.readNextBatch(bundle.getReadContext()));
            if (hasText(readResult.getErrorSummary())) {
                return failureResponse(plan, bundle, request, safeLong(readResult.getRecordsRead()), 0L, 0L,
                        "READ_FAILED", false, stageError("读取阶段失败", readResult.getErrorSummary()));
            }

            SyncBatchRecordBatch recordBatch = requireConsistentRecordBatch(readResult);
            SyncBatchWriteResult writeResult = writeIfNecessary(bundle.getWriteContext(), recordBatch);
            if (hasText(writeResult.getErrorSummary())) {
                return failureResponse(plan, bundle, request, safeLong(readResult.getRecordsRead()),
                        safeLong(writeResult.getRecordsWritten()), safeLong(writeResult.getFailedRecordCount()),
                        "WRITE_FAILED", true, stageError("写入阶段失败", writeResult.getErrorSummary()));
            }

            boolean checkpointCandidateProduced = resolveCheckpointCandidate(request, readResult) != null;
            boolean endOfSource = Boolean.TRUE.equals(readResult.getEndOfSource());
            return successResponse(plan, bundle, request, readResult, writeResult, checkpointCandidateProduced, endOfSource);
        } catch (RuntimeException exception) {
            return failureResponse(plan, bundle, request, 0L, 0L, 0L,
                    "RUNNER_FAILED", false, exceptionError("执行阶段异常", exception));
        }
    }

    /**
     * 将 HTTP internal 请求转换为已有准备服务可消费的内部请求。
     */
    private SyncBatchExecutionPreparationRequest toPreparationRequest(SyncBatchRunOnceInternalRequest request) {
        return new SyncBatchExecutionPreparationRequest(
                request.getExecutionPlan(),
                nullToEmpty(request.getSelectedColumns()),
                request.getWriteColumns(),
                nullToEmpty(request.getPrimaryKeyColumns())
        );
    }

    /**
     * 准备读取参数。
     *
     * <p>读取语句的参数名来自 JDBC 方言层，例如增量读取会声明 checkpointValue 和 limit。
     * 本方法只做参数绑定值准备，不拼接 SQL 字符串。checkpointValue 只进入内部 parameterValues，
     * 不进入响应和日志。</p>
     */
    private void prepareReadParameters(SyncBatchReadContext readContext, SyncBatchRunOnceInternalRequest request) {
        SyncPreparedJdbcStatement readStatement = readContext.getReadStatement();
        List<String> parameterNames = readStatement == null ? List.of() : readStatement.getParameterNames();
        Map<String, Object> parameterValues = new HashMap<>();
        if (readContext.getParameterValues() != null) {
            parameterValues.putAll(readContext.getParameterValues());
        }
        if (request.getCheckpointValue() != null) {
            parameterValues.put(CHECKPOINT_VALUE_PARAMETER, request.getCheckpointValue());
        }
        if (!parameterValues.containsKey(LIMIT_PARAMETER) && readContext.getFetchSize() != null) {
            parameterValues.put(LIMIT_PARAMETER, readContext.getFetchSize());
        }
        if (parameterNames.contains(CHECKPOINT_VALUE_PARAMETER) && !parameterValues.containsKey(CHECKPOINT_VALUE_PARAMETER)) {
            throw new IllegalStateException("增量读取需要 checkpointValue，当前 run-once 请求未提供内部起点水位");
        }
        readContext.setParameterValues(parameterValues);
    }

    /**
     * 校验 reader 输出。
     */
    private SyncBatchReadResult requireReadResult(SyncBatchReadResult readResult) {
        if (readResult == null) {
            throw new IllegalStateException("读取器返回空结果，无法判断本批执行状态");
        }
        return readResult;
    }

    /**
     * 校验读取数量和内部记录批次一致性。
     */
    private SyncBatchRecordBatch requireConsistentRecordBatch(SyncBatchReadResult readResult) {
        SyncBatchRecordBatch recordBatch = readResult.getRecordBatch();
        long recordsRead = safeLong(readResult.getRecordsRead());
        if (recordsRead > 0 && (recordBatch == null || recordBatch.isEmpty())) {
            throw new IllegalStateException("读取数量大于 0 但内部记录批次为空，已阻断写入");
        }
        return recordBatch == null ? new SyncBatchRecordBatch(List.of(), List.of()) : recordBatch;
    }

    /**
     * 空批次不触发写入器。
     */
    private SyncBatchWriteResult writeIfNecessary(SyncBatchWriteContext writeContext, SyncBatchRecordBatch recordBatch) {
        if (recordBatch == null || recordBatch.isEmpty()) {
            return new SyncBatchWriteResult(0L, 0L, true, null);
        }
        SyncBatchWriteResult writeResult = syncBatchWriter.writeBatch(writeContext, recordBatch);
        if (writeResult == null) {
            throw new IllegalStateException("写入器返回空结果，无法判断本批写入状态");
        }
        return writeResult;
    }

    /**
     * 判断是否产生 checkpoint 候选值。
     *
     * <p>该方法会在内存中读取增量字段的最后一个非空值，但只返回是否存在，不返回具体值。
     * 这样能让 data-sync 知道“需要 checkpoint handoff”，同时避免当前低敏响应泄露水位。</p>
     */
    private Object resolveCheckpointCandidate(SyncBatchRunOnceInternalRequest request, SyncBatchReadResult readResult) {
        if (!Boolean.TRUE.equals(readResult.getCheckpointRecommended())) {
            return null;
        }
        String incrementalField = request.getExecutionPlan().getReadPlan().getIncrementalField();
        SyncBatchRecordBatch recordBatch = readResult.getRecordBatch();
        if (!hasText(incrementalField) || recordBatch == null || recordBatch.getRows() == null) {
            return null;
        }
        Object checkpointValue = null;
        for (Map<String, Object> row : recordBatch.getRows()) {
            if (row != null && row.get(incrementalField) != null) {
                checkpointValue = row.get(incrementalField);
            }
        }
        return checkpointValue;
    }

    private SyncBatchRunOnceInternalResponse successResponse(SyncBatchExecutionPlan plan,
                                                             SyncBatchWorkerExecutionBundle bundle,
                                                             SyncBatchRunOnceInternalRequest request,
                                                             SyncBatchReadResult readResult,
                                                             SyncBatchWriteResult writeResult,
                                                             boolean checkpointCandidateProduced,
                                                             boolean endOfSource) {
        String runStatus = endOfSource ? "SOURCE_EXHAUSTED_COMPLETE_REQUIRED" : "BATCH_WRITTEN_MORE_REMAIN";
        return response(plan, bundle, request,
                runStatus,
                safeLong(readResult.getRecordsRead()),
                safeLong(writeResult.getRecordsWritten()),
                safeLong(writeResult.getFailedRecordCount()),
                endOfSource,
                false,
                true,
                checkpointCandidateProduced,
                checkpointCandidateProduced,
                endOfSource,
                false,
                null);
    }

    private SyncBatchRunOnceInternalResponse failureResponse(SyncBatchExecutionPlan plan,
                                                             SyncBatchWorkerExecutionBundle bundle,
                                                             SyncBatchRunOnceInternalRequest request,
                                                             Long batchRecordsRead,
                                                             Long batchRecordsWritten,
                                                             Long batchFailedRecordCount,
                                                             String runStatus,
                                                             boolean progressRecommended,
                                                             String errorSummary) {
        return response(plan, bundle, request,
                runStatus,
                safeLong(batchRecordsRead),
                safeLong(batchRecordsWritten),
                safeLong(batchFailedRecordCount),
                false,
                true,
                progressRecommended,
                false,
                false,
                false,
                true,
                errorSummary);
    }

    private SyncBatchRunOnceInternalResponse response(SyncBatchExecutionPlan plan,
                                                      SyncBatchWorkerExecutionBundle bundle,
                                                      SyncBatchRunOnceInternalRequest request,
                                                      String runStatus,
                                                      Long batchRecordsRead,
                                                      Long batchRecordsWritten,
                                                      Long batchFailedRecordCount,
                                                      boolean endOfSource,
                                                      boolean failed,
                                                      boolean progressCallbackRecommended,
                                                      boolean checkpointCallbackRecommended,
                                                      boolean checkpointCandidateProduced,
                                                      boolean completeCallbackRecommended,
                                                      boolean failCallbackRecommended,
                                                      String errorSummary) {
        return new SyncBatchRunOnceInternalResponse(
                plan.getTaskId(),
                plan.getExecutionId(),
                runStatus,
                batchRecordsRead,
                batchRecordsWritten,
                batchFailedRecordCount,
                total(request.getPreviousRecordsRead(), batchRecordsRead),
                total(request.getPreviousRecordsWritten(), batchRecordsWritten),
                total(request.getPreviousFailedRecordCount(), batchFailedRecordCount),
                endOfSource,
                failed,
                progressCallbackRecommended,
                checkpointCallbackRecommended,
                checkpointCandidateProduced,
                CHECKPOINT_HANDOFF_NOT_RETURNED,
                completeCallbackRecommended,
                failCallbackRecommended,
                checkpointType(bundle, plan),
                checkpointValueVisibility(bundle, plan),
                errorSummary,
                bundle == null ? List.of() : bundle.getWarnings(),
                SyncBatchRunOnceInternalResponse.PAYLOAD_POLICY
        );
    }

    /**
     * 请求基础校验。
     */
    private void validateRequest(SyncBatchRunOnceInternalRequest request) {
        if (request == null || request.getExecutionPlan() == null) {
            throw new IllegalArgumentException("run-once 请求必须包含 executionPlan");
        }
        SyncBatchExecutionPlan plan = request.getExecutionPlan();
        if (plan.getTaskId() == null || plan.getExecutionId() == null) {
            throw new IllegalArgumentException("executionPlan 必须包含 taskId 和 executionId");
        }
        if (plan.getReadPlan() == null || plan.getWritePlan() == null
                || plan.getCheckpointPlan() == null || plan.getRuntimeControlPlan() == null) {
            throw new IllegalArgumentException("executionPlan 必须包含 readPlan、writePlan、checkpointPlan 和 runtimeControlPlan");
        }
        if (request.getWriteColumns() == null || request.getWriteColumns().isEmpty()) {
            throw new IllegalArgumentException("writeColumns 不能为空");
        }
        if (request.getActorId() == null || !hasText(request.getActorRole()) || request.getActorTenantId() == null) {
            throw new IllegalArgumentException("run-once 请求必须包含 actorId、actorRole、actorTenantId");
        }
    }

    private String checkpointType(SyncBatchWorkerExecutionBundle bundle, SyncBatchExecutionPlan plan) {
        if (bundle != null && bundle.getCheckpointPlan() != null) {
            return bundle.getCheckpointPlan().getCheckpointType();
        }
        return plan.getCheckpointPlan() == null ? null : plan.getCheckpointPlan().getCheckpointType();
    }

    private String checkpointValueVisibility(SyncBatchWorkerExecutionBundle bundle, SyncBatchExecutionPlan plan) {
        if (bundle != null && bundle.getCheckpointPlan() != null) {
            return bundle.getCheckpointPlan().getCheckpointValueVisibility();
        }
        return plan.getCheckpointPlan() == null ? null : plan.getCheckpointPlan().getCheckpointValueVisibility();
    }

    private List<String> nullToEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    private long total(Long previousValue, Long batchValue) {
        return safeLong(previousValue) + safeLong(batchValue);
    }

    private long safeLong(Long value) {
        return value == null ? 0L : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String stageError(String stage, String summary) {
        if (!hasText(summary)) {
            return stage;
        }
        return truncate(stage + ": " + scrubPotentialSensitiveDetail(summary));
    }

    private String exceptionError(String stage, RuntimeException exception) {
        return truncate(stage + ": " + exception.getClass().getSimpleName() + "，具体执行细节已隐藏");
    }

    /**
     * 对下游 reader/writer 返回的摘要做二次清洗。
     *
     * <p>接口契约要求 reader/writer 返回低敏摘要，但真实 JDBC 驱动或第三方连接器仍可能把 SQL、URL、token 带入 message。
     * 这里做保守清洗：一旦发现明显敏感片段，就只保留通用提示。</p>
     */
    private String scrubPotentialSensitiveDetail(String value) {
        String normalized = value.toLowerCase();
        if (normalized.contains("jdbc:")
                || normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("select ")
                || normalized.contains("insert ")
                || normalized.contains("update ")
                || normalized.contains("delete ")) {
            return "执行细节已隐藏，请通过受控诊断链路排查";
        }
        return value;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_SUMMARY_LENGTH ? value : value.substring(0, MAX_ERROR_SUMMARY_LENGTH);
    }
}

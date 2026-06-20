/**
 * @Author : Cui
 * @Date: 2026/06/20 16:25
 * @Description DataSmart Govern Backend - SyncBatchExecutionRunner.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchExecutionPlan;
import com.czh.datasmart.govern.datasource.controller.dto.SyncCompleteRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncFailRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncProgressRequest;
import com.czh.datasmart.govern.datasource.service.SyncTaskService;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncPreparedJdbcStatement;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 同步单批执行 Runner。
 *
 * <p>该组件把前面已经完成的几个能力真正串起来：</p>
 * <p>1. `SyncBatchExecutionPreparationService`：把 claim 返回的低敏 executionPlan 装配成内部 read/write context；</p>
 * <p>2. `SyncBatchReader`：按 read context 读取一批源端数据；</p>
 * <p>3. `SyncBatchWriter`：按 write context 写入这一批数据；</p>
 * <p>4. `SyncTaskService`：复用现有任务控制面入口回写 progress、checkpoint、complete 或 fail。</p>
 *
 * <p>为什么当前只做“单批”而不是完整 while 循环：</p>
 * <p>1. 商业化产品里，完整循环要考虑 worker 租约续期、暂停/取消信号、队列公平性、分片并发、重试退避和资源配额；</p>
 * <p>2. 这些能力更适合由 task-management outbox、worker receipt 和调度器统一管理，而不应该继续堆在 datasource-management；</p>
 * <p>3. 单批 Runner 是一个清晰可测试的执行原子，外层调度后续可以安全地重复调用它。</p>
 *
 * <p>安全边界：</p>
 * <p>Runner 可以在内存中看到 `SyncBatchRecordBatch` 这样的真实行数据，但它绝不把这些数据放进返回值、
 * 普通日志、runtime event、审计 payload 或文档进度记录。返回给调用方的只有数量、状态和低敏摘要。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncBatchExecutionRunner {

    private static final String CHECKPOINT_VALUE_PARAMETER = "checkpointValue";
    private static final String LIMIT_PARAMETER = "limit";
    private static final int MAX_ERROR_SUMMARY_LENGTH = 300;

    /**
     * 执行准备服务。
     * 它负责把控制面 executionPlan 转换成内部 SQL 模板和 read/write context。
     */
    private final SyncBatchExecutionPreparationService preparationService;

    /**
     * 批处理读取器。
     * 当前生产实现是 JDBC reader，后续可以通过接口替换为文件、对象存储、API 或消息队列 reader。
     */
    private final SyncBatchReader syncBatchReader;

    /**
     * 批处理写入器。
     * 当前生产实现是 JDBC writer，未来可以扩展为 Kafka writer、MinIO writer、湖仓 writer 等。
     */
    private final SyncBatchWriter syncBatchWriter;

    /**
     * 同步任务控制服务。
     * Runner 不直接改库，而是复用该服务的权限、状态、审计和 checkpoint 持久化逻辑。
     */
    private final SyncTaskService syncTaskService;

    /**
     * 执行一批同步数据。
     *
     * @param request 单批执行请求，包含执行计划、执行器身份、累计统计和起始 checkpoint。
     * @return 低敏执行结果摘要，不包含真实行数据或 SQL。
     */
    public SyncBatchExecutionRunResult runOnce(SyncBatchExecutionRunRequest request) {
        validateRequest(request);
        SyncBatchExecutionPlan plan = request.getPreparationRequest().getExecutionPlan();
        Long taskId = plan.getTaskId();
        Long executionId = plan.getExecutionId();
        SyncBatchWorkerExecutionBundle bundle = null;

        try {
            bundle = preparationService.prepareJdbcExecution(request.getPreparationRequest());
            prepareReadParameters(bundle.getReadContext(), request);

            SyncBatchReadResult readResult = requireReadResult(syncBatchReader.readNextBatch(bundle.getReadContext()));
            if (hasText(readResult.getErrorSummary())) {
                return failExecution(taskId, executionId, request, bundle, safeLong(readResult.getRecordsRead()), 0L,
                        0L, false, "读取阶段失败: " + readResult.getErrorSummary());
            }

            SyncBatchRecordBatch recordBatch = requireConsistentRecordBatch(readResult);
            SyncBatchWriteResult writeResult = writeIfNecessary(bundle.getWriteContext(), recordBatch);
            if (hasText(writeResult.getErrorSummary())) {
                reportProgress(taskId, executionId, request, bundle, readResult, writeResult, null);
                return failExecution(taskId, executionId, request, bundle, safeLong(readResult.getRecordsRead()),
                        safeLong(writeResult.getRecordsWritten()), safeLong(writeResult.getFailedRecordCount()),
                        true, "写入阶段失败: " + writeResult.getErrorSummary());
            }

            Object nextCheckpointValue = resolveNextCheckpointValue(request, bundle, readResult);
            boolean checkpointPersisted = reportProgress(taskId, executionId, request, bundle, readResult, writeResult, nextCheckpointValue);

            if (Boolean.TRUE.equals(readResult.getEndOfSource())) {
                completeExecution(taskId, executionId, request, readResult, writeResult);
                return buildResult(taskId, executionId, bundle, request, readResult, writeResult,
                        true, false, true, true, checkpointPersisted, null);
            }

            return buildResult(taskId, executionId, bundle, request, readResult, writeResult,
                    false, false, false, true, checkpointPersisted, null);
        } catch (RuntimeException ex) {
            String errorSummary = lowSensitiveError("执行阶段异常", ex);
            return failExecution(taskId, executionId, request, bundle, 0L, 0L, 0L, false, errorSummary);
        }
    }

    /**
     * 准备读取参数。
     *
     * <p>方言层生成的 PreparedStatement 会声明参数顺序，例如 `checkpointValue`、`limit`。
     * Runner 只负责把这些参数放入 `parameterValues`，真正绑定仍由 reader 通过 JDBC PreparedStatement 完成。
     * 这样可以避免 SQL 注入，也能让 checkpoint 值只停留在内部执行上下文中。</p>
     */
    private void prepareReadParameters(SyncBatchReadContext readContext, SyncBatchExecutionRunRequest request) {
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
            throw new IllegalStateException("增量读取语句需要 checkpointValue，但当前 Runner 尚未收到起始 checkpoint");
        }
        readContext.setParameterValues(parameterValues);
    }

    /**
     * 校验 reader 输出。
     * reader 不允许返回 null，因为 null 会让 worker 无法区分“空批次”和“读取实现异常”。
     */
    private SyncBatchReadResult requireReadResult(SyncBatchReadResult readResult) {
        if (readResult == null) {
            throw new IllegalStateException("读取器返回空结果，无法判断本批执行状态");
        }
        return readResult;
    }

    /**
     * 校验读取统计和真实批次是否一致。
     *
     * <p>如果 reader 声称读到了记录，却没有提供内部 record batch，writer 就无法写入目标端。
     * 此时继续推进 progress 会造成“控制面显示已读取，但目标端实际未写”的数据一致性风险，所以必须 fail-closed。</p>
     */
    private SyncBatchRecordBatch requireConsistentRecordBatch(SyncBatchReadResult readResult) {
        SyncBatchRecordBatch recordBatch = readResult.getRecordBatch();
        long recordsRead = safeLong(readResult.getRecordsRead());
        if (recordsRead > 0 && (recordBatch == null || recordBatch.isEmpty())) {
            throw new IllegalStateException("读取记录数大于 0，但内部记录批次为空，已阻断写入以避免数据丢失");
        }
        return recordBatch == null ? new SyncBatchRecordBatch(List.of(), List.of()) : recordBatch;
    }

    /**
     * 写入本批数据。
     * 空批次不调用 writer，避免对目标端产生没有业务意义的空事务。
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
     * 从本批记录中解析下一 checkpoint 候选值。
     *
     * <p>当前 JDBC 方言会按增量字段升序读取，因此在一个批次内最后一条非空增量字段值，
     * 就可以作为下一批的起始水位候选。这里仍然只把它传给 progress/checkpoint 回写接口，
     * Runner 自己的返回对象不暴露该原始值。</p>
     */
    private Object resolveNextCheckpointValue(SyncBatchExecutionRunRequest request,
                                              SyncBatchWorkerExecutionBundle bundle,
                                              SyncBatchReadResult readResult) {
        if (!Boolean.TRUE.equals(readResult.getCheckpointRecommended())) {
            return null;
        }
        String incrementalField = request.getPreparationRequest().getExecutionPlan().getReadPlan().getIncrementalField();
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
        return checkpointValue == null && bundle.getReadContext().getParameterValues() != null
                ? bundle.getReadContext().getParameterValues().get(CHECKPOINT_VALUE_PARAMETER)
                : checkpointValue;
    }

    /**
     * 回写本批进度。
     *
     * <p>这里特别注意：`SyncTaskService.reportProgress` 保存的是 execution 累计值。
     * Runner 因此会把请求里的上一批累计值和本批增量值相加，再回写到控制面。</p>
     */
    private boolean reportProgress(Long taskId,
                                   Long executionId,
                                   SyncBatchExecutionRunRequest request,
                                   SyncBatchWorkerExecutionBundle bundle,
                                   SyncBatchReadResult readResult,
                                   SyncBatchWriteResult writeResult,
                                   Object checkpointValue) {
        SyncProgressRequest progressRequest = new SyncProgressRequest();
        fillActor(progressRequest, request);
        progressRequest.setExecutionId(executionId);
        progressRequest.setRecordsRead(total(request.getPreviousRecordsRead(), readResult.getRecordsRead()));
        progressRequest.setRecordsWritten(total(request.getPreviousRecordsWritten(), writeResult.getRecordsWritten()));
        progressRequest.setFailedRecordCount(total(request.getPreviousFailedRecordCount(), writeResult.getFailedRecordCount()));
        progressRequest.setErrorSummary(writeResult.getErrorSummary());

        boolean checkpointPresent = checkpointValue != null;
        if (checkpointPresent) {
            progressRequest.setCheckpointType(bundle.getCheckpointPlan().getCheckpointType());
            progressRequest.setCheckpointValue(String.valueOf(checkpointValue));
            progressRequest.setShardOrPartition(request.getShardOrPartition());
        }
        syncTaskService.reportProgress(taskId, progressRequest);
        return checkpointPresent;
    }

    /**
     * 标记 execution 完成。
     * 如果累计失败记录数大于 0，底层服务会把任务推进到 PARTIALLY_SUCCEEDED。
     */
    private void completeExecution(Long taskId,
                                   Long executionId,
                                   SyncBatchExecutionRunRequest request,
                                   SyncBatchReadResult readResult,
                                   SyncBatchWriteResult writeResult) {
        SyncCompleteRequest completeRequest = new SyncCompleteRequest();
        fillActor(completeRequest, request);
        completeRequest.setExecutionId(executionId);
        completeRequest.setRecordsRead(total(request.getPreviousRecordsRead(), readResult.getRecordsRead()));
        completeRequest.setRecordsWritten(total(request.getPreviousRecordsWritten(), writeResult.getRecordsWritten()));
        completeRequest.setFailedRecordCount(total(request.getPreviousFailedRecordCount(), writeResult.getFailedRecordCount()));
        completeRequest.setSummary("同步单批 Runner 已读到源端结尾，执行完成");
        syncTaskService.completeExecution(taskId, completeRequest);
    }

    /**
     * 标记 execution 失败。
     *
     * <p>失败回写会进入现有控制面状态机，清理租约、记录错误摘要并标记人工关注。
     * 如果 fail 回写本身抛出异常，Runner 不再吞掉原始错误，而是把回写失败作为低敏摘要返回给调用方。</p>
     */
    private SyncBatchExecutionRunResult failExecution(Long taskId,
                                                      Long executionId,
                                                      SyncBatchExecutionRunRequest request,
                                                      SyncBatchWorkerExecutionBundle bundle,
                                                      Long batchRecordsRead,
                                                      Long batchRecordsWritten,
                                                      Long batchFailedRecordCount,
                                                      boolean progressReported,
                                                      String errorSummary) {
        String safeError = truncate(errorSummary);
        try {
            SyncFailRequest failRequest = new SyncFailRequest();
            fillActor(failRequest, request);
            failRequest.setExecutionId(executionId);
            failRequest.setFailedRecordCount(total(request.getPreviousFailedRecordCount(), batchFailedRecordCount));
            failRequest.setErrorSummary(safeError);
            syncTaskService.failExecution(taskId, failRequest);
        } catch (RuntimeException callbackException) {
            safeError = truncate(safeError + "；失败回写也发生异常: " + callbackException.getClass().getSimpleName());
        }
        return buildFailureResult(taskId, executionId, bundle, request, batchRecordsRead, batchRecordsWritten,
                batchFailedRecordCount, progressReported, safeError);
    }

    private SyncBatchExecutionRunResult buildResult(Long taskId,
                                                    Long executionId,
                                                    SyncBatchWorkerExecutionBundle bundle,
                                                    SyncBatchExecutionRunRequest request,
                                                    SyncBatchReadResult readResult,
                                                    SyncBatchWriteResult writeResult,
                                                    boolean completed,
                                                    boolean failed,
                                                    boolean endOfSource,
                                                    boolean progressReported,
                                                    boolean checkpointPersisted,
                                                    String errorSummary) {
        return new SyncBatchExecutionRunResult(
                taskId,
                executionId,
                safeLong(readResult.getRecordsRead()),
                safeLong(writeResult.getRecordsWritten()),
                safeLong(writeResult.getFailedRecordCount()),
                total(request.getPreviousRecordsRead(), readResult.getRecordsRead()),
                total(request.getPreviousRecordsWritten(), writeResult.getRecordsWritten()),
                total(request.getPreviousFailedRecordCount(), writeResult.getFailedRecordCount()),
                endOfSource,
                completed,
                failed,
                progressReported,
                checkpointPersisted,
                bundle.getCheckpointPlan().getCheckpointType(),
                bundle.getCheckpointPlan().getCheckpointValueVisibility(),
                errorSummary,
                bundle.getWarnings()
        );
    }

    private SyncBatchExecutionRunResult buildFailureResult(Long taskId,
                                                           Long executionId,
                                                           SyncBatchWorkerExecutionBundle bundle,
                                                           SyncBatchExecutionRunRequest request,
                                                           Long batchRecordsRead,
                                                           Long batchRecordsWritten,
                                                           Long batchFailedRecordCount,
                                                           boolean progressReported,
                                                           String errorSummary) {
        return new SyncBatchExecutionRunResult(
                taskId,
                executionId,
                safeLong(batchRecordsRead),
                safeLong(batchRecordsWritten),
                safeLong(batchFailedRecordCount),
                total(request.getPreviousRecordsRead(), batchRecordsRead),
                total(request.getPreviousRecordsWritten(), batchRecordsWritten),
                total(request.getPreviousFailedRecordCount(), batchFailedRecordCount),
                false,
                false,
                true,
                progressReported,
                false,
                bundle == null ? null : bundle.getCheckpointPlan().getCheckpointType(),
                bundle == null ? null : bundle.getCheckpointPlan().getCheckpointValueVisibility(),
                errorSummary,
                bundle == null ? List.of() : bundle.getWarnings()
        );
    }

    private void fillActor(SyncProgressRequest progressRequest, SyncBatchExecutionRunRequest request) {
        progressRequest.setActorId(request.getActorId());
        progressRequest.setActorRole(request.getActorRole());
        progressRequest.setActorTenantId(request.getActorTenantId());
    }

    private void fillActor(SyncCompleteRequest completeRequest, SyncBatchExecutionRunRequest request) {
        completeRequest.setActorId(request.getActorId());
        completeRequest.setActorRole(request.getActorRole());
        completeRequest.setActorTenantId(request.getActorTenantId());
    }

    private void fillActor(SyncFailRequest failRequest, SyncBatchExecutionRunRequest request) {
        failRequest.setActorId(request.getActorId());
        failRequest.setActorRole(request.getActorRole());
        failRequest.setActorTenantId(request.getActorTenantId());
    }

    private void validateRequest(SyncBatchExecutionRunRequest request) {
        if (request == null || request.getPreparationRequest() == null
                || request.getPreparationRequest().getExecutionPlan() == null) {
            throw new IllegalArgumentException("单批执行请求必须包含 preparationRequest.executionPlan");
        }
        if (request.getActorId() == null || !hasText(request.getActorRole()) || request.getActorTenantId() == null) {
            throw new IllegalArgumentException("单批执行请求必须包含 actorId、actorRole 和 actorTenantId");
        }
        SyncBatchExecutionPlan plan = request.getPreparationRequest().getExecutionPlan();
        if (plan.getTaskId() == null || plan.getExecutionId() == null) {
            throw new IllegalArgumentException("executionPlan 必须包含 taskId 和 executionId");
        }
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

    private String lowSensitiveError(String stage, RuntimeException ex) {
        return truncate(stage + ": " + ex.getClass().getSimpleName() + " - " + ex.getMessage());
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_SUMMARY_LENGTH ? value : value.substring(0, MAX_ERROR_SUMMARY_LENGTH);
    }
}

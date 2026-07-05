/**
 * @Author : Cui
 * @Date: 2026/07/05 15:00
 * @Description DataSmart Govern Backend - SyncOfflineRunnerReportCallbackService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCheckpointRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncOfflineRunnerReportRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncOfflineRunnerReportResult;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncExecutionMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 专用离线 Runner 执行报告回调服务。
 *
 * <p>本服务是上一阶段 {@link SyncOfflineRunnerAdapter} 的后半段闭环：adapter 负责把合同提交给真实离线 Runner，
 * 本服务负责接收真实 Runner 后续回传的低敏执行报告，并转换为 data-sync 已有的生命周期动作。</p>
 *
 * <p>设计上必须避免两类危险：</p>
 * <p>1. 不再造一套 execution 状态机。SUCCEEDED、FAILED、CHECKPOINT 继续委托 {@link SyncExecutionLifecycleSupport}，
 * 复用已有租约校验、幂等登记、checkpoint 插入、complete/fail 回写、审计和 task-management receipt 链路；</p>
 * <p>2. 不让离线 Runner 把敏感细节带回控制面。PROGRESS/CHECKPOINT 只能写计数、ref、digest 和低敏问题码，
 * 不能写 SQL 正文、statementRef 值、连接串、对象名、字段名、过滤条件、分区条件或错误样本正文。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncOfflineRunnerReportCallbackService {

    private static final String ACTION_PROGRESS = "OFFLINE_RUNNER_PROGRESS";
    private static final Set<String> PROGRESS_STATUSES = Set.of("QUEUED", "RUNNING", "PROGRESS");

    private final SyncTaskMapper taskMapper;
    private final SyncExecutionMapper executionMapper;
    private final SyncExecutionLifecycleSupport lifecycleSupport;
    private final SyncCallbackIdempotencySupport idempotencySupport;
    private final DataSyncTaskManagementReceiptPublisher receiptPublisher;

    /**
     * 应用一份专用离线 Runner 低敏报告。
     *
     * @param taskId 同步任务 ID。
     * @param executionId 执行记录 ID。
     * @param request Runner 上报的低敏报告。
     * @param actorContext 服务账号或受控 worker 上下文。
     * @return 低敏处理结果。
     */
    @Transactional
    public SyncOfflineRunnerReportResult applyReport(Long taskId,
                                                     Long executionId,
                                                     SyncOfflineRunnerReportRequest request,
                                                     SyncActorContext actorContext) {
        SyncTask task = loadTask(taskId);
        SyncExecution execution = loadExecution(task, executionId);
        String status = normalizeStatus(request.getRunnerStatus());
        return switch (status) {
            case "QUEUED", "RUNNING", "PROGRESS" -> acceptProgress(task, execution, request, actorContext, status);
            case "CHECKPOINT" -> writeLowSensitiveCheckpoint(task, execution, request, actorContext);
            case "SUCCEEDED", "SUCCESS", "COMPLETED" -> completeFromReport(task, execution, request, actorContext);
            case "FAILED", "FAIL" -> failFromReport(task, execution, request, actorContext);
            default -> throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "不支持的离线 Runner 报告状态: " + request.getRunnerStatus());
        };
    }

    /**
     * 接收 QUEUED/RUNNING/PROGRESS 报告。
     *
     * <p>这些状态说明真实 Runner 仍在排队或运行中，不能把 execution 改成终态。
     * 因此这里只更新低敏进度计数、heartbeatTime 和 checkpointRef 摘要引用，方便列表和运维台看到任务仍然活跃。</p>
     */
    private SyncOfflineRunnerReportResult acceptProgress(SyncTask task,
                                                         SyncExecution execution,
                                                         SyncOfflineRunnerReportRequest request,
                                                         SyncActorContext actorContext,
                                                         String status) {
        requireActiveExecution(execution, request.getExecutorId(), status);
        String scopeKey = task.getId() + ":" + execution.getId();
        if (idempotencySupport.isDuplicate(task.getTenantId(), task.getId(), execution.getId(), ACTION_PROGRESS,
                scopeKey, request.getIdempotencyKey(), request.getExecutorId(),
                "offlineRunnerProgress,status=" + status + ",adapter=" + safeText(request.getAdapterCode()))) {
            SyncExecution current = executionMapper.selectById(execution.getId());
            return result("PROGRESS_DUPLICATE_IGNORED", current, request, mergedIssues(request, "DUPLICATE_PROGRESS_REPORT"));
        }
        execution.setRecordsRead(safeLong(request.getRecordsRead(), execution.getRecordsRead()));
        execution.setRecordsWritten(safeLong(request.getRecordsWritten(), execution.getRecordsWritten()));
        execution.setFailedRecordCount(safeLong(request.getFailedRecordCount(), execution.getFailedRecordCount()));
        execution.setCheckpointRef(lowSensitiveCheckpointRef(request, execution.getCheckpointRef()));
        execution.setHeartbeatTime(LocalDateTime.now());
        execution.setUpdateTime(LocalDateTime.now());
        executionMapper.updateById(execution);
        idempotencySupport.markSucceeded(task.getTenantId(), ACTION_PROGRESS, scopeKey, request.getIdempotencyKey(),
                "status=" + status + ",recordsRead=" + execution.getRecordsRead()
                        + ",recordsWritten=" + execution.getRecordsWritten());
        return result("PROGRESS_ACCEPTED", execution, request, request.getIssueCodes());
    }

    /**
     * 将低敏 checkpoint 报告转换为已有 checkpoint 生命周期动作。
     *
     * <p>现有 {@link SyncExecutionLifecycleSupport#writeCheckpoint} 要求 checkpointValue 非空。
     * 为了不泄露原始水位，这里只把 checkpointRef/checkpointDigest 组装成不可还原的低敏值。
     * 真实原始水位应保存在专用 Runner 的受控存储中，data-sync 只保存引用或 digest。</p>
     */
    private SyncOfflineRunnerReportResult writeLowSensitiveCheckpoint(SyncTask task,
                                                                      SyncExecution execution,
                                                                      SyncOfflineRunnerReportRequest request,
                                                                      SyncActorContext actorContext) {
        SyncExecutionCheckpointRequest checkpointRequest = new SyncExecutionCheckpointRequest();
        checkpointRequest.setExecutorId(request.getExecutorId());
        checkpointRequest.setCheckpointType(firstText(request.getCheckpointType(), "OFFLINE_RUNNER_DIGEST"));
        checkpointRequest.setCheckpointValue(lowSensitiveCheckpointValue(request));
        checkpointRequest.setShardOrPartition(lowSensitiveShard(request.getShardOrPartition()));
        checkpointRequest.setRecordsRead(request.getRecordsRead());
        checkpointRequest.setRecordsWritten(request.getRecordsWritten());
        checkpointRequest.setIdempotencyKey(request.getIdempotencyKey());
        lifecycleSupport.writeCheckpoint(task, execution, checkpointRequest, actorContext);
        SyncExecution current = executionMapper.selectById(execution.getId());
        return result("CHECKPOINT_WRITTEN", current, request, request.getIssueCodes());
    }

    /**
     * 将 SUCCEEDED 报告转换为 complete 回调。
     */
    private SyncOfflineRunnerReportResult completeFromReport(SyncTask task,
                                                             SyncExecution execution,
                                                             SyncOfflineRunnerReportRequest request,
                                                             SyncActorContext actorContext) {
        SyncExecutionCompleteRequest completeRequest = new SyncExecutionCompleteRequest();
        completeRequest.setExecutorId(request.getExecutorId());
        completeRequest.setRecordsRead(request.getRecordsRead());
        completeRequest.setRecordsWritten(request.getRecordsWritten());
        completeRequest.setCheckpointRef(lowSensitiveCheckpointRef(request, execution.getCheckpointRef()));
        completeRequest.setIdempotencyKey(request.getIdempotencyKey());
        lifecycleSupport.completeExecution(task, execution, completeRequest, actorContext);
        SyncExecution current = executionMapper.selectById(execution.getId());
        receiptPublisher.publishComplete(task, current, actorContext, null);
        return result("EXECUTION_COMPLETED", current, request, request.getIssueCodes());
    }

    /**
     * 将 FAILED 报告转换为 fail 回调。
     *
     * <p>失败样本仍然只写低敏错误摘要，不写 sourceRecordKey、targetRecordKey 或 samplePayload。
     * 如果真实 Runner 需要保留坏数据样本，应放入受控对象存储并通过后续审批/审计流程查看。</p>
     */
    private SyncOfflineRunnerReportResult failFromReport(SyncTask task,
                                                         SyncExecution execution,
                                                         SyncOfflineRunnerReportRequest request,
                                                         SyncActorContext actorContext) {
        SyncExecutionFailRequest failRequest = new SyncExecutionFailRequest();
        failRequest.setExecutorId(request.getExecutorId());
        failRequest.setErrorType(firstText(request.getErrorType(), "OFFLINE_RUNNER_ERROR"));
        failRequest.setErrorCode(firstText(request.getErrorCode(), "OFFLINE_RUNNER_FAILED"));
        failRequest.setErrorMessage(lowSensitiveErrorMessage(request));
        failRequest.setSourceRecordKey(null);
        failRequest.setTargetRecordKey(null);
        failRequest.setSamplePayload(null);
        failRequest.setRetryable(Boolean.TRUE.equals(request.getRetryable()));
        failRequest.setIdempotencyKey(request.getIdempotencyKey());
        lifecycleSupport.failExecution(task, execution, failRequest, actorContext);
        SyncExecution current = executionMapper.selectById(execution.getId());
        receiptPublisher.publishFailed(task, current, actorContext, failRequest.getErrorCode(),
                mergedIssues(request, failRequest.getErrorCode()));
        return result("EXECUTION_FAILED", current, request, mergedIssues(request, failRequest.getErrorCode()));
    }

    private SyncTask loadTask(Long taskId) {
        if (taskId == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "同步任务 ID 不能为空");
        }
        SyncTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "同步任务不存在: " + taskId);
        }
        return task;
    }

    private SyncExecution loadExecution(SyncTask task, Long executionId) {
        if (executionId == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR, "同步执行记录 ID 不能为空");
        }
        SyncExecution execution = executionMapper.selectById(executionId);
        if (execution == null) {
            throw new PlatformBusinessException(PlatformErrorCode.NOT_FOUND, "同步执行记录不存在: " + executionId);
        }
        if (!task.getId().equals(execution.getSyncTaskId())) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "同步执行记录不属于当前任务，taskId=" + task.getId() + ", executionId=" + executionId);
        }
        return execution;
    }

    private void requireActiveExecution(SyncExecution execution, String executorId, String status) {
        if (!SyncExecutionState.RUNNING.name().equals(execution.getExecutionState())
                && !SyncExecutionState.RETRYING.name().equals(execution.getExecutionState())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "当前执行状态不允许接收离线 Runner 进度报告，runnerStatus=" + status
                            + ", executionState=" + execution.getExecutionState());
        }
        if (!matchesExecutor(execution, executorId)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "executorId 不匹配，不能写入离线 Runner 报告");
        }
    }

    private SyncOfflineRunnerReportResult result(String action,
                                                 SyncExecution execution,
                                                 SyncOfflineRunnerReportRequest request,
                                                 List<String> issueCodes) {
        return new SyncOfflineRunnerReportResult(
                true,
                action,
                execution == null ? null : execution.getId(),
                execution == null ? null : execution.getExecutionState(),
                execution == null ? lowSensitiveCheckpointRef(request, null) : execution.getCheckpointRef(),
                distinct(issueCodes),
                SyncOfflineRunnerReportResult.PAYLOAD_POLICY
        );
    }

    private String lowSensitiveCheckpointValue(SyncOfflineRunnerReportRequest request) {
        String ref = trimToNull(request.getCheckpointRef());
        String digest = trimToNull(request.getCheckpointDigest());
        if (ref == null && digest == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "离线 Runner checkpoint 报告必须提供 checkpointRef 或 checkpointDigest");
        }
        return "checkpointRef=" + firstText(ref, "NONE") + ";checkpointDigest=" + firstText(digest, "NONE");
    }

    private String lowSensitiveCheckpointRef(SyncOfflineRunnerReportRequest request, String fallback) {
        String ref = trimToNull(request.getCheckpointRef());
        String digest = trimToNull(request.getCheckpointDigest());
        if (ref != null && digest != null) {
            return "ref:" + ref + "|digest:" + digest;
        }
        if (ref != null) {
            return "ref:" + ref;
        }
        if (digest != null) {
            return "digest:" + digest;
        }
        return fallback;
    }

    private String lowSensitiveShard(String shardOrPartition) {
        String value = trimToNull(shardOrPartition);
        return value == null ? null : truncate(value, 128);
    }

    private String lowSensitiveErrorMessage(SyncOfflineRunnerReportRequest request) {
        String message = firstText(request.getErrorMessage(), "离线 Runner 上报失败，未提供错误摘要");
        return truncate(message, 1000);
    }

    private List<String> mergedIssues(SyncOfflineRunnerReportRequest request, String issueCode) {
        List<String> values = new ArrayList<>(request.getIssueCodes() == null ? List.of() : request.getIssueCodes());
        if (issueCode != null && !issueCode.isBlank()) {
            values.add(issueCode);
        }
        return distinct(values);
    }

    private List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values == null ? List.of() : values));
    }

    private String normalizeStatus(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim();
    }

    private String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Long safeLong(Long candidate, Long fallback) {
        return candidate == null ? (fallback == null ? 0L : fallback) : candidate;
    }

    private boolean matchesExecutor(SyncExecution execution, String executorId) {
        return executorId != null
                && !executorId.isBlank()
                && execution.getExecutorId() != null
                && executorId.trim().equals(execution.getExecutorId());
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}

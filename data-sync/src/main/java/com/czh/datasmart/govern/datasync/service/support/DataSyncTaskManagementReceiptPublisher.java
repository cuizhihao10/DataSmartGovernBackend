/**
 * @Author : Cui
 * @Date: 2026/06/29 13:18
 * @Description DataSmart Govern Backend - DataSyncTaskManagementReceiptPublisher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.config.DataSyncTaskManagementReceiptProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceResponse;
import com.czh.datasmart.govern.datasync.integration.task.receipt.TaskManagementExecutionReceiptRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * data-sync 到 task-management execution receipt 的发布器。
 *
 * <p>本类位于 service support 层，而不是 HTTP client 层，原因是它理解 data-sync 的业务对象：
 * {@link SyncTask}、{@link SyncExecution} 和 datasource-management run-once response。
 * 它负责把这些领域事实转换成 task-management 需要的低敏 receipt 请求。</p>
 *
 * <p>发布器不暴露敏感信息：</p>
 * <p>1. COMPLETE 只发送计数、完成标记、endOfSource 和 checkpoint 可见性策略；</p>
 * <p>2. FAILED 只发送错误码、低敏错误摘要和问题码 warning，不发送异常 message、SQL、URL、字段值或样本；</p>
 * <p>3. commandId 当前可为空，task-management 会尝试按 syncTaskId + syncExecutionId 回查 outbox。</p>
 */
@Component
@Slf4j
public class DataSyncTaskManagementReceiptPublisher {

    private static final String CHECKPOINT_VISIBILITY = "NO_CHECKPOINT_VALUE_IN_RECEIPT";
    private static final String PARTIAL_OBJECT_FAILURE = "PARTIAL_OBJECT_FAILURE";

    private final DataSyncTaskManagementReceiptOutboxService outboxService;
    private final DataSyncTaskManagementReceiptProperties properties;
    private final SyncAutopilotRecoveryTriggerPublisher autopilotRecoveryTriggerPublisher;
    private final SyncAutopilotRecoverySidecarCompensationService sidecarCompensationService;
    private final SyncAutopilotRecoveryMetrics autopilotRecoveryMetrics;

    /** Spring 生产构造器：失败投影和 Autopilot 触发使用彼此独立的可靠投递链。 */
    @Autowired
    public DataSyncTaskManagementReceiptPublisher(
            DataSyncTaskManagementReceiptOutboxService outboxService,
            DataSyncTaskManagementReceiptProperties properties,
            SyncAutopilotRecoveryTriggerPublisher autopilotRecoveryTriggerPublisher,
            SyncAutopilotRecoverySidecarCompensationService sidecarCompensationService,
            SyncAutopilotRecoveryMetrics autopilotRecoveryMetrics) {
        this.outboxService = outboxService;
        this.properties = properties;
        this.autopilotRecoveryTriggerPublisher = autopilotRecoveryTriggerPublisher;
        this.sidecarCompensationService = sidecarCompensationService;
        this.autopilotRecoveryMetrics = autopilotRecoveryMetrics;
    }

    /**
     * Compatibility constructor for focused tests and migration-period callers that exercise only the first
     * Autopilot sidecar. Production Spring wiring uses the five-argument constructor so failures can be made
     * durable in the V23 compensation journal.
     *
     * @param outboxService durable task-management receipt outbox writer
     * @param properties receipt projection configuration
     * @param autopilotRecoveryTriggerPublisher governed Autopilot sidecar, or {@code null} for legacy tests
     */
    DataSyncTaskManagementReceiptPublisher(
            DataSyncTaskManagementReceiptOutboxService outboxService,
            DataSyncTaskManagementReceiptProperties properties,
            SyncAutopilotRecoveryTriggerPublisher autopilotRecoveryTriggerPublisher) {
        this(outboxService, properties, autopilotRecoveryTriggerPublisher, null, null);
    }

    /**
     * 单元测试和迁移期兼容构造器。
     *
     * <p>只省略 Autopilot sidecar，不改变原 task-management receipt 行为；生产 Spring 容器
     * 始终选择上面的三参数构造器。</p>
     */
    DataSyncTaskManagementReceiptPublisher(
            DataSyncTaskManagementReceiptOutboxService outboxService,
            DataSyncTaskManagementReceiptProperties properties) {
        this(outboxService, properties, null, null, null);
    }

    /**
     * Publishes a successful execution fact and independently attempts to close an active Autopilot case.
     *
     * <p>The completion sidecar runs before the optional task-management projection because a recovered case
     * must be closed even when operators have disabled receipt projection. A sidecar exception is converted into
     * a V23 compensation fact, while the successful execution receipt continues through its own outbox. This
     * keeps the durable business fact, the operational projection, and the recovery-control plane isolated.</p>
     *
     * @param task authoritative task that owns the execution
     * @param execution completed execution
     * @param actorContext service or user context recorded by the receipt outbox
     * @param response low-sensitive aggregate result from datasource-management
     */
    public void publishComplete(SyncTask task,
                                SyncExecution execution,
                                SyncActorContext actorContext,
                                DatasourceRunOnceResponse response) {
        /*
         * Autopilot case 终态与 task-management 展示投影彼此独立。即使运营侧关闭普通 receipt，
         * 夜间自动恢复成功后仍必须把 RECOVERY_STARTED 收敛为 RECOVERED。
         */
        publishSuccessfulSidecar(task, execution);
        if (!properties.isEnabled()) {
            return;
        }
        TaskManagementExecutionReceiptRequest request = baseRequest(task, execution, "COMPLETE");
        request.setBatchRecordsRead(zeroIfNull(response == null ? null : response.getBatchRecordsRead()));
        request.setBatchRecordsWritten(zeroIfNull(response == null ? null : response.getBatchRecordsWritten()));
        request.setBatchFailedRecordCount(zeroIfNull(response == null ? null : response.getBatchFailedRecordCount()));
        request.setTotalRecordsRead(zeroIfNull(response == null ? execution.getRecordsRead() : response.getTotalRecordsRead()));
        request.setTotalRecordsWritten(zeroIfNull(response == null ? execution.getRecordsWritten() : response.getTotalRecordsWritten()));
        request.setTotalFailedRecordCount(zeroIfNull(response == null ? execution.getFailedRecordCount() : response.getTotalFailedRecordCount()));
        request.setProgressPercent(100);
        request.setEndOfSource(response == null ? Boolean.TRUE : response.getEndOfSource());
        request.setCompleted(true);
        request.setFailed(false);
        request.setProgressReported(false);
        request.setCheckpointPersisted(false);
        request.setCheckpointType(null);
        request.setCheckpointValueVisibility(CHECKPOINT_VISIBILITY);
        request.setWarnings(List.of("data-sync 已完成本次 execution，task-management 仅记录低敏执行投影"));
        outboxService.enqueueAndDispatch(task, execution, request, actorContext);
    }

    /**
     * Publishes a failed execution fact and independently starts governed Autopilot recovery when authorized.
     *
     * <p>The trigger sidecar is deliberately isolated from the task-management projection. If its independent
     * transaction fails after the execution failure is already durable, this method writes a compact V23 replay
     * fact and still enqueues the receipt. The compensation journal stores only IDs and sanitized codes, never
     * the exception body or transport data.</p>
     *
     * @param task authoritative task that owns the execution
     * @param execution failed execution
     * @param actorContext service or user context recorded by the receipt outbox
     * @param errorCode primary low-sensitive failure category
     * @param issueCodes bounded secondary low-sensitive failure categories
     */
    public void publishFailed(SyncTask task,
                              SyncExecution execution,
                              SyncActorContext actorContext,
                              String errorCode,
                              List<String> issueCodes) {
        /*
         * Autopilot 是执行恢复控制链，不是 task-management 的展示投影。必须先独立触发，
         * 否则关闭 receipt 展示功能会意外关闭夜间无人值守恢复。
         */
        publishFailedSidecar(task, execution, errorCode, issueCodes);
        if (!properties.isEnabled()) {
            return;
        }
        TaskManagementExecutionReceiptRequest request = baseRequest(task, execution, "FAILED");
        request.setBatchRecordsRead(0L);
        request.setBatchRecordsWritten(0L);
        request.setBatchFailedRecordCount(1L);
        request.setTotalRecordsRead(zeroIfNull(execution.getRecordsRead()));
        request.setTotalRecordsWritten(zeroIfNull(execution.getRecordsWritten()));
        request.setTotalFailedRecordCount(Math.max(1L, zeroIfNull(execution.getFailedRecordCount())));
        request.setProgressPercent(null);
        request.setEndOfSource(false);
        request.setCompleted(false);
        request.setFailed(true);
        request.setProgressReported(false);
        request.setCheckpointPersisted(false);
        request.setCheckpointType(null);
        request.setCheckpointValueVisibility(CHECKPOINT_VISIBILITY);
        request.setErrorSummary("data-sync execution failed, errorCode=" + safeCode(errorCode));
        request.setWarnings(issueCodes == null || issueCodes.isEmpty()
                ? List.of("data-sync 回写失败回执，未提供额外低敏 issueCode")
                : issueCodes.stream().map(code -> "issueCode=" + safeCode(code)).toList());
        outboxService.enqueueAndDispatch(task, execution, request, actorContext);
    }

    /**
     * 发布 execution 部分成功 receipt。
     *
     * <p>部分成功是对象级 fan-out 的重要状态：它不是 COMPLETE，因为仍有对象失败；也不应伪装成 FAILED，因为已有对象成功落地，
     * 后续运营动作应优先选择“只重试失败对象/分片”，而不是整任务盲目重跑。这里显式使用 PARTIALLY_SUCCEEDED 事件类型，
     * 让 task-management 和 Agent timeline 可以把它展示为“已完成但需处理失败分片”的状态。</p>
     */
    public void publishPartiallySucceeded(SyncTask task,
                                          SyncExecution execution,
                                          SyncActorContext actorContext,
                                          DatasourceRunOnceResponse response,
                                          List<String> issueCodes) {
        long failedCount = failedObjectCount(execution, response);
        /*
         * PARTIALLY_SUCCEEDED means at least one object may already be durable while another object failed.
         * Recovery must target that failed-object fact, not rerun the completed objects or depend on the optional
         * task-management projection switch. A zero count is treated as no failure evidence and remains a pure
         * partial receipt, which prevents a malformed aggregate from manufacturing an Autopilot case.
         */
        if (failedCount > 0L) {
            publishFailedSidecar(task, execution, PARTIAL_OBJECT_FAILURE, issueCodes);
        }
        if (!properties.isEnabled()) {
            return;
        }
        TaskManagementExecutionReceiptRequest request = baseRequest(task, execution, "PARTIALLY_SUCCEEDED");
        request.setBatchRecordsRead(zeroIfNull(response == null ? null : response.getBatchRecordsRead()));
        request.setBatchRecordsWritten(zeroIfNull(response == null ? null : response.getBatchRecordsWritten()));
        request.setBatchFailedRecordCount(zeroIfNull(response == null
                ? execution.getFailedRecordCount()
                : response.getBatchFailedRecordCount()));
        request.setTotalRecordsRead(zeroIfNull(response == null ? execution.getRecordsRead() : response.getTotalRecordsRead()));
        request.setTotalRecordsWritten(zeroIfNull(response == null ? execution.getRecordsWritten() : response.getTotalRecordsWritten()));
        request.setTotalFailedRecordCount(zeroIfNull(response == null
                ? execution.getFailedRecordCount()
                : response.getTotalFailedRecordCount()));
        request.setProgressPercent(100);
        request.setEndOfSource(false);
        request.setCompleted(false);
        request.setFailed(false);
        request.setProgressReported(false);
        request.setCheckpointPersisted(false);
        request.setCheckpointType(null);
        request.setCheckpointValueVisibility(CHECKPOINT_VISIBILITY);
        request.setErrorSummary("data-sync execution partially succeeded, failedObjectCount="
                + failedCount);
        request.setWarnings(issueCodes == null || issueCodes.isEmpty()
                ? List.of("data-sync OBJECT_LIST 部分成功；失败对象可按对象级执行账本选择性重试")
                : issueCodes.stream().map(code -> "issueCode=" + safeCode(code)).toList());
        outboxService.enqueueAndDispatch(task, execution, request, actorContext);
    }

    /**
     * Invokes the successful-execution sidecar and remembers an unavailable finalization for later replay.
     *
     * <p>The original sidecar has its own {@code REQUIRES_NEW} transaction. When it throws, the V23 journal is
     * written through a second independent transaction so the scheduler can replay only the idempotent
     * finalization call. A journal-write failure is logged but never allowed to roll back or hide the already
     * completed execution receipt.</p>
     *
     * @param task authoritative task used by the sidecar scope check
     * @param execution completed execution used by the sidecar scope check
     */
    private void publishSuccessfulSidecar(SyncTask task, SyncExecution execution) {
        if (autopilotRecoveryTriggerPublisher == null) {
            return;
        }
        try {
            autopilotRecoveryTriggerPublisher.publishSucceeded(task, execution);
        } catch (RuntimeException exception) {
            log.warn("Autopilot recovery completion sidecar failed, taskId={}, executionId={}, exceptionType={}",
                    task == null ? null : task.getId(),
                    execution == null ? null : execution.getId(),
                    exception.getClass().getSimpleName());
            if (autopilotRecoveryMetrics != null) {
                autopilotRecoveryMetrics.recordFinalizationSidecarFailure();
            }
            recordSuccessfulFinalizationCompensation(task, execution);
        }
    }

    /**
     * Invokes the failed-execution sidecar and records a bounded retry fact when its transaction is unavailable.
     *
     * <p>This helper is shared by terminal failures and partial object failures. Both cases enter the same
     * authorization, scope, loop-budget, and outbox path when replayed; the only difference is the stable primary
     * error code. Catching here preserves receipt publication and avoids leaking an arbitrary exception message
     * into persistence, logs, or Kafka.</p>
     *
     * @param task authoritative task used by the sidecar scope check
     * @param execution failed or partially successful execution
     * @param errorCode normalized primary failure category
     * @param issueCodes bounded secondary low-sensitive categories
     */
    private void publishFailedSidecar(SyncTask task,
                                      SyncExecution execution,
                                      String errorCode,
                                      List<String> issueCodes) {
        if (autopilotRecoveryTriggerPublisher == null) {
            return;
        }
        try {
            autopilotRecoveryTriggerPublisher.publishFailed(task, execution, errorCode, issueCodes);
        } catch (RuntimeException exception) {
            log.warn("Autopilot recovery sidecar failed, taskId={}, executionId={}, exceptionType={}",
                    task == null ? null : task.getId(),
                    execution == null ? null : execution.getId(),
                    exception.getClass().getSimpleName());
            if (autopilotRecoveryMetrics != null) {
                autopilotRecoveryMetrics.recordTriggerSidecarFailure();
            }
            recordFailedTriggerCompensation(task, execution, errorCode, issueCodes);
        }
    }

    /**
     * Persists a failed-trigger retry pointer without allowing an unavailable journal to affect the receipt path.
     *
     * <p>The compensation service validates task/execution ownership and starts a new transaction. Its own
     * exception is intentionally contained because the caller has already done the only safe action available
     * during a control-plane outage: preserve the execution and task-management facts that actually occurred.</p>
     */
    private void recordFailedTriggerCompensation(SyncTask task,
                                                 SyncExecution execution,
                                                 String errorCode,
                                                 List<String> issueCodes) {
        if (sidecarCompensationService == null) {
            return;
        }
        try {
            sidecarCompensationService.recordFailedTrigger(task, execution, errorCode, issueCodes);
        } catch (RuntimeException compensationException) {
            log.error("Autopilot trigger sidecar compensation could not be persisted, taskId={}, executionId={}, "
                            + "exceptionType={}",
                    task == null ? null : task.getId(),
                    execution == null ? null : execution.getId(),
                    compensationException.getClass().getSimpleName());
        }
    }

    /**
     * Persists a successful-finalization retry pointer without allowing its journal failure to affect completion.
     *
     * <p>Unlike a failure trigger, this record can only replay {@code publishSucceeded}; it cannot alter the
     * execution outcome or create a new recovery trigger. The fixed compensation key in the service makes
     * repeated completion callbacks idempotent.</p>
     */
    private void recordSuccessfulFinalizationCompensation(SyncTask task, SyncExecution execution) {
        if (sidecarCompensationService == null) {
            return;
        }
        try {
            sidecarCompensationService.recordSuccessfulFinalization(task, execution);
        } catch (RuntimeException compensationException) {
            log.error("Autopilot finalization sidecar compensation could not be persisted, taskId={}, executionId={}, "
                            + "exceptionType={}",
                    task == null ? null : task.getId(),
                    execution == null ? null : execution.getId(),
                    compensationException.getClass().getSimpleName());
        }
    }

    /**
     * Calculates whether a partial result has durable evidence of at least one failed object or record.
     *
     * <p>The execution aggregate is authoritative after lifecycle persistence, while the datasource response can
     * contain a newer batch or total count. Taking the maximum avoids a false negative when either source lags;
     * it does not expose object identifiers or payload content and has no side effect.</p>
     *
     * @param execution persisted aggregate execution counters
     * @param response optional datasource aggregate counters
     * @return the largest nonnegative failure count observed across both bounded summaries
     */
    private long failedObjectCount(SyncExecution execution, DatasourceRunOnceResponse response) {
        long executionCount = zeroIfNull(execution == null ? null : execution.getFailedRecordCount());
        long batchCount = zeroIfNull(response == null ? null : response.getBatchFailedRecordCount());
        long totalCount = zeroIfNull(response == null ? null : response.getTotalFailedRecordCount());
        return Math.max(executionCount, Math.max(batchCount, totalCount));
    }

    private TaskManagementExecutionReceiptRequest baseRequest(SyncTask task,
                                                              SyncExecution execution,
                                                              String eventType) {
        TaskManagementExecutionReceiptRequest request = new TaskManagementExecutionReceiptRequest();
        request.setReceiptId(receiptId(execution, eventType));
        request.setCommandId(null);
        request.setSyncTaskId(task.getId());
        request.setSyncExecutionId(execution.getId());
        request.setEventType(eventType);
        request.setEventTime(LocalDateTime.now());
        request.setExecutorId(execution.getExecutorId());
        request.setSourceService(properties.getSourceService());
        return request;
    }

    private String receiptId(SyncExecution execution, String eventType) {
        return "data-sync-execution-receipt:" + execution.getId() + ":" + eventType.toLowerCase();
    }

    private Long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }

    private String safeCode(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        return value.trim().replaceAll("[^A-Za-z0-9_\\-:.]", "_");
    }
}

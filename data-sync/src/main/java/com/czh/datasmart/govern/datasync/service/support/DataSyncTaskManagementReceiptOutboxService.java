/**
 * @Author : Cui
 * @Date: 2026/06/29 19:34
 * @Description DataSmart Govern Backend - DataSyncTaskManagementReceiptOutboxService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.config.DataSyncTaskManagementReceiptProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.TaskManagementReceiptOutboxDispatchResult;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTaskManagementReceiptOutbox;
import com.czh.datasmart.govern.datasync.integration.task.receipt.TaskManagementExecutionReceiptClient;
import com.czh.datasmart.govern.datasync.integration.task.receipt.TaskManagementExecutionReceiptRequest;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskManagementReceiptOutboxMapper;
import com.czh.datasmart.govern.datasync.scheduler.DataSyncTaskManagementReceiptOutboxMetrics;
import com.czh.datasmart.govern.datasync.support.TaskManagementReceiptOutboxState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * task-management execution receipt outbox 服务。
 *
 * <p>本服务承接 publisher 构造出的低敏 receipt 请求，负责：</p>
 * <p>1. 先把投递意图写入 data-sync 本地 MySQL outbox，避免进程崩溃时丢失跨服务投影；</p>
 * <p>2. 正常路径下立即尝试投递 task-management，保持用户侧接近实时的任务中心视图；</p>
 * <p>3. 失败时按指数退避写入 RETRY_WAIT，后台调度器继续补偿；</p>
 * <p>4. 达到最大尝试次数后进入 DEAD_LETTER，等待运维介入，避免无限重试压垮对端。</p>
 *
 * <p>安全边界：本服务不会把 task-management baseUrl、HTTP 响应体、异常 message、SQL、字段映射正文、
 * checkpoint 原始值、样本数据、prompt 或模型输出写入 outbox、日志、指标或返回结果。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSyncTaskManagementReceiptOutboxService {

    private static final int HARD_MAX_BATCH_SIZE = 200;

    private final SyncTaskManagementReceiptOutboxMapper outboxMapper;
    private final TaskManagementExecutionReceiptClient receiptClient;
    private final DataSyncTaskManagementReceiptProperties properties;
    private final ObjectMapper objectMapper;
    private final DataSyncTaskManagementReceiptOutboxMetrics metrics;

    /**
     * 写入 outbox，并按配置立即尝试投递。
     *
     * <p>调用方通常是 {@link DataSyncTaskManagementReceiptPublisher}。该方法必须在 data-sync execution complete/fail
     * 之后调用，因为 receipt 是“已发生事实的投影”，不能反过来决定 data-sync 主状态是否已经闭合。</p>
     */
    public TaskManagementReceiptOutboxDispatchResult enqueueAndDispatch(SyncTask task,
                                                                        SyncExecution execution,
                                                                        TaskManagementExecutionReceiptRequest request,
                                                                        SyncActorContext actorContext) {
        if (!properties.isEnabled()) {
            return TaskManagementReceiptOutboxDispatchResult.empty("TASK_MANAGEMENT_RECEIPT_DISABLED");
        }
        if (!properties.getOutbox().isEnabled()) {
            return deliverWithoutOutbox(request, actorContext, true);
        }
        SyncTaskManagementReceiptOutbox outbox = ensureOutbox(task, execution, request, actorContext);
        if (TaskManagementReceiptOutboxState.DELIVERED.name().equals(outbox.getOutboxState())) {
            return TaskManagementReceiptOutboxDispatchResult.skipped("RECEIPT_ALREADY_DELIVERED");
        }
        if (TaskManagementReceiptOutboxState.DEAD_LETTER.name().equals(outbox.getOutboxState())) {
            return TaskManagementReceiptOutboxDispatchResult.skipped("RECEIPT_ALREADY_DEAD_LETTERED");
        }
        if (!properties.getOutbox().isImmediateDeliveryEnabled()) {
            return TaskManagementReceiptOutboxDispatchResult.skipped("RECEIPT_OUTBOX_WAITING_FOR_SCHEDULER");
        }
        return dispatchOne(outbox, actorContext, true);
    }

    /**
     * 调度器或 internal 运维入口派发 due receipt。
     *
     * <p>本方法只处理 due 记录，不允许调用方直接传 payload 或目标地址。这样手动补偿仍然受数据库 outbox 控制，
     * 不会变成一个任意内部 HTTP 调用器。</p>
     */
    public TaskManagementReceiptOutboxDispatchResult dispatchDue(Integer requestedLimit, SyncActorContext actorContext) {
        if (!properties.isEnabled()) {
            return TaskManagementReceiptOutboxDispatchResult.empty("TASK_MANAGEMENT_RECEIPT_DISABLED");
        }
        if (!properties.getOutbox().isEnabled()) {
            return TaskManagementReceiptOutboxDispatchResult.empty("TASK_MANAGEMENT_RECEIPT_OUTBOX_DISABLED");
        }
        int limit = normalizeLimit(requestedLimit);
        List<SyncTaskManagementReceiptOutbox> dueReceipts = outboxMapper.selectDueReceipts(
                limit, Math.max(1L, properties.getOutbox().getStaleDeliveringSeconds()));
        TaskManagementReceiptOutboxDispatchResult total = TaskManagementReceiptOutboxDispatchResult.empty(null);
        for (SyncTaskManagementReceiptOutbox outbox : dueReceipts) {
            total = total.plus(dispatchOne(outbox, actorContext, false));
        }
        metrics.recordDispatchSuccess(total);
        return total;
    }

    /**
     * 确保 outbox 记录存在。
     *
     * <p>幂等规则：receiptId 是稳定唯一键。如果同一 execution 的同一事件重复发布，服务会复用已存在 outbox，
     * 不会创建多条记录，也不会把 DELIVERED 改回 PENDING。</p>
     */
    private SyncTaskManagementReceiptOutbox ensureOutbox(SyncTask task,
                                                         SyncExecution execution,
                                                         TaskManagementExecutionReceiptRequest request,
                                                         SyncActorContext actorContext) {
        requireInputs(task, execution, request);
        SyncTaskManagementReceiptOutbox existing = outboxMapper.selectByReceiptId(request.getReceiptId());
        if (existing != null) {
            return existing;
        }
        SyncTaskManagementReceiptOutbox outbox = new SyncTaskManagementReceiptOutbox();
        outbox.setReceiptId(request.getReceiptId());
        outbox.setTenantId(task.getTenantId());
        outbox.setProjectId(task.getProjectId());
        outbox.setWorkspaceId(task.getWorkspaceId());
        outbox.setSyncTaskId(task.getId());
        outbox.setSyncExecutionId(execution.getId());
        outbox.setEventType(request.getEventType());
        outbox.setSourceService(request.getSourceService());
        outbox.setOutboxState(TaskManagementReceiptOutboxState.PENDING.name());
        outbox.setAttemptCount(0);
        outbox.setMaxAttemptCount(Math.max(1, properties.getOutbox().getMaxAttempts()));
        outbox.setNextRetryAt(null);
        outbox.setActorId(actorContext == null ? null : actorContext.actorId());
        outbox.setActorRole(actorContext == null ? properties.getActorRole() : actorContext.actorRole());
        outbox.setTraceId(actorContext == null ? properties.getOutbox().getTraceIdPrefix() : actorContext.traceId());
        outbox.setPayloadJson(writePayload(request));
        LocalDateTime now = LocalDateTime.now();
        outbox.setCreateTime(now);
        outbox.setUpdateTime(now);
        try {
            outboxMapper.insert(outbox);
            return outbox;
        } catch (DuplicateKeyException exception) {
            SyncTaskManagementReceiptOutbox duplicate = outboxMapper.selectByReceiptId(request.getReceiptId());
            if (duplicate != null) {
                return duplicate;
            }
            throw exception;
        }
    }

    /**
     * 派发单条 outbox。
     */
    private TaskManagementReceiptOutboxDispatchResult dispatchOne(SyncTaskManagementReceiptOutbox outbox,
                                                                  SyncActorContext fallbackActorContext,
                                                                  boolean throwWhenDeliveryRequired) {
        if (outbox == null || outbox.getId() == null) {
            return TaskManagementReceiptOutboxDispatchResult.skipped("RECEIPT_OUTBOX_RECORD_MISSING");
        }
        if (TaskManagementReceiptOutboxState.DELIVERED.name().equals(outbox.getOutboxState())) {
            return TaskManagementReceiptOutboxDispatchResult.skipped("RECEIPT_ALREADY_DELIVERED");
        }
        if (TaskManagementReceiptOutboxState.DEAD_LETTER.name().equals(outbox.getOutboxState())) {
            return TaskManagementReceiptOutboxDispatchResult.skipped("RECEIPT_ALREADY_DEAD_LETTERED");
        }
        int locked = outboxMapper.markDelivering(outbox.getId(),
                Math.max(1L, properties.getOutbox().getStaleDeliveringSeconds()));
        if (locked != 1) {
            return TaskManagementReceiptOutboxDispatchResult.skipped("RECEIPT_OUTBOX_ALREADY_CLAIMED_OR_NOT_DUE");
        }
        int nextAttempt = safeAttempt(outbox.getAttemptCount()) + 1;
        try {
            receiptClient.record(readPayload(outbox), actorContext(outbox, fallbackActorContext));
            outboxMapper.markDelivered(outbox.getId());
            return TaskManagementReceiptOutboxDispatchResult.delivered();
        } catch (PlatformBusinessException exception) {
            TaskManagementReceiptOutboxState targetState = nextAttempt >= safeMaxAttempts(outbox)
                    ? TaskManagementReceiptOutboxState.DEAD_LETTER
                    : TaskManagementReceiptOutboxState.RETRY_WAIT;
            LocalDateTime nextRetryAt = targetState == TaskManagementReceiptOutboxState.RETRY_WAIT
                    ? LocalDateTime.now().plusSeconds(backoffSeconds(nextAttempt))
                    : null;
            LocalDateTime deadLetterAt = targetState == TaskManagementReceiptOutboxState.DEAD_LETTER
                    ? LocalDateTime.now()
                    : null;
            String errorCode = exception.getErrorCode() == null
                    ? "EXTERNAL_DEPENDENCY_FAILED"
                    : exception.getErrorCode().name();
            outboxMapper.markDeliveryFailure(outbox.getId(), targetState.name(), nextRetryAt, deadLetterAt,
                    safeCode(errorCode), safeSummary(errorCode, nextAttempt, targetState));
            log.warn("task-management receipt outbox 投递失败: receiptId={}, syncTaskId={}, syncExecutionId={}, eventType={}, attempt={}, targetState={}, errorCode={}",
                    outbox.getReceiptId(), outbox.getSyncTaskId(), outbox.getSyncExecutionId(), outbox.getEventType(),
                    nextAttempt, targetState.name(), safeCode(errorCode));
            if (throwWhenDeliveryRequired && properties.isDeliveryRequired()) {
                throw exception;
            }
            return targetState == TaskManagementReceiptOutboxState.DEAD_LETTER
                    ? TaskManagementReceiptOutboxDispatchResult.deadLettered(errorCode)
                    : TaskManagementReceiptOutboxDispatchResult.retryScheduled(errorCode);
        }
    }

    private TaskManagementReceiptOutboxDispatchResult deliverWithoutOutbox(TaskManagementExecutionReceiptRequest request,
                                                                           SyncActorContext actorContext,
                                                                           boolean throwWhenDeliveryRequired) {
        try {
            receiptClient.record(request, actorContext);
            return TaskManagementReceiptOutboxDispatchResult.delivered();
        } catch (PlatformBusinessException exception) {
            log.warn("task-management receipt outbox 已关闭，直接投递失败: syncTaskId={}, syncExecutionId={}, receiptId={}, exceptionType={}, deliveryRequired={}",
                    request == null ? null : request.getSyncTaskId(),
                    request == null ? null : request.getSyncExecutionId(),
                    request == null ? null : request.getReceiptId(),
                    exception.getClass().getSimpleName(),
                    properties.isDeliveryRequired());
            if (throwWhenDeliveryRequired && properties.isDeliveryRequired()) {
                throw exception;
            }
            return TaskManagementReceiptOutboxDispatchResult.retryScheduled("DIRECT_DELIVERY_FAILED_WITHOUT_OUTBOX");
        }
    }

    private void requireInputs(SyncTask task,
                               SyncExecution execution,
                               TaskManagementExecutionReceiptRequest request) {
        if (task == null || execution == null || request == null || request.getReceiptId() == null
                || request.getReceiptId().isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "task-management receipt outbox 缺少 task/execution/request/receiptId 上下文");
        }
    }

    private String writePayload(TaskManagementExecutionReceiptRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.INTERNAL_ERROR,
                    "task-management receipt outbox payload 序列化失败");
        }
    }

    private TaskManagementExecutionReceiptRequest readPayload(SyncTaskManagementReceiptOutbox outbox) {
        try {
            return objectMapper.readValue(outbox.getPayloadJson(), TaskManagementExecutionReceiptRequest.class);
        } catch (JsonProcessingException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.INTERNAL_ERROR,
                    "task-management receipt outbox payload 反序列化失败，receiptId=" + outbox.getReceiptId());
        }
    }

    private SyncActorContext actorContext(SyncTaskManagementReceiptOutbox outbox, SyncActorContext fallbackActorContext) {
        Long tenantId = outbox.getTenantId();
        Long actorId = outbox.getActorId() == null ? properties.getOutbox().getSystemActorId() : outbox.getActorId();
        String actorRole = hasText(outbox.getActorRole())
                ? outbox.getActorRole()
                : properties.getOutbox().getSystemActorRole();
        String traceId = hasText(outbox.getTraceId())
                ? outbox.getTraceId()
                : fallbackTraceId(fallbackActorContext);
        return new SyncActorContext(tenantId, actorId, actorRole, traceId);
    }

    private String fallbackTraceId(SyncActorContext fallbackActorContext) {
        if (fallbackActorContext != null && hasText(fallbackActorContext.traceId())) {
            return fallbackActorContext.traceId();
        }
        return properties.getOutbox().getTraceIdPrefix();
    }

    private int normalizeLimit(Integer requestedLimit) {
        int configured = Math.max(1, properties.getOutbox().getBatchSize());
        int requested = requestedLimit == null ? configured : Math.max(1, requestedLimit);
        return Math.min(Math.min(requested, configured), HARD_MAX_BATCH_SIZE);
    }

    private int safeAttempt(Integer attemptCount) {
        return attemptCount == null ? 0 : Math.max(0, attemptCount);
    }

    private int safeMaxAttempts(SyncTaskManagementReceiptOutbox outbox) {
        Integer recordMax = outbox.getMaxAttemptCount();
        if (recordMax != null && recordMax > 0) {
            return recordMax;
        }
        return Math.max(1, properties.getOutbox().getMaxAttempts());
    }

    private long backoffSeconds(int attempt) {
        long base = Math.max(1L, properties.getOutbox().getBaseBackoffSeconds());
        long max = Math.max(base, properties.getOutbox().getMaxBackoffSeconds());
        long multiplier = 1L << Math.min(10, Math.max(0, attempt - 1));
        return Math.min(max, base * multiplier);
    }

    private String safeSummary(String errorCode, int attempt, TaskManagementReceiptOutboxState targetState) {
        return "receipt delivery failed, errorCode=" + safeCode(errorCode)
                + ", attempt=" + attempt
                + ", nextState=" + targetState.name();
    }

    private String safeCode(String value) {
        if (!hasText(value)) {
            return "UNKNOWN";
        }
        return value.trim().replaceAll("[^A-Za-z0-9_\\-:.]", "_");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

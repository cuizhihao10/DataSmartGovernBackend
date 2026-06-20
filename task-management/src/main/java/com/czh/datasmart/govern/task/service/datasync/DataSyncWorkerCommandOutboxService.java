/**
 * @Author : Cui
 * @Date: 2026/06/20 16:40
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandOutboxService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.task.entity.DataSyncWorkerCommandOutbox;
import com.czh.datasmart.govern.task.mapper.DataSyncWorkerCommandOutboxMapper;
import com.czh.datasmart.govern.task.support.DataSyncWorkerCommandOutboxStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DataSync worker command outbox 服务。
 *
 * <p>该服务负责把 task-management 调用 data-sync 的动作变成可恢复、可审计、可补偿的本地事实。
 * 当前阶段仍保留同步 HTTP 调用 data-sync 的方式，但在调用前先 stage outbox，调用后记录 receipt。
 * 这样即使后续把 HTTP 直接调用替换为 Kafka dispatcher，业务语义也不需要重新设计。</p>
 *
 * <p>安全边界：</p>
 * <p>1. payloadJson 只保存低敏 ID 和控制字段；</p>
 * <p>2. 不保存模板名称、任务描述、SQL、连接串、密码、样本数据、工具参数正文或模型输出；</p>
 * <p>3. receipt 只保存 syncTaskId/syncExecutionId、状态和低敏消息摘要；</p>
 * <p>4. 幂等最终裁决依赖数据库唯一键，而不是 JVM 内存锁。</p>
 */
@Service
@RequiredArgsConstructor
public class DataSyncWorkerCommandOutboxService {

    public static final String DEFAULT_OPERATION = "DATA_SYNC_EXECUTE";
    private static final String OUTBOX_ID_PREFIX = "task-datasync:";
    private static final int MAX_ERROR_LENGTH = 500;

    private final DataSyncWorkerCommandOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;

    /**
     * 创建或复用 outbox 命令。
     *
     * @param request DataSync worker command 入箱请求。
     * @return outbox 快照；重复 command 会返回 duplicate=true。
     */
    @Transactional
    public DataSyncWorkerCommandOutboxSnapshot stageCommand(DataSyncWorkerCommandStageRequest request) {
        validateStageRequest(request);
        DataSyncWorkerCommandOutbox outbox = buildOutbox(request);
        try {
            outboxMapper.insert(outbox);
            return snapshot(outbox, false, "DataSync worker command 已写入 task-management outbox");
        } catch (DuplicateKeyException exception) {
            DataSyncWorkerCommandOutbox existing = findByCommandOrIdempotency(
                    request.getCommandId(),
                    request.getIdempotencyKey()
            );
            assertSameIdentity(request, existing);
            return snapshot(existing, true, "检测到重复 DataSync worker command，复用已有 outbox");
        }
    }

    /**
     * 标记命令开始投递。
     *
     * <p>如果命令已经处于 SUCCEEDED/FAILED/DEAD_LETTER 终态，不会把它回退为 DISPATCHING。
     * 这保证重复 worker、HTTP 超时补偿或人工重放不会把已经完成的 outbox 状态覆盖掉。</p>
     */
    @Transactional
    public DataSyncWorkerCommandOutboxSnapshot markDispatching(String commandId) {
        DataSyncWorkerCommandOutbox outbox = getRequiredByCommandId(commandId);
        DataSyncWorkerCommandOutboxStatus currentStatus = parseStatus(outbox.getStatus());
        if (currentStatus.terminal()) {
            return snapshot(outbox, true, "DataSync worker command 已是终态，不再重复标记投递");
        }
        outbox.setStatus(DataSyncWorkerCommandOutboxStatus.DISPATCHING.name());
        outbox.setAttemptCount(safeInt(outbox.getAttemptCount()) + 1);
        outbox.setDispatchedAt(LocalDateTime.now());
        outbox.setUpdateTime(LocalDateTime.now());
        outboxMapper.updateById(outbox);
        return snapshot(outbox, false, "DataSync worker command 已开始投递");
    }

    /**
     * 记录 data-sync 成功 receipt。
     */
    @Transactional
    public DataSyncWorkerCommandOutboxSnapshot recordSuccess(DataSyncWorkerReceiptRecordRequest request) {
        validateReceiptRequest(request);
        DataSyncWorkerCommandOutbox outbox = getRequiredByCommandId(request.getCommandId());
        outbox.setStatus(DataSyncWorkerCommandOutboxStatus.SUCCEEDED.name());
        outbox.setReceiptId(request.getReceiptId());
        outbox.setSyncTaskId(request.getSyncTaskId());
        outbox.setSyncExecutionId(request.getSyncExecutionId());
        outbox.setSideEffectStarted(true);
        outbox.setSideEffectExecuted(true);
        outbox.setLastError(null);
        outbox.setNextRetryAt(null);
        outbox.setUpdateTime(LocalDateTime.now());
        outboxMapper.updateById(outbox);
        return snapshot(outbox, false, "DataSync worker receipt 已记录为成功");
    }

    /**
     * 记录可重试失败。
     *
     * @param commandId command ID。
     * @param errorSummary 低敏错误摘要。
     * @param retryAfterSeconds 多少秒后允许重试。
     * @return outbox 快照。
     */
    @Transactional
    public DataSyncWorkerCommandOutboxSnapshot recordRetryableFailure(String commandId,
                                                                      String errorSummary,
                                                                      int retryAfterSeconds) {
        DataSyncWorkerCommandOutbox outbox = getRequiredByCommandId(commandId);
        outbox.setStatus(DataSyncWorkerCommandOutboxStatus.DEFERRED.name());
        outbox.setSideEffectStarted(true);
        outbox.setSideEffectExecuted(false);
        outbox.setLastError(truncate(errorSummary));
        outbox.setNextRetryAt(LocalDateTime.now().plusSeconds(Math.max(1, retryAfterSeconds)));
        outbox.setUpdateTime(LocalDateTime.now());
        outboxMapper.updateById(outbox);
        return snapshot(outbox, false, "DataSync worker command 已记录为可重试失败");
    }

    /**
     * 记录死信状态。
     *
     * <p>DEAD_LETTER 与 FAILED 的区别在于：FAILED 通常表示本次命令存在不可恢复的契约错误，例如 toolCode
     * 不支持、payload 损坏或幂等身份冲突；DEAD_LETTER 表示命令本身可能仍然有效，但已经超过自动重试上限，
     * 系统停止继续消耗自动 worker 资源，等待人工介入。</p>
     *
     * @param commandId command ID。
     * @param errorSummary 低敏错误摘要，不应包含内部 URL、SQL、连接串、凭据、样本数据或工具实参正文。
     * @return outbox 快照。
     */
    @Transactional
    public DataSyncWorkerCommandOutboxSnapshot recordDeadLetter(String commandId, String errorSummary) {
        DataSyncWorkerCommandOutbox outbox = getRequiredByCommandId(commandId);
        outbox.setStatus(DataSyncWorkerCommandOutboxStatus.DEAD_LETTER.name());
        outbox.setSideEffectStarted(Boolean.TRUE.equals(outbox.getSideEffectStarted()));
        outbox.setSideEffectExecuted(false);
        outbox.setLastError(truncate(errorSummary));
        outbox.setNextRetryAt(null);
        outbox.setUpdateTime(LocalDateTime.now());
        outboxMapper.updateById(outbox);
        return snapshot(outbox, false, "DataSync worker command 已超过自动重试上限并进入 DEAD_LETTER");
    }

    /**
     * 记录不可恢复失败。
     */
    @Transactional
    public DataSyncWorkerCommandOutboxSnapshot recordFatalFailure(String commandId, String errorSummary) {
        DataSyncWorkerCommandOutbox outbox = getRequiredByCommandId(commandId);
        outbox.setStatus(DataSyncWorkerCommandOutboxStatus.FAILED.name());
        outbox.setSideEffectStarted(Boolean.TRUE.equals(outbox.getSideEffectStarted()));
        outbox.setSideEffectExecuted(false);
        outbox.setLastError(truncate(errorSummary));
        outbox.setNextRetryAt(null);
        outbox.setUpdateTime(LocalDateTime.now());
        outboxMapper.updateById(outbox);
        return snapshot(outbox, false, "DataSync worker command 已记录为不可恢复失败");
    }

    private DataSyncWorkerCommandOutbox buildOutbox(DataSyncWorkerCommandStageRequest request) {
        LocalDateTime now = LocalDateTime.now();
        String payloadJson = safePayloadJson(request);
        DataSyncWorkerCommandOutbox outbox = new DataSyncWorkerCommandOutbox();
        outbox.setOutboxId(OUTBOX_ID_PREFIX + request.getCommandId().trim());
        outbox.setCommandId(request.getCommandId().trim());
        outbox.setIdempotencyKey(request.getIdempotencyKey().trim());
        outbox.setTaskId(request.getTaskId());
        outbox.setAgentRunId(request.getRunId().trim());
        outbox.setAgentSessionId(trimToNull(request.getSessionId()));
        outbox.setAuditId(trimToNull(request.getAuditId()));
        outbox.setToolCode(request.getToolCode().trim());
        outbox.setTargetService(request.getTargetService().trim());
        outbox.setOperation(trimToDefault(request.getOperation(), DEFAULT_OPERATION));
        outbox.setTenantId(request.getTenantId());
        outbox.setProjectId(request.getProjectId());
        outbox.setWorkspaceId(request.getWorkspaceId());
        outbox.setActorId(trimToNull(request.getActorId()));
        outbox.setTraceId(trimToNull(request.getTraceId()));
        outbox.setTemplateId(request.getTemplateId());
        outbox.setSyncTemplateId(request.getSyncTemplateId());
        outbox.setStatus(DataSyncWorkerCommandOutboxStatus.PENDING.name());
        outbox.setAttemptCount(0);
        outbox.setPayloadJson(payloadJson);
        outbox.setPayloadSizeBytes(payloadJson.getBytes(StandardCharsets.UTF_8).length);
        outbox.setPayloadTruncated(false);
        outbox.setSideEffectStarted(false);
        outbox.setSideEffectExecuted(false);
        outbox.setCreateTime(now);
        outbox.setUpdateTime(now);
        return outbox;
    }

    /**
     * 构造低敏 payload。
     *
     * <p>这里刻意不直接序列化 `DataSyncWorkerCommandStageRequest`，
     * 因为未来 request 可能新增 name/description/filter 等字段。白名单序列化能避免敏感字段意外进 outbox。</p>
     */
    private String safePayloadJson(DataSyncWorkerCommandStageRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "datasmart.task.data-sync-worker-command.v1");
        payload.put("commandId", request.getCommandId());
        payload.put("taskId", request.getTaskId());
        payload.put("runId", request.getRunId());
        payload.put("auditId", request.getAuditId());
        payload.put("toolCode", request.getToolCode());
        payload.put("targetService", request.getTargetService());
        payload.put("operation", trimToDefault(request.getOperation(), DEFAULT_OPERATION));
        payload.put("tenantId", request.getTenantId());
        payload.put("projectId", request.getProjectId());
        payload.put("workspaceId", request.getWorkspaceId());
        payload.put("templateId", request.getTemplateId());
        payload.put("syncTemplateId", request.getSyncTemplateId());
        payload.put("priority", trimToNull(request.getPriority()));
        payload.put("runMode", trimToNull(request.getRunMode()));
        payload.put("ownerId", request.getOwnerId());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("DataSync worker command 低敏 payload 序列化失败", exception);
        }
    }

    private DataSyncWorkerCommandOutbox findByCommandOrIdempotency(String commandId, String idempotencyKey) {
        DataSyncWorkerCommandOutbox existing = outboxMapper.selectOne(
                new LambdaQueryWrapper<DataSyncWorkerCommandOutbox>()
                        .and(wrapper -> wrapper.eq(DataSyncWorkerCommandOutbox::getCommandId, commandId.trim())
                                .or()
                                .eq(DataSyncWorkerCommandOutbox::getIdempotencyKey, idempotencyKey.trim()))
                        .last("LIMIT 1")
        );
        if (existing == null) {
            throw new IllegalStateException("检测到 DataSync worker outbox 唯一键冲突，但未找到已有记录");
        }
        return existing;
    }

    private DataSyncWorkerCommandOutbox getRequiredByCommandId(String commandId) {
        requireText(commandId, "commandId");
        DataSyncWorkerCommandOutbox outbox = outboxMapper.selectOne(
                new LambdaQueryWrapper<DataSyncWorkerCommandOutbox>()
                        .eq(DataSyncWorkerCommandOutbox::getCommandId, commandId.trim())
                        .last("LIMIT 1")
        );
        if (outbox == null) {
            throw new IllegalStateException("DataSync worker command outbox 不存在: " + commandId);
        }
        return outbox;
    }

    private void assertSameIdentity(DataSyncWorkerCommandStageRequest request, DataSyncWorkerCommandOutbox existing) {
        if (!request.getCommandId().trim().equals(existing.getCommandId())
                || !request.getIdempotencyKey().trim().equals(existing.getIdempotencyKey())) {
            throw new IllegalStateException("DataSync worker command 身份冲突：commandId 或 idempotencyKey 被错误复用");
        }
    }

    private void validateStageRequest(DataSyncWorkerCommandStageRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("DataSync worker command 入箱请求不能为空");
        }
        requireText(request.getCommandId(), "commandId");
        requireText(request.getIdempotencyKey(), "idempotencyKey");
        requireText(request.getRunId(), "runId");
        requireText(request.getToolCode(), "toolCode");
        requireText(request.getTargetService(), "targetService");
        requirePositive(request.getTaskId(), "taskId");
        requirePositive(request.getTenantId(), "tenantId");
        requirePositive(request.getProjectId(), "projectId");
        if (request.getTemplateId() == null && request.getSyncTemplateId() == null) {
            throw new IllegalArgumentException("DataSync worker command 必须包含 templateId 或 syncTemplateId");
        }
    }

    private void validateReceiptRequest(DataSyncWorkerReceiptRecordRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("DataSync worker receipt 不能为空");
        }
        requireText(request.getCommandId(), "commandId");
        requireText(request.getReceiptId(), "receiptId");
        requirePositive(request.getSyncTaskId(), "syncTaskId");
    }

    private void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
    }

    private void requirePositive(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + " 必须大于 0");
        }
    }

    private DataSyncWorkerCommandOutboxStatus parseStatus(String status) {
        try {
            return DataSyncWorkerCommandOutboxStatus.valueOf(status);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("未知 DataSync worker command outbox 状态: " + status);
        }
    }

    private DataSyncWorkerCommandOutboxSnapshot snapshot(DataSyncWorkerCommandOutbox outbox,
                                                         boolean duplicate,
                                                         String message) {
        return new DataSyncWorkerCommandOutboxSnapshot(
                outbox.getOutboxId(),
                outbox.getCommandId(),
                outbox.getIdempotencyKey(),
                outbox.getStatus(),
                duplicate,
                outbox.getAttemptCount(),
                outbox.getReceiptId(),
                outbox.getSyncTaskId(),
                outbox.getSyncExecutionId(),
                message
        );
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String trimToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}

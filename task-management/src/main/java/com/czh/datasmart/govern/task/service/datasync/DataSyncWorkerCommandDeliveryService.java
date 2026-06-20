/**
 * @Author : Cui
 * @Date: 2026/06/20 21:43
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandDeliveryService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.task.entity.DataSyncWorkerCommandOutbox;
import com.czh.datasmart.govern.task.mapper.DataSyncWorkerCommandOutboxMapper;
import com.czh.datasmart.govern.task.service.agent.DataSyncAgentExecuteRequest;
import com.czh.datasmart.govern.task.service.agent.DataSyncAgentExecuteResponse;
import com.czh.datasmart.govern.task.support.DataSyncWorkerCommandOutboxStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * DataSync worker outbox 投递服务。
 *
 * <p>本服务是 task-management 与 datasource-management 之间真正跨服务副作用的控制点。
 * 它不负责创建 outbox，也不负责普通任务状态机，而是专注于一件事：
 * 把已经可靠落库的 DataSync outbox 命令发送给下游内部幂等入口，并把下游 receipt 回写到本地 outbox。</p>
 *
 * <p>为什么要从 {@code DataSyncExecuteAgentAsyncToolAdapter} 里拆出来：</p>
 * <p>1. Agent 工具适配器应该只负责把 Agent payload 变成业务命令，不应该同时承载 HTTP 投递、重试和 receipt 回写；</p>
 * <p>2. 手动 dispatch-batch、后台定时 dispatcher、未来 Kafka publisher 都应该复用同一套投递语义；</p>
 * <p>3. 单独拆分后，文件职责更稳定，也更容易把每个文件控制在 500 行以内。</p>
 *
 * <p>状态流转：</p>
 * <p>1. {@link #deliverCommand(String)} 用于“尚未被 claim 的单条命令”，会先把 PENDING/DEFERRED 推进到 DISPATCHING；</p>
 * <p>2. {@link #deliverAlreadyClaimedCommand(String)} 用于 batch dispatcher，前置 claim 已经把命令推进到 DISPATCHING，
 * 因此这里不会重复递增 attemptCount；</p>
 * <p>3. 下游成功返回后调用 {@link DataSyncWorkerCommandOutboxService#recordSuccess(DataSyncWorkerReceiptRecordRequest)}；</p>
 * <p>4. 网络、超时、连接拒绝等临时问题进入 DEFERRED；</p>
 * <p>5. 本地契约错误、payload 损坏、toolCode 不匹配或下游返回非成功 envelope 时进入 FAILED。</p>
 *
 * <p>敏感数据边界：</p>
 * <p>本服务会在内部读取 outbox.payloadJson，但只解析白名单低敏字段 priority、runMode、ownerId。
 * 它不会把 payloadJson 原文返回给 Controller、Agent Runtime 或诊断视图；错误摘要也不会包含 datasource 内部 URL、SQL、
 * 凭据、连接串、样本数据、prompt、模型输出或工具实参正文。</p>
 */
@Service
@RequiredArgsConstructor
public class DataSyncWorkerCommandDeliveryService {

    private static final String DISPATCH_BATCH_SCHEMA_VERSION =
            "datasmart.task.data-sync-worker-outbox.dispatch-batch.v1";
    private static final String TOOL_CODE = "data-sync.execute";
    private static final int RETRY_AFTER_SECONDS = 30;
    private static final int MAX_MESSAGE_LENGTH = 300;

    private final DataSyncWorkerCommandOutboxMapper outboxMapper;
    private final DataSyncWorkerCommandOutboxService outboxService;
    private final DataSyncWorkerCommandOutboxDispatchService claimService;
    private final DataSyncAgentExecuteClient executeClient;
    private final ObjectMapper objectMapper;

    /**
     * 投递一条尚未被外部 claim 的 outbox 命令。
     *
     * <p>这个入口主要给历史 Agent 工具适配器使用：适配器先 stage outbox，再调用本方法。
     * 本方法会负责把命令标记为 DISPATCHING，从而保持 attemptCount、dispatchedAt 和状态推进都由同一套账本管理。</p>
     *
     * @param commandId Agent command ID。
     * @return 单条投递的低敏结果。
     */
    public DataSyncWorkerCommandDeliveryResult deliverCommand(String commandId) {
        DataSyncWorkerCommandOutboxSnapshot snapshot = outboxService.markDispatching(commandId);
        if (isTerminal(snapshot.status())) {
            return fromTerminalSnapshot(snapshot);
        }
        return deliverLoadedCommand(loadByCommandId(commandId), false);
    }

    /**
     * 投递一条已经被 claim 服务推进到 DISPATCHING 的 outbox 命令。
     *
     * <p>这个入口主要给 dispatch-batch 使用。claim 阶段已经完成条件更新和 attemptCount 递增，
     * 因此这里不能再次调用 markDispatching，否则一次投递会被错误统计为两次 attempt。</p>
     *
     * @param commandId 已领取命令的 command ID。
     * @return 单条投递的低敏结果。
     */
    public DataSyncWorkerCommandDeliveryResult deliverAlreadyClaimedCommand(String commandId) {
        return deliverLoadedCommand(loadByCommandId(commandId), true);
    }

    /**
     * 领取并投递一批 DataSync worker outbox 命令。
     *
     * <p>该方法是当前阶段“真实 dispatcher 循环”的可手动触发版本：
     * claim 服务负责并发安全地领取 PENDING/到期 DEFERRED，delivery 服务负责逐条调用 datasource-management。
     * 后续如果接入后台定时任务或 Kafka publisher，应该复用本方法或复用其中的单条 delivery 入口，而不是再写第二套状态机。</p>
     *
     * @param request 批量投递请求。
     * @return 本轮投递的低敏统计和单条结果。
     */
    public DataSyncWorkerOutboxDispatchBatchResult dispatchBatch(DataSyncWorkerOutboxDispatchBatchRequest request) {
        DataSyncWorkerOutboxDispatchBatchRequest safeRequest = requireDispatchRequest(request);
        DataSyncWorkerOutboxClaimResult claimResult = claimService.claimDispatchCandidates(new DataSyncWorkerOutboxClaimRequest(
                safeRequest.getExecutorId(),
                safeRequest.getTenantId(),
                safeRequest.getProjectId(),
                safeRequest.getLimit(),
                safeRequest.includeDeferredCommands()
        ));

        List<DataSyncWorkerCommandDeliveryResult> results = new ArrayList<>();
        for (DataSyncWorkerCommandOutboxView candidate : claimResult.candidates()) {
            results.add(deliverAlreadyClaimedCommand(candidate.commandId()));
        }

        int succeeded = 0;
        int deferred = 0;
        int failed = 0;
        int skipped = 0;
        for (DataSyncWorkerCommandDeliveryResult result : results) {
            if (result.success()) {
                succeeded++;
            } else if (result.retryable()) {
                deferred++;
            } else if ("SKIPPED_INVALID_STATE".equals(result.outcome())) {
                skipped++;
            } else {
                failed++;
            }
        }

        return new DataSyncWorkerOutboxDispatchBatchResult(
                DISPATCH_BATCH_SCHEMA_VERSION,
                safeRequest.getExecutorId().trim(),
                LocalDateTime.now(),
                claimResult.claimedCount(),
                results.size(),
                succeeded,
                deferred,
                failed,
                skipped,
                List.copyOf(results),
                claimResult.warnings()
        );
    }

    /**
     * 执行已经加载出的 outbox 命令。
     *
     * @param outbox 数据库中的 outbox 实体，包含内部 payloadJson。
     * @param alreadyClaimed true 表示该命令已经被 claim 服务标记为 DISPATCHING。
     * @return 投递结果。
     */
    private DataSyncWorkerCommandDeliveryResult deliverLoadedCommand(DataSyncWorkerCommandOutbox outbox,
                                                                     boolean alreadyClaimed) {
        DataSyncWorkerCommandOutboxStatus status = parseStatus(outbox.getStatus());
        if (status.terminal()) {
            return fromTerminalOutbox(outbox);
        }
        if (alreadyClaimed && status != DataSyncWorkerCommandOutboxStatus.DISPATCHING) {
            return skippedInvalidState(outbox, "已领取投递入口要求 outbox 处于 DISPATCHING，但当前状态为 " + status.name());
        }

        DataSyncAgentExecuteRequest request;
        try {
            request = buildExecuteRequest(outbox);
        } catch (RuntimeException exception) {
            return recordFatal(outbox.getCommandId(), "DataSync worker outbox payload 契约无效: "
                    + safeExceptionType(exception));
        }

        try {
            DataSyncAgentExecuteResponse response = executeClient.execute(request);
            return recordSuccess(request, response);
        } catch (RestClientException exception) {
            return recordRetryable(outbox.getCommandId(), "data-sync 内部执行入口暂时不可用: "
                    + safeExceptionType(exception));
        } catch (RuntimeException exception) {
            return recordFatal(outbox.getCommandId(), "data-sync Agent 执行契约失败: "
                    + safeExceptionType(exception));
        }
    }

    /**
     * 从 outbox 实体还原 datasource-management 内部执行请求。
     *
     * <p>注意这里不直接反序列化成完整业务对象，而是明确从 entity 字段和低敏 payload 白名单中取值。
     * outbox.entity 中的 tenant/project/template 等字段是查询和状态机需要的强字段；payloadJson 只补充 priority、
     * runMode、ownerId 这类低敏执行选项，避免未来 payload 扩字段时被无意透传到下游。</p>
     */
    private DataSyncAgentExecuteRequest buildExecuteRequest(DataSyncWorkerCommandOutbox outbox) {
        if (!TOOL_CODE.equalsIgnoreCase(trimToEmpty(outbox.getToolCode()))) {
            throw new IllegalStateException("不支持的 DataSync worker toolCode: " + outbox.getToolCode());
        }
        JsonNode payload = safePayload(outbox);
        DataSyncAgentExecuteRequest request = new DataSyncAgentExecuteRequest();
        request.setCommandId(outbox.getCommandId());
        request.setIdempotencyKey(outbox.getIdempotencyKey());
        request.setAuditId(outbox.getAuditId());
        request.setSessionId(outbox.getAgentSessionId());
        request.setRunId(outbox.getAgentRunId());
        request.setToolCode(outbox.getToolCode());
        request.setTenantId(outbox.getTenantId());
        request.setProjectId(outbox.getProjectId());
        request.setWorkspaceId(outbox.getWorkspaceId());
        request.setActorId(outbox.getActorId());
        request.setTraceId(outbox.getTraceId());
        request.setTemplateId(outbox.getTemplateId());
        request.setSyncTemplateId(outbox.getSyncTemplateId());
        request.setPriority(text(payload, "priority"));
        request.setRunMode(text(payload, "runMode"));
        request.setOwnerId(longValue(payload, "ownerId"));
        return request;
    }

    private DataSyncWorkerCommandDeliveryResult recordSuccess(DataSyncAgentExecuteRequest request,
                                                              DataSyncAgentExecuteResponse response) {
        String receiptId = "data-sync-receipt:" + request.getCommandId()
                + ":" + response.syncTaskId()
                + ":" + (response.syncExecutionId() == null ? "pending-execution" : response.syncExecutionId());
        DataSyncWorkerCommandOutboxSnapshot receipt = outboxService.recordSuccess(new DataSyncWorkerReceiptRecordRequest(
                request.getCommandId(),
                receiptId,
                response.syncTaskId(),
                response.syncExecutionId(),
                response.state(),
                response.created(),
                response.queued(),
                response.duplicate(),
                response.message()
        ));
        return new DataSyncWorkerCommandDeliveryResult(
                response.commandId(),
                receipt.outboxId(),
                receipt.status(),
                response.duplicate() ? "REUSED_SUCCEEDED" : "SUCCEEDED",
                true,
                false,
                receipt.receiptId(),
                response.syncTaskId(),
                response.syncExecutionId(),
                response.state(),
                response.created(),
                response.queued(),
                response.duplicate(),
                safeMessage(response.message())
        );
    }

    private DataSyncWorkerCommandDeliveryResult recordRetryable(String commandId, String message) {
        DataSyncWorkerCommandOutboxSnapshot failure = outboxService.recordRetryableFailure(
                commandId,
                safeMessage(message),
                RETRY_AFTER_SECONDS
        );
        return fromSnapshot(failure, "DEFERRED", false, true, safeMessage(message));
    }

    private DataSyncWorkerCommandDeliveryResult recordFatal(String commandId, String message) {
        DataSyncWorkerCommandOutboxSnapshot failure = outboxService.recordFatalFailure(commandId, safeMessage(message));
        return fromSnapshot(failure, "FAILED", false, false, safeMessage(message));
    }

    private DataSyncWorkerCommandDeliveryResult fromTerminalSnapshot(DataSyncWorkerCommandOutboxSnapshot snapshot) {
        boolean success = DataSyncWorkerCommandOutboxStatus.SUCCEEDED.name().equals(snapshot.status());
        return new DataSyncWorkerCommandDeliveryResult(
                snapshot.commandId(),
                snapshot.outboxId(),
                snapshot.status(),
                success ? "REUSED_SUCCEEDED" : "REJECTED_TERMINAL",
                success,
                false,
                snapshot.receiptId(),
                snapshot.syncTaskId(),
                snapshot.syncExecutionId(),
                null,
                null,
                null,
                snapshot.duplicate(),
                snapshot.message()
        );
    }

    private DataSyncWorkerCommandDeliveryResult fromTerminalOutbox(DataSyncWorkerCommandOutbox outbox) {
        boolean success = DataSyncWorkerCommandOutboxStatus.SUCCEEDED.name().equals(outbox.getStatus());
        return new DataSyncWorkerCommandDeliveryResult(
                outbox.getCommandId(),
                outbox.getOutboxId(),
                outbox.getStatus(),
                success ? "REUSED_SUCCEEDED" : "REJECTED_TERMINAL",
                success,
                false,
                outbox.getReceiptId(),
                outbox.getSyncTaskId(),
                outbox.getSyncExecutionId(),
                null,
                null,
                null,
                null,
                success ? "DataSync worker command 已成功，复用本地 receipt" : "DataSync worker command 已处于终态，拒绝重复投递"
        );
    }

    private DataSyncWorkerCommandDeliveryResult fromSnapshot(DataSyncWorkerCommandOutboxSnapshot snapshot,
                                                             String outcome,
                                                             boolean success,
                                                             boolean retryable,
                                                             String message) {
        return new DataSyncWorkerCommandDeliveryResult(
                snapshot.commandId(),
                snapshot.outboxId(),
                snapshot.status(),
                outcome,
                success,
                retryable,
                snapshot.receiptId(),
                snapshot.syncTaskId(),
                snapshot.syncExecutionId(),
                null,
                null,
                null,
                snapshot.duplicate(),
                message
        );
    }

    private DataSyncWorkerCommandDeliveryResult skippedInvalidState(DataSyncWorkerCommandOutbox outbox, String message) {
        return new DataSyncWorkerCommandDeliveryResult(
                outbox.getCommandId(),
                outbox.getOutboxId(),
                outbox.getStatus(),
                "SKIPPED_INVALID_STATE",
                false,
                false,
                outbox.getReceiptId(),
                outbox.getSyncTaskId(),
                outbox.getSyncExecutionId(),
                null,
                null,
                null,
                null,
                safeMessage(message)
        );
    }

    private DataSyncWorkerOutboxDispatchBatchRequest requireDispatchRequest(
            DataSyncWorkerOutboxDispatchBatchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("DataSync worker outbox 批量投递请求不能为空");
        }
        if (request.getExecutorId() == null || request.getExecutorId().isBlank()) {
            throw new IllegalArgumentException("executorId 不能为空");
        }
        return request;
    }

    private DataSyncWorkerCommandOutbox loadByCommandId(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId 不能为空");
        }
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

    private JsonNode safePayload(DataSyncWorkerCommandOutbox outbox) {
        String payloadJson = outbox.getPayloadJson();
        if (payloadJson == null || payloadJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(payloadJson);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("DataSync worker outbox payload_json 不是合法 JSON", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("DataSync worker outbox payload_json 解析失败", exception);
        }
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return text == null || text.isBlank() ? null : text.trim();
    }

    private Long longValue(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.longValue();
        }
        String text = value.asText(null);
        return text == null || text.isBlank() ? null : Long.parseLong(text.trim());
    }

    private DataSyncWorkerCommandOutboxStatus parseStatus(String status) {
        try {
            return DataSyncWorkerCommandOutboxStatus.valueOf(status);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("未知 DataSync worker command outbox 状态: " + status);
        }
    }

    private boolean isTerminal(String status) {
        return parseStatus(status).terminal();
    }

    private String safeExceptionType(RuntimeException exception) {
        return exception.getClass().getSimpleName();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "DataSync worker command 投递结果无详细说明";
        }
        String normalized = message.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= MAX_MESSAGE_LENGTH ? normalized : normalized.substring(0, MAX_MESSAGE_LENGTH);
    }
}

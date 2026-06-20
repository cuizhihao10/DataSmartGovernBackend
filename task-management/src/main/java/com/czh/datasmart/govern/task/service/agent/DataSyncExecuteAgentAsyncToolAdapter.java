/**
 * @Author : Cui
 * @Date: 2026/05/31 23:35
 * @Description DataSmart Govern Backend - DataSyncExecuteAgentAsyncToolAdapter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerCommandOutboxService;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerCommandOutboxSnapshot;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerCommandStageRequest;
import com.czh.datasmart.govern.task.service.datasync.DataSyncWorkerReceiptRecordRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * `data-sync.execute` Agent 异步工具适配器。
 *
 * <p>它是当前 worker 的第一个真实业务工具适配器。适配器只调用 data-sync 的内部幂等入口，
 * 不调用公开的“创建任务 + 运行任务”双接口，也不信任 payload 中的 targetEndpoint。
 * 这样可以同时解决三个生产级问题：</p>
 *
 * <p>1. 安全：只有白名单 toolCode 会被执行，模型不能通过构造 URL 触发任意内网接口；</p>
 * <p>2. 幂等：data-sync 负责把创建和入队合并成同一个幂等业务动作；</p>
 * <p>3. 解耦：task-management 只知道 data-sync.execute 的内部契约，不理解 data-sync 的任务状态机细节。</p>
 */
@Component
@RequiredArgsConstructor
public class DataSyncExecuteAgentAsyncToolAdapter implements AgentAsyncToolExecutor {

    public static final String TOOL_CODE = "data-sync.execute";

    private final AgentAsyncToolWorkerProperties properties;
    private final RestClient.Builder restClientBuilder;
    private final DataSyncWorkerCommandOutboxService outboxService;

    @Override
    public boolean supports(String toolCode) {
        return TOOL_CODE.equalsIgnoreCase(toolCode == null ? "" : toolCode.trim());
    }

    @Override
    public AgentAsyncToolExecutionResult execute(AgentAsyncToolResolvedPayload payload) {
        DataSyncAgentExecuteRequest request = buildRequest(payload);
        /*
         * 先写 task-management 本地 outbox，再越过 data-sync 副作用边界。
         * 当前适配器仍然保持同步 HTTP 调用，原因是 data-sync 的远程批处理 dispatcher 还未完成；
         * 但 outbox 已经让这次调用具备了“可追踪、可补偿、可审计”的本地事实。
         */
        DataSyncWorkerCommandOutboxSnapshot outbox = outboxService.stageCommand(stageRequest(payload, request));
        outbox = outboxService.markDispatching(request.getCommandId());
        if ("SUCCEEDED".equals(outbox.status())) {
            return AgentAsyncToolExecutionResult.success(
                    "DataSync worker command 已有成功 receipt，本次复用 outbox 结果，不重复调用 data-sync。",
                    output(outbox)
            );
        }
        if ("FAILED".equals(outbox.status()) || "DEAD_LETTER".equals(outbox.status())) {
            return AgentAsyncToolExecutionResult.fatalFailure(
                    "DataSync worker command 已处于终态 " + outbox.status() + "，拒绝重复投递。",
                    output(outbox)
            );
        }
        try {
            PlatformApiResponse<DataSyncAgentExecuteResponse> response = restClientBuilder
                    .baseUrl(properties.getDataSyncBaseUrl())
                    .build()
                    .post()
                    .uri("/internal/data-sync/agent/tasks/execute")
                    .headers(headers -> applyHeaders(headers, payload))
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            DataSyncAgentExecuteResponse data = unwrap(response);
            DataSyncWorkerCommandOutboxSnapshot receipt = outboxService.recordSuccess(receiptRequest(request, data));
            return AgentAsyncToolExecutionResult.success(
                    data.message(),
                    output(data, receipt)
            );
        } catch (RestClientException exception) {
            DataSyncWorkerCommandOutboxSnapshot failure = outboxService.recordRetryableFailure(
                    request.getCommandId(),
                    "调用 data-sync 内部 Agent 执行入口失败: " + exception.getMessage(),
                    30
            );
            return AgentAsyncToolExecutionResult.retryableFailure(
                    "调用 data-sync 内部 Agent 执行入口失败，可由 worker 延迟重试: " + exception.getMessage(),
                    Map.of(
                            "toolCode", TOOL_CODE,
                            "commandId", payload.commandId(),
                            "dataSyncWorkerOutboxId", failure.outboxId(),
                            "dataSyncWorkerOutboxStatus", failure.status()
                    )
            );
        } catch (RuntimeException exception) {
            outboxService.recordFatalFailure(
                    request.getCommandId(),
                    "data-sync.execute 发生不可恢复异常: " + exception.getMessage()
            );
            throw exception;
        }
    }

    private DataSyncAgentExecuteRequest buildRequest(AgentAsyncToolResolvedPayload payload) {
        Long templateId = longArgument(payload, "templateId");
        Long syncTemplateId = longArgument(payload, "syncTemplateId");
        if (templateId == null && syncTemplateId == null) {
            throw new IllegalStateException("data-sync.execute 必须在 planArguments 中提供 templateId 或 syncTemplateId");
        }
        DataSyncAgentExecuteRequest request = new DataSyncAgentExecuteRequest();
        request.setCommandId(payload.commandId());
        request.setIdempotencyKey("agent-async-tool:" + payload.commandId());
        request.setAuditId(payload.auditId());
        request.setSessionId(payload.sessionId());
        request.setRunId(payload.runId());
        request.setToolCode(payload.toolCode());
        request.setTenantId(payload.tenantId());
        request.setProjectId(payload.projectId());
        request.setWorkspaceId(payload.workspaceId());
        request.setActorId(payload.actorId());
        request.setTraceId(payload.traceId());
        request.setTemplateId(templateId);
        request.setSyncTemplateId(syncTemplateId);
        request.setName(stringArgument(payload, "name"));
        request.setDescription(stringArgument(payload, "description"));
        request.setPriority(stringArgument(payload, "priority"));
        request.setRunMode(stringArgument(payload, "runMode"));
        request.setOwnerId(longArgument(payload, "ownerId"));
        return request;
    }

    /**
     * 构造 DataSync worker command outbox 入箱请求。
     *
     * <p>该请求只保存低敏控制字段。即使 Agent payload 中存在 name/description，outbox 也不保存这些用户输入文本，
     * 避免把可能包含业务口径、筛选条件或自然语言上下文的内容复制到跨服务命令账本。</p>
     */
    private DataSyncWorkerCommandStageRequest stageRequest(AgentAsyncToolResolvedPayload payload,
                                                           DataSyncAgentExecuteRequest request) {
        return new DataSyncWorkerCommandStageRequest(
                request.getCommandId(),
                request.getIdempotencyKey(),
                payload.taskId(),
                payload.sessionId(),
                payload.runId(),
                payload.auditId(),
                payload.toolCode(),
                payload.targetService(),
                DataSyncWorkerCommandOutboxService.DEFAULT_OPERATION,
                payload.tenantId(),
                payload.projectId(),
                payload.workspaceId(),
                payload.actorId(),
                payload.traceId(),
                request.getTemplateId(),
                request.getSyncTemplateId(),
                request.getPriority(),
                request.getRunMode(),
                request.getOwnerId()
        );
    }

    /**
     * 构造成功 receipt。
     *
     * <p>receiptId 必须稳定可重放。这里使用 commandId + syncTaskId + syncExecutionId，
     * 表示同一条 command 收到同一个 data-sync 结果时应被视为同一份 receipt。</p>
     */
    private DataSyncWorkerReceiptRecordRequest receiptRequest(DataSyncAgentExecuteRequest request,
                                                              DataSyncAgentExecuteResponse data) {
        String receiptId = "data-sync-receipt:" + request.getCommandId()
                + ":" + data.syncTaskId()
                + ":" + (data.syncExecutionId() == null ? "pending-execution" : data.syncExecutionId());
        return new DataSyncWorkerReceiptRecordRequest(
                request.getCommandId(),
                receiptId,
                data.syncTaskId(),
                data.syncExecutionId(),
                data.state(),
                data.created(),
                data.queued(),
                data.duplicate(),
                data.message()
        );
    }

    private void applyHeaders(HttpHeaders headers, AgentAsyncToolResolvedPayload payload) {
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "task-management");
        headers.set(PlatformContextHeaders.ACTOR_ROLE, "SERVICE_ACCOUNT");
        headers.set(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT");
        if (payload.traceId() != null && !payload.traceId().isBlank()) {
            headers.set(PlatformContextHeaders.TRACE_ID, payload.traceId());
        }
        if (payload.tenantId() != null) {
            headers.set(PlatformContextHeaders.TENANT_ID, String.valueOf(payload.tenantId()));
        }
    }

    private DataSyncAgentExecuteResponse unwrap(PlatformApiResponse<DataSyncAgentExecuteResponse> response) {
        if (response == null) {
            throw new IllegalStateException("data-sync 返回空响应");
        }
        if (response.getCode() == null || response.getCode() != 0) {
            throw new IllegalStateException("data-sync Agent 执行失败，code=" + response.getCode()
                    + ", reason=" + response.getReason() + ", message=" + response.getMessage());
        }
        if (response.getData() == null) {
            throw new IllegalStateException("data-sync Agent 执行响应 data 为空");
        }
        return response.getData();
    }

    private Map<String, Object> output(DataSyncAgentExecuteResponse data,
                                       DataSyncWorkerCommandOutboxSnapshot receipt) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("downstreamService", "data-sync");
        output.put("commandId", data.commandId());
        output.put("dataSyncWorkerOutboxId", receipt.outboxId());
        output.put("dataSyncWorkerOutboxStatus", receipt.status());
        output.put("dataSyncWorkerReceiptId", receipt.receiptId());
        output.put("syncTaskId", data.syncTaskId());
        output.put("syncExecutionId", data.syncExecutionId());
        output.put("state", data.state());
        output.put("created", data.created());
        output.put("queued", data.queued());
        output.put("duplicate", data.duplicate());
        output.put("message", data.message());
        return output;
    }

    /**
     * 从已有 outbox 快照构造适配器输出。
     *
     * <p>这个分支用于重复 command 已经成功的幂等短路场景。
     * 输出仍只包含低敏引用，不包含历史响应正文或 data-sync 内部执行详情。</p>
     */
    private Map<String, Object> output(DataSyncWorkerCommandOutboxSnapshot snapshot) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("downstreamService", "data-sync");
        output.put("commandId", snapshot.commandId());
        output.put("dataSyncWorkerOutboxId", snapshot.outboxId());
        output.put("dataSyncWorkerOutboxStatus", snapshot.status());
        output.put("dataSyncWorkerReceiptId", snapshot.receiptId());
        output.put("syncTaskId", snapshot.syncTaskId());
        output.put("syncExecutionId", snapshot.syncExecutionId());
        return output;
    }

    private Long longArgument(AgentAsyncToolResolvedPayload payload, String name) {
        Object value = payload.planArguments().get(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text.trim());
        }
        return null;
    }

    private String stringArgument(AgentAsyncToolResolvedPayload payload, String name) {
        Object value = payload.planArguments().get(name);
        return value == null ? null : String.valueOf(value).trim();
    }
}

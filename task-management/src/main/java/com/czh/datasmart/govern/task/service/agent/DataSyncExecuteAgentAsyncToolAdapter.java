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

    @Override
    public boolean supports(String toolCode) {
        return TOOL_CODE.equalsIgnoreCase(toolCode == null ? "" : toolCode.trim());
    }

    @Override
    public AgentAsyncToolExecutionResult execute(AgentAsyncToolResolvedPayload payload) {
        DataSyncAgentExecuteRequest request = buildRequest(payload);
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
            return AgentAsyncToolExecutionResult.success(
                    data.message(),
                    output(data)
            );
        } catch (RestClientException exception) {
            return AgentAsyncToolExecutionResult.retryableFailure(
                    "调用 data-sync 内部 Agent 执行入口失败，可由 worker 延迟重试: " + exception.getMessage(),
                    Map.of("toolCode", TOOL_CODE, "commandId", payload.commandId())
            );
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

    private Map<String, Object> output(DataSyncAgentExecuteResponse data) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("downstreamService", "data-sync");
        output.put("commandId", data.commandId());
        output.put("syncTaskId", data.syncTaskId());
        output.put("syncExecutionId", data.syncExecutionId());
        output.put("state", data.state());
        output.put("created", data.created());
        output.put("queued", data.queued());
        output.put("duplicate", data.duplicate());
        output.put("message", data.message());
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

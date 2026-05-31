/**
 * @Author : Cui
 * @Date: 2026/05/31 23:59
 * @Description DataSmart Govern Backend - AgentRuntimeAsyncToolStatusClient.java
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

import java.util.Map;

/**
 * Agent Runtime 异步工具状态回写客户端。
 *
 * <p>该客户端是 task-management worker 与 agent-runtime 工具审计之间的唯一 HTTP 边界。
 * worker 不直接写 Agent 审计库，也不发布 Agent runtime event，而是把状态事实回调给 agent-runtime，
 * 由 agent-runtime 继续复用已有审计状态机、事件发布和 outbox 机制。</p>
 *
 * <p>可靠性策略说明：
 * RUNNING 回调失败时，worker 还没有执行业务副作用，可以直接把任务 defer 回队列；
 * SUCCEEDED/FAILED/DEFERRED 这类终态或阶段性回调失败时，也不能静默完成任务，否则 Python Runtime 和前端会永远看不到真实结果。
 * 因此调用方应把本客户端异常视为可重试问题，优先 defer 当前任务，由下一轮 worker 依赖下游幂等键进行补偿。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentRuntimeAsyncToolStatusClient {

    private final AgentAsyncToolWorkerProperties properties;
    private final RestClient.Builder restClientBuilder;

    /**
     * 通知 agent-runtime 当前异步工具任务状态。
     */
    public AgentRuntimeAsyncToolStatusResponse notifyStatus(AgentAsyncToolResolvedPayload payload,
                                                            Long taskRunId,
                                                            String status,
                                                            String message,
                                                            String errorCode,
                                                            Map<String, Object> output) {
        AgentRuntimeAsyncToolStatusRequest request = new AgentRuntimeAsyncToolStatusRequest(
                payload.commandId(),
                payload.taskId(),
                taskRunId,
                properties.getExecutorId(),
                status,
                message,
                errorCode,
                summarizeOutput(output),
                output == null ? Map.of() : output,
                "agent-async-tool:" + payload.commandId() + ":" + status
        );
        try {
            PlatformApiResponse<AgentRuntimeAsyncToolStatusResponse> response = restClientBuilder
                    .baseUrl(properties.getAgentRuntimeBaseUrl())
                    .build()
                    .post()
                    .uri("/internal/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/{auditId}/async-task-status",
                            payload.sessionId(), payload.runId(), payload.auditId())
                    .headers(headers -> applyHeaders(headers, payload.traceId()))
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return unwrap(response, payload, status);
        } catch (RestClientException exception) {
            throw new IllegalStateException("回写 agent-runtime 异步工具状态失败，status=" + status
                    + ", commandId=" + payload.commandId() + ": " + exception.getMessage(), exception);
        }
    }

    private void applyHeaders(HttpHeaders headers, String traceId) {
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "task-management");
        headers.set(PlatformContextHeaders.ACTOR_ROLE, "SERVICE_ACCOUNT");
        headers.set(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT");
        if (traceId != null && !traceId.isBlank()) {
            headers.set(PlatformContextHeaders.TRACE_ID, traceId.trim());
        }
    }

    private AgentRuntimeAsyncToolStatusResponse unwrap(PlatformApiResponse<AgentRuntimeAsyncToolStatusResponse> response,
                                                       AgentAsyncToolResolvedPayload payload,
                                                       String status) {
        if (response == null) {
            throw new IllegalStateException("agent-runtime 异步工具状态回调返回空响应，commandId=" + payload.commandId());
        }
        if (response.getCode() == null || response.getCode() != 0) {
            throw new IllegalStateException("agent-runtime 异步工具状态回调失败，status=" + status
                    + ", code=" + response.getCode() + ", reason=" + response.getReason()
                    + ", message=" + response.getMessage());
        }
        if (response.getData() == null || !response.getData().accepted()) {
            throw new IllegalStateException("agent-runtime 未接受异步工具状态回调，status=" + status
                    + ", commandId=" + payload.commandId());
        }
        return response.getData();
    }

    private String summarizeOutput(Map<String, Object> output) {
        if (output == null || output.isEmpty()) {
            return "异步工具未返回结构化输出摘要。";
        }
        return "异步工具输出摘要字段: " + String.join(",", output.keySet());
    }
}

/**
 * @Author : Cui
 * @Date: 2026/06/28 18:50
 * @Description DataSmart Govern Backend - AgentRuntimeQualityRemediationSubmitClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * agent-runtime 质量治理受控提交客户端。
 *
 * <p>该客户端只服务 `quality.remediation.task.draft` 这一条已经具备 Host 提交入口的工具。它不会把
 * task.params 中的 payloadReference 展开成正文，也不会向 data-quality 直连；task-management 通过 commandId
 * 请求 agent-runtime，由 agent-runtime 在服务端回查 outbox、approval confirmation 和 payload store 后再提交。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentRuntimeQualityRemediationSubmitClient {

    private final AgentAsyncToolWorkerProperties properties;
    private final RestClient.Builder restClientBuilder;

    /**
     * 按 commandId 触发 agent-runtime Host 侧真实提交。
     *
     * @param payload 低敏 command payload。
     * @param taskRunId task-management 当前 execution run ID。
     * @param executorId 当前 worker 身份。
     * @param actorContext 调用上下文，用于 trace 透传。
     * @return agent-runtime 返回的低敏提交摘要。
     */
    public AgentToolActionQualityRemediationSubmitResponse submit(AgentToolActionControlledTaskPayload payload,
                                                                  Long taskRunId,
                                                                  String executorId,
                                                                  TaskActorContext actorContext) {
        AgentToolActionQualityRemediationSubmitRequest request =
                new AgentToolActionQualityRemediationSubmitRequest(
                        executorId,
                        payload.taskId(),
                        taskRunId,
                        submitIdempotencyKey(payload, taskRunId)
                );
        try {
            PlatformApiResponse<AgentToolActionQualityRemediationSubmitResponse> response = restClientBuilder
                    .requestFactory(requestFactory())
                    .baseUrl(properties.getAgentRuntimeBaseUrl())
                    .build()
                    .post()
                    .uri("/internal/agent-runtime/tool-action-commands/{commandId}/quality-remediation-submit",
                            payload.commandId())
                    .headers(headers -> applyHeaders(headers, actorContext))
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return unwrap(response, payload.commandId());
        } catch (RestClientException exception) {
            throw new IllegalStateException("调用 agent-runtime 质量治理受控提交入口失败，commandId="
                    + payload.commandId() + ": " + exception.getMessage(), exception);
        }
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(Math.max(1L, properties.getControlledActionSubmitTimeoutMs()));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }

    private AgentToolActionQualityRemediationSubmitResponse unwrap(
            PlatformApiResponse<AgentToolActionQualityRemediationSubmitResponse> response,
            String commandId) {
        if (response == null) {
            throw new IllegalStateException("agent-runtime 返回空质量治理提交响应，commandId=" + commandId);
        }
        if (response.getCode() == null || response.getCode() != 0) {
            throw new IllegalStateException("agent-runtime 质量治理提交失败，commandId=" + commandId
                    + ", reason=" + response.getReason() + ", message=" + response.getMessage());
        }
        if (response.getData() == null || !response.getData().accepted()) {
            throw new IllegalStateException("agent-runtime 未接受质量治理受控提交，commandId=" + commandId);
        }
        return response.getData();
    }

    private void applyHeaders(HttpHeaders headers, TaskActorContext actorContext) {
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "task-management");
        headers.set(PlatformContextHeaders.ACTOR_ROLE, "SERVICE_ACCOUNT");
        headers.set(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT");
        if (actorContext != null && actorContext.traceId() != null && !actorContext.traceId().isBlank()) {
            headers.set(PlatformContextHeaders.TRACE_ID, actorContext.traceId().trim());
        }
    }

    private String submitIdempotencyKey(AgentToolActionControlledTaskPayload payload, Long taskRunId) {
        return "quality-remediation-submit:" + payload.commandId() + ":"
                + (payload.taskId() == null ? "no-task" : payload.taskId()) + ":"
                + (taskRunId == null ? "no-run" : taskRunId);
    }
}

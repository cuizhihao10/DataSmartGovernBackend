/**
 * @Author : Cui
 * @Date: 2026/06/28 18:50
 * @Description DataSmart Govern Backend - AgentRuntimeCommandWorkerLeaseClient.java
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
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * agent-runtime command worker lease 客户端。
 *
 * <p>task-management 的任务租约只能证明“当前 task 被哪个 task worker 认领”；它不能证明某个
 * agent-runtime command outbox 指令仍然允许该 worker 写执行回执。command worker lease 是第二层租约，
 * 由 agent-runtime 维护，用来保护 commandId 级别的旧写、重复投递和跨实例竞争。</p>
 *
 * <p>该客户端只传递低敏定位字段和 fencingToken，不读取或发送 payload body。真实工具正文始终留在
 * agent-runtime Host 或受控对象存储侧。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentRuntimeCommandWorkerLeaseClient {

    private final AgentAsyncToolWorkerProperties properties;
    private final RestClient.Builder restClientBuilder;

    /**
     * 为当前受控工具动作领取 command worker lease。
     *
     * @param payload 已解析的低敏 command payload。
     * @param executorId task-management worker 的逻辑执行器身份。
     * @param actorContext 当前调用上下文，用于 trace 透传。
     * @return 领取结果；未获得 lease 时不会携带 fencingToken。
     */
    public AgentRuntimeCommandWorkerLeaseClaimResponse claim(AgentToolActionControlledTaskPayload payload,
                                                             String executorId,
                                                             TaskActorContext actorContext) {
        AgentRuntimeCommandWorkerLeaseClaimRequest request = new AgentRuntimeCommandWorkerLeaseClaimRequest(
                payload.commandId(),
                executorId,
                payload.tenantId(),
                payload.projectId(),
                actorId(payload, actorContext),
                properties.getControlledActionCommandLeaseTtlSeconds()
        );
        try {
            PlatformApiResponse<AgentRuntimeCommandWorkerLeaseClaimResponse> response = restClientBuilder
                    .baseUrl(properties.getAgentRuntimeBaseUrl())
                    .build()
                    .post()
                    .uri("/internal/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/command-worker-leases/claims",
                            payload.sessionId(), payload.runId())
                    .headers(headers -> applyHeaders(headers, actorContext))
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return unwrapClaim(response, payload.commandId());
        } catch (RestClientException exception) {
            throw new IllegalStateException("领取 agent-runtime command worker lease 失败，commandId="
                    + payload.commandId() + ": " + exception.getMessage(), exception);
        }
    }

    /**
     * 释放当前 command worker lease。
     *
     * <p>释放失败不代表业务副作用失败，调用方通常会记录低敏告警后继续返回本轮结果。下一轮 worker
     * 最多等待 TTL 到期后重新领取，不会因此拿到 payload body。</p>
     */
    public AgentRuntimeCommandWorkerLeaseMutationResponse release(AgentToolActionControlledTaskPayload payload,
                                                                  String executorId,
                                                                  AgentRuntimeCommandWorkerLeaseClaimResponse lease,
                                                                  String releaseReason,
                                                                  TaskActorContext actorContext) {
        AgentRuntimeCommandWorkerLeaseReleaseRequest request = new AgentRuntimeCommandWorkerLeaseReleaseRequest(
                payload.commandId(),
                executorId,
                lease == null ? null : lease.fencingToken(),
                lease == null ? null : lease.workerLeaseVersion(),
                releaseReason,
                payload.tenantId(),
                payload.projectId(),
                actorId(payload, actorContext)
        );
        try {
            PlatformApiResponse<AgentRuntimeCommandWorkerLeaseMutationResponse> response = restClientBuilder
                    .baseUrl(properties.getAgentRuntimeBaseUrl())
                    .build()
                    .post()
                    .uri("/internal/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/command-worker-leases/releases",
                            payload.sessionId(), payload.runId())
                    .headers(headers -> applyHeaders(headers, actorContext))
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return unwrapMutation(response, payload.commandId());
        } catch (RestClientException exception) {
            throw new IllegalStateException("释放 agent-runtime command worker lease 失败，commandId="
                    + payload.commandId() + ": " + exception.getMessage(), exception);
        }
    }

    private AgentRuntimeCommandWorkerLeaseClaimResponse unwrapClaim(
            PlatformApiResponse<AgentRuntimeCommandWorkerLeaseClaimResponse> response,
            String commandId) {
        if (response == null || response.getCode() == null || response.getCode() != 0 || response.getData() == null) {
            throw new IllegalStateException("agent-runtime command worker lease claim 响应无效，commandId=" + commandId);
        }
        return response.getData();
    }

    private AgentRuntimeCommandWorkerLeaseMutationResponse unwrapMutation(
            PlatformApiResponse<AgentRuntimeCommandWorkerLeaseMutationResponse> response,
            String commandId) {
        if (response == null || response.getCode() == null || response.getCode() != 0 || response.getData() == null) {
            throw new IllegalStateException("agent-runtime command worker lease release 响应无效，commandId=" + commandId);
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

    private Long actorId(AgentToolActionControlledTaskPayload payload, TaskActorContext actorContext) {
        if (payload.actorId() != null && !payload.actorId().isBlank()) {
            try {
                return Long.parseLong(payload.actorId().trim());
            } catch (NumberFormatException ignored) {
                return actorContext == null ? null : actorContext.actorId();
            }
        }
        return actorContext == null ? null : actorContext.actorId();
    }
}

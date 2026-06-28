/**
 * @Author : Cui
 * @Date: 2026/06/28 18:50
 * @Description DataSmart Govern Backend - AgentRuntimeToolActionCommandWorkerReceiptClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * agent-runtime command worker receipt 回写客户端。
 *
 * <p>该客户端与旧 dry-run receipt 客户端分开：dry-run receipt 永远不能声明真实副作用，command worker receipt
 * 则用于记录 Host 提交、沙箱命令、制品输出等受控执行结果。分开后，timeline、恢复事实包和审计台可以准确解释
 * “这是执行前治理”还是“真实 worker 回执”。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRuntimeToolActionCommandWorkerReceiptClient {

    private final AgentAsyncToolWorkerProperties properties;
    private final RestClient.Builder restClientBuilder;

    /**
     * 发布 command worker receipt。
     *
     * <p>如果 agent-runtime 暂时不可用，当前阶段复用 receipt fail-open 配置返回投递失败摘要；
     * 调用方会根据是否已经产生真实副作用决定 complete、fail 还是 defer 当前 task。</p>
     */
    public AgentToolActionControlledReceiptDelivery publish(AgentToolActionControlledTaskPayload payload,
                                                            AgentToolActionCommandWorkerReceiptRequest request,
                                                            TaskActorContext actorContext) {
        if (!properties.isControlledActionReceiptEnabled()) {
            return AgentToolActionControlledReceiptDelivery.skipped("受控工具动作 command worker receipt 回写配置已关闭");
        }
        try {
            PlatformApiResponse<AgentToolActionCommandWorkerReceiptResponse> response = restClientBuilder
                    .baseUrl(properties.getAgentRuntimeBaseUrl())
                    .build()
                    .post()
                    .uri("/internal/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/command-worker-receipts",
                            payload.sessionId(), payload.runId())
                    .headers(headers -> applyHeaders(headers, actorContext))
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return unwrap(response);
        } catch (RestClientException exception) {
            return handleFailure("回写 agent-runtime command worker receipt 失败: " + exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            return handleFailure("处理 agent-runtime command worker receipt 响应失败: " + exception.getMessage(), exception);
        }
    }

    private AgentToolActionControlledReceiptDelivery unwrap(
            PlatformApiResponse<AgentToolActionCommandWorkerReceiptResponse> response) {
        if (response == null || response.getCode() == null || response.getCode() != 0 || response.getData() == null) {
            throw new IllegalStateException("agent-runtime command worker receipt 响应无效");
        }
        AgentToolActionCommandWorkerReceiptResponse data = response.getData();
        if (!data.accepted()) {
            throw new IllegalStateException("agent-runtime 未接受 command worker receipt");
        }
        return new AgentToolActionControlledReceiptDelivery(
                true,
                true,
                data.duplicate(),
                data.identityKey(),
                data.eventType(),
                data.message()
        );
    }

    private void applyHeaders(HttpHeaders headers, TaskActorContext actorContext) {
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "task-management");
        headers.set(PlatformContextHeaders.ACTOR_ROLE, "SERVICE_ACCOUNT");
        headers.set(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT");
        if (actorContext != null && actorContext.traceId() != null && !actorContext.traceId().isBlank()) {
            headers.set(PlatformContextHeaders.TRACE_ID, actorContext.traceId().trim());
        }
    }

    private AgentToolActionControlledReceiptDelivery handleFailure(String message, RuntimeException exception) {
        if (!properties.isControlledActionReceiptFailOpenOnError()) {
            throw exception;
        }
        log.warn("{}，当前配置为 fail-open，task-management 会根据副作用状态决定是否 defer 补偿。", message);
        return AgentToolActionControlledReceiptDelivery.failedOpen(message);
    }
}

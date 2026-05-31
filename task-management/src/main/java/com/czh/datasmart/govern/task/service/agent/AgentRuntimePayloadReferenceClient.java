/**
 * @Author : Cui
 * @Date: 2026/05/31 18:16
 * @Description DataSmart Govern Backend - AgentRuntimePayloadReferenceClient.java
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

/**
 * Agent Runtime payloadReference HTTP 客户端。
 *
 * <p>该客户端只负责“按受控引用读取参数快照”，不负责执行工具，也不负责解释业务参数。
 * 这样可以让网络调用、PlatformApiResponse 解包、Header 透传和异常翻译集中在一处，
 * 避免未来多个 worker/adapter 重复拼 URL、重复解析响应。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentRuntimePayloadReferenceClient {

    private final AgentAsyncToolWorkerProperties properties;
    private final RestClient.Builder restClientBuilder;

    /**
     * 读取 plan-arguments 载荷。
     *
     * @param reference 已解析并校验过的 Agent 工具审计引用。
     * @param traceId 当前链路追踪 ID，用于串起 task-management worker 与 agent-runtime 内部读取日志。
     * @return Agent Runtime 返回的参数快照。
     */
    public AgentRuntimePlanArgumentsPayloadResponse fetchPlanArguments(AgentToolAuditPayloadReference reference,
                                                                       String traceId) {
        try {
            PlatformApiResponse<AgentRuntimePlanArgumentsPayloadResponse> response = restClientBuilder
                    .baseUrl(properties.getAgentRuntimeBaseUrl())
                    .build()
                    .get()
                    .uri("/internal/agent-runtime/sessions/{sessionId}/runs/{runId}/tool-executions/{auditId}/plan-arguments",
                            reference.sessionId(), reference.runId(), reference.auditId())
                    .headers(headers -> applyHeaders(headers, traceId))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return unwrap(response, reference);
        } catch (RestClientException exception) {
            throw new IllegalStateException("调用 agent-runtime 参数载荷解析接口失败: " + exception.getMessage(), exception);
        }
    }

    /**
     * 透传服务间调用 Header。
     *
     * <p>当前还没有完整服务账号签名体系，因此先显式标记 SOURCE_SERVICE、ACTOR_ROLE 和 ACTOR_TYPE。
     * 后续 gateway 或服务网格可以基于这些 Header 与 mTLS 身份、签名令牌一起做内部接口访问控制。</p>
     */
    private void applyHeaders(HttpHeaders headers, String traceId) {
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "task-management");
        headers.set(PlatformContextHeaders.ACTOR_ROLE, "SERVICE_ACCOUNT");
        headers.set(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT");
        if (traceId != null && !traceId.isBlank()) {
            headers.set(PlatformContextHeaders.TRACE_ID, traceId.trim());
        }
    }

    private AgentRuntimePlanArgumentsPayloadResponse unwrap(
            PlatformApiResponse<AgentRuntimePlanArgumentsPayloadResponse> response,
            AgentToolAuditPayloadReference reference) {
        if (response == null) {
            throw new IllegalStateException("agent-runtime 返回空响应，payloadReference=" + reference.toCanonicalString());
        }
        if (response.getCode() == null || response.getCode() != 0) {
            throw new IllegalStateException("agent-runtime 参数载荷解析失败，code=" + response.getCode()
                    + ", reason=" + response.getReason() + ", message=" + response.getMessage());
        }
        if (response.getData() == null) {
            throw new IllegalStateException("agent-runtime 参数载荷解析成功但 data 为空，payloadReference="
                    + reference.toCanonicalString());
        }
        return response.getData();
    }
}

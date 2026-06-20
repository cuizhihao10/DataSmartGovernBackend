/**
 * @Author : Cui
 * @Date: 2026/06/20 21:43
 * @Description DataSmart Govern Backend - HttpDataSyncAgentExecuteClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.service.agent.DataSyncAgentExecuteRequest;
import com.czh.datasmart.govern.task.service.agent.DataSyncAgentExecuteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 基于 Spring RestClient 的 DataSync Agent 内部执行客户端。
 *
 * <p>该类只做“如何把低敏 DTO 发送给 datasource-management”的技术适配，不参与 outbox 状态流转。
 * 这样可以避免一个类同时负责 HTTP 调用、幂等账本、重试策略和 Agent 工具结果转换，降低耦合度。</p>
 *
 * <p>当前实现调用固定内部路径 {@code /internal/data-sync/agent/tasks/execute}。
 * 注意：该内部路径不会写入普通响应、runtime event 或诊断视图；真正对外可见的只有低敏 commandId、syncTaskId、
 * syncExecutionId、状态和消息摘要。生产环境还应在 gateway、服务网格或 mTLS 层增加服务账号签名，当前 Header
 * 校验只是本地闭环阶段的最小可用保护。</p>
 */
@Component
@RequiredArgsConstructor
public class HttpDataSyncAgentExecuteClient implements DataSyncAgentExecuteClient {

    /**
     * RestClient 构建器由 Spring 注入。
     *
     * <p>这里保留 Builder 而不是直接保存 RestClient，是为了让 baseUrl 可以继续由
     * {@link AgentAsyncToolWorkerProperties#getDataSyncBaseUrl()} 配置覆盖。
     * 后续接入服务发现或负载均衡时，可以在这里集中替换构建方式。</p>
     */
    private final RestClient.Builder restClientBuilder;

    /**
     * Agent worker 配置，当前主要使用 dataSyncBaseUrl。
     */
    private final AgentAsyncToolWorkerProperties properties;

    @Override
    public DataSyncAgentExecuteResponse execute(DataSyncAgentExecuteRequest request) {
        PlatformApiResponse<DataSyncAgentExecuteResponse> response = restClientBuilder
                .baseUrl(properties.getDataSyncBaseUrl())
                .build()
                .post()
                .uri("/internal/data-sync/agent/tasks/execute")
                .headers(headers -> applyHeaders(headers, request))
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return unwrap(response);
    }

    /**
     * 写入服务间调用 Header。
     *
     * <p>这些 Header 表示本次调用来自 task-management 服务账号，而不是普通终端用户直接访问。
     * datasource-management 会用它们做最小的内部入口校验。需要注意，Header 本身不是强身份凭证；
     * 商业化部署时应继续叠加 mTLS、网关签名、服务网格身份或零信任策略。</p>
     */
    private void applyHeaders(HttpHeaders headers, DataSyncAgentExecuteRequest request) {
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "task-management");
        headers.set(PlatformContextHeaders.ACTOR_ROLE, "SERVICE_ACCOUNT");
        headers.set(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT");
        if (request.getTraceId() != null && !request.getTraceId().isBlank()) {
            headers.set(PlatformContextHeaders.TRACE_ID, request.getTraceId());
        }
        if (request.getTenantId() != null) {
            headers.set(PlatformContextHeaders.TENANT_ID, String.valueOf(request.getTenantId()));
        }
    }

    /**
     * 解包统一响应 envelope。
     *
     * <p>这里不把 datasource-management 的内部 endpoint、HTTP 响应正文或堆栈信息拼进异常。
     * 如果下游返回非成功 code，只暴露 code/reason/message 的低敏摘要，避免把内部地址、SQL 或配置细节带回
     * Agent 工具输出与 outbox 诊断链路。</p>
     */
    private DataSyncAgentExecuteResponse unwrap(PlatformApiResponse<DataSyncAgentExecuteResponse> response) {
        if (response == null) {
            throw new IllegalStateException("data-sync 返回空响应");
        }
        if (response.getCode() == null || response.getCode() != 0) {
            throw new IllegalStateException("data-sync Agent 执行被下游拒绝，code="
                    + response.getCode() + ", reason=" + response.getReason()
                    + ", message=" + response.getMessage());
        }
        if (response.getData() == null) {
            throw new IllegalStateException("data-sync Agent 执行响应 data 为空");
        }
        return response.getData();
    }
}

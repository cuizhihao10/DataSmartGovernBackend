/**
 * @Author : Cui
 * @Date: 2026/06/06 23:09
 * @Description DataSmart Govern Backend - HttpGatewayAgentToolPolicyEnvelopeClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.agent;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.gateway.config.GatewayContextProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 基于 HTTP 的 Agent 工具治理策略客户端。
 *
 * <p>它调用 permission-admin 的 `/permissions/agent/tool-budget-policies/evaluate`，读取统一
 * `PlatformApiResponse<AgentToolBudgetPolicyView>`。这里不把 permission-admin 的 DTO 作为编译依赖，
 * 而是使用 gateway 自己的契约视图承接 JSON 字段，保持微服务边界清晰。</p>
 *
 * <p>为什么这个调用应该发生在 gateway/agent-runtime，而不是长期留在 Python Runtime：
 * 1. gateway 已经掌握认证、角色、租户、workspace、数据范围和签名边界；
 * 2. permission-admin 是 Java 控制面，更适合统一角色/套餐/容量/风险策略；
 * 3. Python Runtime 应消费“已经评估好的低敏策略”，专注 Agent 编排，而不是反复同步调用权限中心。</p>
 */
@Component
@RequiredArgsConstructor
public class HttpGatewayAgentToolPolicyEnvelopeClient implements GatewayAgentToolPolicyEnvelopeClient {

    /**
     * gateway 上下文配置，其中包含 permission-admin 工具策略评估地址和超时时间。
     */
    private final GatewayContextProperties contextProperties;

    /**
     * Reactive HTTP 客户端构造器。
     *
     * <p>gateway 是 WebFlux/Reactor 链路，不能在过滤器里使用阻塞式 RestTemplate。
     * WebClient 可以在 permission-admin 短暂变慢时保持网关线程不被阻塞。</p>
     */
    private final WebClient.Builder webClientBuilder;

    @Override
    public Mono<GatewayAgentToolPolicyEnvelopeView> evaluate(
            GatewayAgentToolPolicyEnvelopeRequest request,
            String traceId) {
        GatewayContextProperties.ToolPolicyEnvelope properties = contextProperties.getToolPolicyEnvelope();
        return webClientBuilder.build()
                .post()
                .uri(properties.getPermissionAdminEvaluateUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .header(PlatformContextHeaders.TRACE_ID, traceId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(GatewayAgentToolPolicyEnvelopeResponse.class)
                .timeout(properties.getTimeout())
                .flatMap(this::unwrap);
    }

    /**
     * 解包 permission-admin 的统一响应。
     *
     * <p>如果 code 非 0 或 data 为空，说明策略中心没有给出可用工具治理事实。
     * 这里不做 fail-open/fail-closed 决策，只抛出异常交给过滤器按配置处理。</p>
     */
    private Mono<GatewayAgentToolPolicyEnvelopeView> unwrap(GatewayAgentToolPolicyEnvelopeResponse response) {
        if (response == null) {
            return Mono.error(new IllegalStateException("permission-admin 工具策略响应为空"));
        }
        if (response.getCode() == null || response.getCode() != 0) {
            return Mono.error(new IllegalStateException("permission-admin 工具策略响应失败：" + response.getMessage()));
        }
        if (response.getData() == null) {
            return Mono.error(new IllegalStateException("permission-admin 工具策略响应缺少 data"));
        }
        return Mono.just(response.getData());
    }

    /**
     * permission-admin 统一响应 envelope。
     *
     * <p>这里只声明 gateway 关心的 code/message/data。reason、timestamp 等字段即使存在，也不参与工具策略注入。</p>
     */
    @Data
    private static class GatewayAgentToolPolicyEnvelopeResponse {
        private Integer code;
        private String message;
        private GatewayAgentToolPolicyEnvelopeView data;
    }
}

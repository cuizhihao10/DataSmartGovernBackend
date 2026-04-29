/**
 * @Author : Cui
 * @Date: 2026/04/25 23:22
 * @Description DataSmart Govern Backend - PermissionAdminDecisionClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.authorization;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.gateway.config.GatewayAuthorizationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * permission-admin 权限判定客户端。
 *
 * <p>这是 gateway 调用权限中心的唯一 HTTP 出口。
 * 单独封装客户端有两个好处：
 * 1. GatewayAuthorizationFilter 不需要关心 WebClient 细节，只关注过滤器流程；
 * 2. 后续如果从 HTTP 调用切换为服务发现、缓存、本地策略快照或熔断组件，只需要改这个客户端。
 */
@Component
@RequiredArgsConstructor
public class PermissionAdminDecisionClient {

    /**
     * 网关授权配置，包含权限中心地址、超时时间和失败策略。
     */
    private final GatewayAuthorizationProperties authorizationProperties;

    /**
     * WebClient Builder 由 Spring Boot WebFlux 自动提供。
     *
     * <p>gateway 基于 Reactive 技术栈，不能在过滤器中使用阻塞式 RestTemplate。
     * WebClient 能保持非阻塞调用，避免少量慢权限请求拖垮网关线程。
     */
    private final WebClient.Builder webClientBuilder;

    /**
     * 调用 permission-admin 的权限判定接口。
     *
     * @param request 判定请求，包含角色、租户、HTTP 方法、路径和业务资源语义。
     * @param traceId 当前链路 ID，会透传给 permission-admin 用于日志和审计关联。
     * @return 权限判定结果。
     */
    public Mono<GatewayPermissionDecisionResult> evaluate(GatewayPermissionDecisionRequest request, String traceId) {
        return webClientBuilder.build()
                .post()
                .uri(authorizationProperties.getDecisionEndpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .header(PlatformContextHeaders.TRACE_ID, traceId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(GatewayPermissionDecisionEnvelope.class)
                .timeout(authorizationProperties.getTimeout())
                .flatMap(this::unwrapDecision);
    }

    /**
     * 解包 permission-admin 的统一响应。
     *
     * <p>permission-admin 使用 PlatformApiResponse，因此真正的权限结果在 data 字段中。
     * 如果 code 非 0 或 data 为空，说明权限中心没有给出可用判定，gateway 应该进入异常处理分支。
     */
    private Mono<GatewayPermissionDecisionResult> unwrapDecision(GatewayPermissionDecisionEnvelope envelope) {
        if (envelope == null) {
            return Mono.error(new IllegalStateException("权限中心返回为空"));
        }
        if (envelope.getCode() == null || envelope.getCode() != 0) {
            return Mono.error(new IllegalStateException("权限中心返回失败：" + envelope.getMessage()));
        }
        if (envelope.getData() == null) {
            return Mono.error(new IllegalStateException("权限中心未返回判定结果"));
        }
        return Mono.just(envelope.getData());
    }
}

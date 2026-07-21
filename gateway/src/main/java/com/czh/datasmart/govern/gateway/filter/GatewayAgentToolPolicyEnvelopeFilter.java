/**
 * @Author : Cui
 * @Date: 2026/06/06 23:10
 * @Description DataSmart Govern Backend - GatewayAgentToolPolicyEnvelopeFilter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.filter;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.gateway.agent.GatewayAgentToolPolicyEnvelopeClient;
import com.czh.datasmart.govern.gateway.agent.GatewayAgentToolPolicyEnvelopeFactory;
import com.czh.datasmart.govern.gateway.agent.GatewayAgentToolPolicyEnvelopeRequest;
import com.czh.datasmart.govern.gateway.agent.GatewayAgentToolPolicyEnvelopeView;
import com.czh.datasmart.govern.gateway.authorization.GatewayAuthorizationErrorWriter;
import com.czh.datasmart.govern.gateway.config.GatewayContextProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Agent 规划入口工具治理策略信封过滤器。
 *
 * <p>该过滤器把 5.41 已经建立好的 `X-DataSmart-Tool-Policy-Envelope` 契约放进真实 gateway 链路：
 * 1. 清理调用方伪造的旧 envelope Header；
 * 2. 从已认证/授权/缓存上下文 Header 中构造低敏策略评估请求；
 * 3. 按配置调用 permission-admin 或使用 gateway 本地保守 fallback；
 * 4. 将 `toolCallBudget + toolExecutionReadinessPolicy` 裁剪成小 JSON Header；
 * 5. 交给后续 `GatewayPythonRuntimeSignatureFilter` 统一签名。</p>
 *
 * <p>执行顺序为什么是 -84：
 * - `GatewaySkillVisibilityCacheContextFilter(-85)` 先写入 tenantPlan、workspaceRisk、policyVersion 等上下文；
 * - 本过滤器读取这些上下文并写入 tool policy envelope；
 * - `GatewayPythonRuntimeSignatureFilter(-80)` 最后对包括 envelope 在内的 Header 快照签名。</p>
 *
 * <p>安全边界：
 * - 不读取 request body，避免 gateway 缓存 prompt、SQL、工具参数或模型上下文；
 * - 不信任外部传入的 envelope，命中目标路径时始终先清理；
 * - 远程 permission-admin 失败时是否 fallback 由配置控制；
 * - envelope 过大或 permission-admin 明确 allowed=false 时 fail-closed。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayAgentToolPolicyEnvelopeFilter implements GlobalFilter, Ordered {

    private final GatewayContextProperties contextProperties;
    private final GatewayAgentToolPolicyEnvelopeClient policyEnvelopeClient;
    private final GatewayAgentToolPolicyEnvelopeFactory policyEnvelopeFactory;
    private final GatewayAuthorizationErrorWriter authorizationErrorWriter;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        GatewayContextProperties.ToolPolicyEnvelope properties = contextProperties.getToolPolicyEnvelope();
        if (!isTargetPath(request, properties)) {
            return chain.filter(exchange);
        }

        ServerHttpRequest sanitizedRequest = request.mutate()
                .headers(headers -> headers.remove(PlatformContextHeaders.TOOL_POLICY_ENVELOPE))
                .build();
        ServerWebExchange sanitizedExchange = exchange.mutate().request(sanitizedRequest).build();
        if (!properties.isEnabled()) {
            return chain.filter(sanitizedExchange);
        }

        GatewayAgentToolPolicyEnvelopeRequest policyRequest = policyEnvelopeFactory.requestFromHeaders(
                sanitizedRequest.getHeaders(),
                properties
        );
        String traceId = sanitizedRequest.getHeaders().getFirst(PlatformContextHeaders.TRACE_ID);
        /*
         * 只把“策略评估/信封序列化”异常转换为策略中心 503。onErrorMap 放在 flatMap 前面非常重要：
         * chain.filter(...) 代表请求已经进入 Python Runtime 转发阶段，它的连接拒绝、超时或 5xx 必须保持原始
         * 下游语义，不能再被误包装成 403 权限问题。
         */
        return evaluatePolicy(policyRequest, properties, traceId)
                .onErrorMap(error -> new ToolPolicyEnvelopeException(error))
                .flatMap(view -> continueWithEnvelope(sanitizedExchange, chain, policyRequest, view, properties, traceId))
                .onErrorResume(ToolPolicyEnvelopeException.class, error -> {
                    log.error("Agent 工具策略 envelope 生成失败，已按 fail-closed 拒绝请求，traceId={}, path={}",
                            traceId, sanitizedRequest.getPath().value(), error);
                    return authorizationErrorWriter.writeServiceUnavailable(
                            sanitizedExchange.getResponse(),
                            traceId,
                            "Agent 工具策略中心暂时不可用，网关已拒绝本次规划请求"
                    );
                });
    }

    /**
     * 根据配置评估工具策略。
     *
     * <p>远程评估关闭时直接使用本地 fallback；远程开启但失败时，根据 failOpenOnRemoteError 决定：
     * - true：记录告警并使用本地保守 fallback，保证本地/灰度环境可用；
     * - false：把异常继续抛出，由网关返回失败，避免生产环境策略中心不可用时继续放行。</p>
     */
    private Mono<GatewayAgentToolPolicyEnvelopeView> evaluatePolicy(
            GatewayAgentToolPolicyEnvelopeRequest request,
            GatewayContextProperties.ToolPolicyEnvelope properties,
            String traceId) {
        if (!properties.isRemoteEvaluationEnabled()) {
            return Mono.just(policyEnvelopeFactory.localFallback(request, properties));
        }
        return policyEnvelopeClient.evaluate(request, traceId)
                .onErrorResume(error -> {
                    if (!properties.isFailOpenOnRemoteError()) {
                        return Mono.error(error);
                    }
                    log.warn("permission-admin 工具策略评估失败，已按配置回退 gateway 本地保守 envelope，traceId={}, error={}",
                            traceId, error.getMessage());
                    return Mono.just(policyEnvelopeFactory.localFallback(request, properties));
                });
    }

    /**
     * 将策略结果写入 Header 并继续转发。
     */
    private Mono<Void> continueWithEnvelope(
            ServerWebExchange exchange,
            GatewayFilterChain chain,
            GatewayAgentToolPolicyEnvelopeRequest request,
            GatewayAgentToolPolicyEnvelopeView view,
            GatewayContextProperties.ToolPolicyEnvelope properties,
            String traceId) {
        if (!Boolean.TRUE.equals(view.getAllowed())) {
            log.warn("Agent 工具策略中心拒绝继续规划，traceId={}, actorRole={}, tenantPlan={}, workspaceRisk={}",
                    traceId, request.getActorRole(), request.getTenantPlanCode(), request.getWorkspaceRiskLevel());
            return authorizationErrorWriter.writeForbidden(
                    exchange.getResponse(),
                    traceId,
                    "Agent 工具策略中心拒绝本次规划请求"
            );
        }

        String envelope;
        try {
            envelope = policyEnvelopeFactory.envelopeJson(view, request, Math.max(1, properties.getMaxHeaderBytes()));
        } catch (RuntimeException error) {
            return Mono.error(new ToolPolicyEnvelopeException(error));
        }
        ServerHttpRequest enrichedRequest = exchange.getRequest().mutate()
                .headers(headers -> headers.set(PlatformContextHeaders.TOOL_POLICY_ENVELOPE, envelope))
                .build();
        return chain.filter(exchange.mutate().request(enrichedRequest).build());
    }

    private boolean isTargetPath(ServerHttpRequest request,
                                 GatewayContextProperties.ToolPolicyEnvelope properties) {
        return properties != null
                && properties.getTargetPaths() != null
                && properties.getTargetPaths().contains(request.getPath().value());
    }

    @Override
    public int getOrder() {
        return -84;
    }

    /**
     * 仅标记工具策略评估或 envelope 构造失败，避免捕获后续 Python Runtime 转发异常。
     */
    private static final class ToolPolicyEnvelopeException extends RuntimeException {

        private ToolPolicyEnvelopeException(Throwable cause) {
            super(cause);
        }
    }
}

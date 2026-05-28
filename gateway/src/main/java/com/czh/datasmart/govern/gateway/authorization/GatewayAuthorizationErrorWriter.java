/**
 * @Author : Cui
 * @Date: 2026/05/24 02:39
 * @Description DataSmart Govern Backend - GatewayAuthorizationErrorWriter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.authorization;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关授权错误响应写出器。
 *
 * <p>授权过滤器的核心职责应该是“编排授权流程”：读取上下文、调用 permission-admin、处理缓存和决定放行或拒绝。
 * 如果把 JSON 序列化、错误码选择、Retry-After Header 写入等响应细节继续放在过滤器里，
 * 过滤器会快速膨胀成难以维护的大类，也不利于后续新增更多网关保护策略。
 *
 * <p>因此本类专门承担网关授权链路的错误响应输出：
 * 1. 普通权限拒绝返回平台统一 403；
 * 2. 内部服务端点角色或 token 拒绝返回 403；
 * 3. 内部服务端点限流返回 429，并补充 Retry-After，方便调用方做退避重试。
 */
@Component
@RequiredArgsConstructor
public class GatewayAuthorizationErrorWriter {

    /**
     * Jackson 序列化器。
     *
     * <p>网关是响应链路的最后一道门，如果序列化失败，仍然要返回一个最小 JSON，
     * 避免客户端收到空响应而无法定位 traceId 或错误类型。
     */
    private final ObjectMapper objectMapper;

    /**
     * 写出权限拒绝响应。
     *
     * @param response 当前网关响应对象。
     * @param traceId 本次请求追踪 ID，用于前端、日志和审计排障关联。
     * @param message 面向调用方的拒绝原因。
     */
    public Mono<Void> writeForbidden(ServerHttpResponse response, String traceId, String message) {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return writeError(response, traceId, PlatformErrorCode.FORBIDDEN, message);
    }

    /**
     * 写出内部服务端点保护拒绝响应。
     *
     * <p>内部服务端点保护发生在 permission-admin 远程判定之前。
     * 如果是角色或 token 不符合要求，返回 403；如果是本地限流，返回 429 并写入 Retry-After。
     * 这样调用方可以区分“身份不对”和“调用太频繁”，不会盲目重试 403。
     */
    public Mono<Void> writeGuardDenied(ServerHttpResponse response,
                                       String traceId,
                                       GatewayInternalServiceEndpointGuard.GuardDecision decision) {
        response.setStatusCode(decision.status());
        if (decision.status() == HttpStatus.TOO_MANY_REQUESTS && decision.retryAfterSeconds() > 0) {
            response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        }
        PlatformErrorCode errorCode = decision.status() == HttpStatus.TOO_MANY_REQUESTS
                ? PlatformErrorCode.RATE_LIMITED
                : PlatformErrorCode.FORBIDDEN;
        return writeError(response, traceId, errorCode, decision.reason());
    }

    /**
     * 写出网关层统一错误响应。
     *
     * <p>权限拒绝、内部端点保护和限流都应该使用平台统一响应体。
     * 这样前端、Python Runtime 或其他服务账号都可以按 `code/reason/traceId` 做统一处理。
     */
    private Mono<Void> writeError(ServerHttpResponse response,
                                  String traceId,
                                  PlatformErrorCode errorCode,
                                  String message) {
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        PlatformApiResponse<Void> body = PlatformApiResponse.error(errorCode, message, traceId);
        byte[] bytes = serialize(body);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 序列化响应体。
     */
    private byte[] serialize(PlatformApiResponse<Void> body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            return "{\"code\":20002,\"reason\":\"FORBIDDEN\",\"message\":\"forbidden\"}"
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}

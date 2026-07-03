/**
 * @Author : Cui
 * @Date: 2026/07/03 16:46
 * @Description DataSmart Govern Backend - HttpAgentMcpDurableWorkerClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime.mcp;

import com.czh.datasmart.govern.agent.config.AgentMcpDurableWorkerClientProperties;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Duration;

/**
 * 基于 Spring RestClient 的 Python MCP Durable Worker HTTP 客户端。
 *
 * <p>这是 Java agent-runtime 与 Python Runtime MCP 执行面的第一条真实桥接。它刻意保持“薄而明确”：不解析 MCP 工具语义、
 * 不做 permission-admin 授权、不写 receipt、不更新 outbox，只负责把已经由 Java 控制面确认过的请求投递给 Python 内部 API，
 * 并把 Python 的低敏响应包装成 {@link AgentMcpDurableWorkerCallResult}。这种分层可以减少后续接入 dispatcher 时的耦合。</p>
 *
 * <p>为什么暂时使用 HTTP 而不是直接 Kafka：</p>
 * <p>1. Python 侧刚完成内部 API 合同，HTTP 更适合作为第一条可测联调路径；</p>
 * <p>2. outbox dispatcher 仍能提供重试、失败回写和死信语义，不会退化成业务线程同步调用；</p>
 * <p>3. 等 Kafka worker topic、幂等消费表和死信运维完全落地后，可以新增 Kafka 实现并保留本接口不变。</p>
 *
 * <p>低敏策略：异常消息、URL、请求参数、服务账户 token、Python 原始响应正文都不会进入返回对象。后续如果要做日志，
 * 也应该只记录 {@code errorCode/statusCode/traceId/runId/callId} 等低基数字段。</p>
 */
@Component
@RequiredArgsConstructor
public class HttpAgentMcpDurableWorkerClient implements AgentMcpDurableWorkerClient {

    private static final String ERROR_DISABLED = "MCP_DURABLE_WORKER_CLIENT_DISABLED";
    private static final String ERROR_REQUEST_MISSING = "MCP_DURABLE_WORKER_REQUEST_MISSING";
    private static final String ERROR_ENDPOINT_INVALID = "MCP_DURABLE_WORKER_ENDPOINT_INVALID";
    private static final String ERROR_HTTP_STATUS = "MCP_DURABLE_WORKER_HTTP_STATUS_NOT_SUCCESSFUL";
    private static final String ERROR_RESPONSE_MISSING = "MCP_DURABLE_WORKER_RESPONSE_MISSING";
    private static final String ERROR_CLIENT_EXCEPTION = "MCP_DURABLE_WORKER_CLIENT_EXCEPTION";

    private final AgentMcpDurableWorkerClientProperties properties;
    private final RestClient.Builder restClientBuilder;

    /**
     * 调用 Python MCP Durable Worker。
     *
     * <p>该方法是后续 dispatcher 的稳定入口。它会先做轻量配置校验，再把请求级默认值补齐，最后发起 HTTP POST。
     * 所有失败都会转换为低敏 {@link AgentMcpDurableWorkerCallResult}，避免把 RestClient 异常中的内部地址、响应片段或 header
     * 暴露给上层 runtime event。</p>
     */
    @Override
    public AgentMcpDurableWorkerCallResult run(AgentMcpDurableWorkerRunRequest request) {
        if (!properties.isEnabled()) {
            return AgentMcpDurableWorkerCallResult.skipped(
                    ERROR_DISABLED,
                    "MCP Durable Worker 客户端未启用，本次不调用 Python Runtime。"
            );
        }
        if (request == null) {
            return AgentMcpDurableWorkerCallResult.skipped(
                    ERROR_REQUEST_MISSING,
                    "MCP Durable Worker 请求为空，已跳过投递。"
            );
        }
        URI endpoint = endpointUri();
        if (endpoint == null) {
            return AgentMcpDurableWorkerCallResult.skipped(
                    ERROR_ENDPOINT_INVALID,
                    "MCP Durable Worker endpoint 配置无效，已跳过投递。"
            );
        }

        AgentMcpDurableWorkerRunRequest normalizedRequest = request.withClientDefaults(
                properties.isPostToJava(),
                properties.isIncludeModelFeedback()
        );
        try {
            ResponseEntity<AgentMcpDurableWorkerRunResponse> response = restClientBuilder
                    .requestFactory(requestFactory())
                    .build()
                    .post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> applyHeaders(headers, normalizedRequest))
                    .body(normalizedRequest)
                    .retrieve()
                    .toEntity(AgentMcpDurableWorkerRunResponse.class);
            AgentMcpDurableWorkerRunResponse body = response.getBody();
            if (body == null) {
                return AgentMcpDurableWorkerCallResult.failed(
                        response.getStatusCode().value(),
                        ERROR_RESPONSE_MISSING,
                        "Python MCP Durable Worker 返回空响应体，等待 dispatcher 后续重试或人工排查。"
                );
            }
            return AgentMcpDurableWorkerCallResult.accepted(
                    response.getStatusCode().value(),
                    body,
                    Boolean.TRUE.equals(body.accepted())
                            ? "Python MCP Durable Worker 已接受并处理请求。"
                            : "Python MCP Durable Worker 返回未接受状态，详情请查看低敏 workerResult/receipt 摘要。"
            );
        } catch (RestClientResponseException exception) {
            return AgentMcpDurableWorkerCallResult.failed(
                    exception.getStatusCode().value(),
                    ERROR_HTTP_STATUS,
                    "Python MCP Durable Worker 返回非成功 HTTP 状态，响应正文已隐藏。"
            );
        } catch (RestClientException exception) {
            return AgentMcpDurableWorkerCallResult.failed(
                    0,
                    ERROR_CLIENT_EXCEPTION,
                    "调用 Python MCP Durable Worker 失败，底层地址、Header、参数和响应片段已隐藏。"
            );
        }
    }

    /**
     * 构造内部 API endpoint。
     *
     * <p>这里不把无效 URI 异常抛给上层，是因为 dispatcher 更需要一个可分类的低基数错误码，而不是带内部地址的异常消息。
     * {@code baseUrl} 与 {@code runPath} 的真实值只存在配置和本地变量中，不进入返回结果。</p>
     */
    private URI endpointUri() {
        String baseUrl = trimToNull(properties.getBaseUrl());
        String runPath = trimToNull(properties.getRunPath());
        if (baseUrl == null || runPath == null) {
            return null;
        }
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = runPath.startsWith("/") ? runPath : "/" + runPath;
        try {
            return URI.create(normalizedBase + normalizedPath);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * 为 MCP worker 调用设置短超时。
     *
     * <p>连接超时和读取超时分开配置：连接失败通常说明服务不可达，应快速释放 dispatcher；读取超时则说明 Python worker
     * 或外部 MCP Server 处理过慢，应进入重试/补偿，而不是让 Java 控制面线程无限等待。</p>
     */
    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(Math.max(1L, properties.getConnectTimeoutMs())));
        requestFactory.setReadTimeout(Duration.ofMillis(Math.max(1L, properties.getReadTimeoutMs())));
        return requestFactory;
    }

    /**
     * 应用服务间调用 Header。
     *
     * <p>traceId 允许跨 Java/Python 排障；source service 表明调用方身份；Authorization 或自定义认证头只在 token 非空时发送。
     * 注意 token 不会进入 {@link AgentMcpDurableWorkerCallResult}，也不应该进入日志或 runtime event。</p>
     */
    private void applyHeaders(HttpHeaders headers, AgentMcpDurableWorkerRunRequest request) {
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "agent-runtime");
        if (trimToNull(request.traceId()) != null) {
            headers.set(PlatformContextHeaders.TRACE_ID, request.traceId().trim());
        }
        String token = trimToNull(properties.getServiceAccountToken());
        String headerName = trimToNull(properties.getAuthHeaderName());
        if (token != null && headerName != null) {
            String headerValue = "Authorization".equalsIgnoreCase(headerName) ? "Bearer " + token : token;
            headers.set(headerName, headerValue);
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

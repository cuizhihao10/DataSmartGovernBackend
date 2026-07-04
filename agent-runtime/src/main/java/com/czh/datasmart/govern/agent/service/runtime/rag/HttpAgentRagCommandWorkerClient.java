/**
 * @Author : Cui
 * @Date: 2026/07/05 01:22
 * @Description DataSmart Govern Backend - HttpAgentRagCommandWorkerClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime.rag;

import com.czh.datasmart.govern.agent.config.AgentRagCommandWorkerClientProperties;
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
 * 基于 Spring RestClient 的 Python RAG Command Worker HTTP 客户端。
 *
 * <p>这是 Java agent-runtime 与 Python RAG 执行面的第一条真实桥接。它刻意保持职责单一：
 * 不做 RAG 检索、不做 prompt 拼接、不写 receipt、不修改 outbox，只负责把 Java 已经形成的 command
 * 投递给 Python 内部 API，并把 Python 返回的低敏响应包装为 {@link AgentRagCommandWorkerCallResult}。</p>
 *
 * <p>为什么当前先用 HTTP：RAG worker route 已经在 Python Runtime 内部落地，HTTP 最适合作为可测试的第一条
 * 联调链路。可靠性仍由 Java outbox dispatcher 提供：HTTP 失败会让 outbox 进入 FAILED 并按退避重试，
 * 不会退化为业务线程里的不可恢复同步调用。</p>
 *
 * <p>低敏策略：本类不会把 endpoint、Header、question、answer、Python 原始响应、异常堆栈或服务账号 token
 * 写入返回对象。后续如果需要日志，也应只记录 traceId、runId、commandId、errorCode、statusCode 这类低基数字段。</p>
 */
@Component
@RequiredArgsConstructor
public class HttpAgentRagCommandWorkerClient implements AgentRagCommandWorkerClient {

    private static final String ERROR_DISABLED = "RAG_COMMAND_WORKER_CLIENT_DISABLED";
    private static final String ERROR_REQUEST_MISSING = "RAG_COMMAND_WORKER_REQUEST_MISSING";
    private static final String ERROR_ENDPOINT_INVALID = "RAG_COMMAND_WORKER_ENDPOINT_INVALID";
    private static final String ERROR_HTTP_STATUS = "RAG_COMMAND_WORKER_HTTP_STATUS_NOT_SUCCESSFUL";
    private static final String ERROR_RESPONSE_MISSING = "RAG_COMMAND_WORKER_RESPONSE_MISSING";
    private static final String ERROR_CLIENT_EXCEPTION = "RAG_COMMAND_WORKER_CLIENT_EXCEPTION";

    private final AgentRagCommandWorkerClientProperties properties;
    private final RestClient.Builder restClientBuilder;

    /**
     * 调用 Python RAG Command Worker。
     *
     * <p>方法先做轻量配置校验，再套用请求默认值，最后发起 HTTP POST。所有失败都会转换为低敏
     * {@link AgentRagCommandWorkerCallResult}，让 dispatcher 用统一 outbox 状态机处理重试和阻断。</p>
     */
    @Override
    public AgentRagCommandWorkerCallResult run(AgentRagCommandWorkerRunRequest request) {
        if (!properties.isEnabled()) {
            return AgentRagCommandWorkerCallResult.skipped(
                    ERROR_DISABLED,
                    "RAG Command Worker 客户端未启用，本次不调用 Python Runtime。"
            );
        }
        if (request == null) {
            return AgentRagCommandWorkerCallResult.skipped(
                    ERROR_REQUEST_MISSING,
                    "RAG Command Worker 请求为空，已跳过投递。"
            );
        }
        URI endpoint = endpointUri();
        if (endpoint == null) {
            return AgentRagCommandWorkerCallResult.skipped(
                    ERROR_ENDPOINT_INVALID,
                    "RAG Command Worker endpoint 配置无效，已跳过投递。"
            );
        }

        AgentRagCommandWorkerRunRequest normalizedRequest = request.withClientDefaults(properties.isPostToJava());
        try {
            ResponseEntity<AgentRagCommandWorkerRunResponse> response = restClientBuilder
                    .requestFactory(requestFactory())
                    .build()
                    .post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> applyHeaders(headers, normalizedRequest))
                    .body(normalizedRequest)
                    .retrieve()
                    .toEntity(AgentRagCommandWorkerRunResponse.class);
            AgentRagCommandWorkerRunResponse body = response.getBody();
            if (body == null) {
                return AgentRagCommandWorkerCallResult.failed(
                        response.getStatusCode().value(),
                        ERROR_RESPONSE_MISSING,
                        "Python RAG Command Worker 返回空响应体，等待 dispatcher 后续重试或人工排查。"
                );
            }
            return AgentRagCommandWorkerCallResult.accepted(
                    response.getStatusCode().value(),
                    body,
                    Boolean.TRUE.equals(body.accepted())
                            ? "Python RAG Command Worker 已接受并处理请求。"
                            : "Python RAG Command Worker 返回未接受状态，详情请查看低敏 workerResult/receipt 摘要。"
            );
        } catch (RestClientResponseException exception) {
            return AgentRagCommandWorkerCallResult.failed(
                    exception.getStatusCode().value(),
                    ERROR_HTTP_STATUS,
                    "Python RAG Command Worker 返回非成功 HTTP 状态，响应正文已隐藏。"
            );
        } catch (RestClientException exception) {
            return AgentRagCommandWorkerCallResult.failed(
                    0,
                    ERROR_CLIENT_EXCEPTION,
                    "调用 Python RAG Command Worker 失败，底层地址、Header、question 和响应片段已隐藏。"
            );
        }
    }

    /**
     * 构造内部 API endpoint。
     *
     * <p>无效 URI 不向上抛原始异常，是为了避免内部地址进入 dispatcher 错误信息。
     * 调用方只需要看到低基数错误码，真实 baseUrl/runPath 留在配置层和运维排障上下文中。</p>
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
     * 设置短超时，避免 RAG worker 故障时长时间占用 dispatcher。
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
     * <p>Header 只在 HTTP 请求中使用，不会进入 {@link AgentRagCommandWorkerCallResult}。
     * traceId 允许跨 Java/Python 排障；source service 表明调用方身份；Authorization 或自定义认证头只在 token 非空时发送。</p>
     */
    private void applyHeaders(HttpHeaders headers, AgentRagCommandWorkerRunRequest request) {
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "agent-runtime");
        String traceId = text(request.controlFacts().get("traceId"));
        if (traceId != null) {
            headers.set(PlatformContextHeaders.TRACE_ID, traceId);
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

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() ? null : normalized;
    }
}

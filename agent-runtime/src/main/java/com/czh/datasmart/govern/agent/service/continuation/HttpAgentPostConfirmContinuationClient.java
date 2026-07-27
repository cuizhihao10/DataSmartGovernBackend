/**
 * @Author : Cui
 * @Date: 2026/07/27 00:00
 * @Description DataSmart Govern Backend - HttpAgentPostConfirmContinuationClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.continuation;

import com.czh.datasmart.govern.agent.config.AgentPostConfirmContinuationProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentPostConfirmContinuationRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentPostConfirmContinuationView;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Duration;

/**
 * 通过内部 HTTP 合同恢复 Python Durable Agent Loop。
 *
 * <p>网络或 Python Provider 故障采用 fail-open：原 Java 工具批次已经成功，不应被改写为失败；客户端返回
 * {@code FAILED_RETRYABLE}，让前端显示“已完成当前步骤，但自动续跑暂时失败”，后续可按 Run 恢复。</p>
 */
@Component
@RequiredArgsConstructor
public class HttpAgentPostConfirmContinuationClient implements AgentPostConfirmContinuationClient {

    private final AgentPostConfirmContinuationProperties properties;
    private final RestClient.Builder restClientBuilder;

    @Override
    public AgentPostConfirmContinuationView continueAfterConfirmedTools(AgentPostConfirmContinuationRequest request) {
        if (!properties.isEnabled()) {
            return AgentPostConfirmContinuationView.disabled();
        }
        URI endpoint = endpointUri();
        if (endpoint == null) {
            return AgentPostConfirmContinuationView.failed("确认后续跑地址配置无效，请检查 Python Runtime 内部地址。");
        }
        try {
            AgentPostConfirmContinuationView body = restClientBuilder
                    .requestFactory(requestFactory())
                    .build()
                    .post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "agent-runtime");
                        if (request.traceId() != null && !request.traceId().isBlank()) {
                            headers.set(PlatformContextHeaders.TRACE_ID, request.traceId());
                        }
                        String token = trimToNull(properties.getServiceAccountToken());
                        if (token != null) {
                            headers.setBearerAuth(token);
                        }
                    })
                    .body(request)
                    .retrieve()
                    .body(AgentPostConfirmContinuationView.class);
            return body == null
                    ? AgentPostConfirmContinuationView.failed("Python Runtime 返回空续跑结果，可稍后按当前 Run 重试。")
                    : body;
        } catch (RestClientException exception) {
            // RestClient 异常可能包含内部 URL、响应正文或 Header，不能直接回显给用户或写入审计摘要。
            return AgentPostConfirmContinuationView.failed(
                    "调用 Python Runtime 继续 Agent 流程失败；已完成的工具结果不会丢失，可稍后重试续跑。"
            );
        }
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.max(100, properties.getConnectTimeoutMs())));
        factory.setReadTimeout(Duration.ofMillis(Math.max(1000, properties.getReadTimeoutMs())));
        return factory;
    }

    private URI endpointUri() {
        String baseUrl = trimToNull(properties.getBaseUrl());
        String path = trimToNull(properties.getPath());
        if (baseUrl == null || path == null) {
            return null;
        }
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        try {
            return URI.create(normalizedBase + normalizedPath);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

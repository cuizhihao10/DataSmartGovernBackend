/**
 * @Author : Cui
 * @Date: 2026/07/01 11:00
 * @Description DataSmartGovernBackend - JdkPlatformEndpointProbeClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.observability.service.probe;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;

/**
 * 基于 JDK 21 HttpClient 的平台端点探针实现。
 *
 * <p>该实现只读取 HTTP 状态码，不读取响应正文。
 * 这点非常重要：健康检查、Prometheus 指标、Python Runtime 诊断等接口未来可能包含更多字段，
 * observability 聚合层不能因为“想统一展示”就把下游响应正文原样搬出来，否则很容易泄露内部拓扑、
 * token 片段、模型 Provider 信息、业务样本或用户上下文。</p>
 */
@Component
public class JdkPlatformEndpointProbeClient implements PlatformEndpointProbeClient {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public PlatformEndpointProbeResult probe(URI uri, Duration timeout) {
        Instant startedAt = Instant.now();
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int statusCode = response.statusCode();
            return new PlatformEndpointProbeResult(
                    true,
                    statusCode,
                    elapsedMillis(startedAt),
                    statusCode >= 200 && statusCode < 300 ? "OK" : "HTTP_" + statusCode);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new PlatformEndpointProbeResult(false, null, elapsedMillis(startedAt), "INTERRUPTED");
        } catch (IllegalArgumentException exception) {
            return new PlatformEndpointProbeResult(false, null, elapsedMillis(startedAt), "INVALID_URI");
        } catch (IOException exception) {
            return new PlatformEndpointProbeResult(false, null, elapsedMillis(startedAt), classifyIoException(exception));
        }
    }

    private String classifyIoException(IOException exception) {
        if (exception instanceof ConnectException) {
            return "CONNECTION_REFUSED";
        }
        if (exception.getCause() instanceof TimeoutException) {
            return "TIMEOUT";
        }
        String message = exception.getMessage();
        if (message != null && message.toLowerCase().contains("timed out")) {
            return "TIMEOUT";
        }
        return "NETWORK_ERROR";
    }

    private long elapsedMillis(Instant startedAt) {
        return Math.max(0L, Duration.between(startedAt, Instant.now()).toMillis());
    }
}

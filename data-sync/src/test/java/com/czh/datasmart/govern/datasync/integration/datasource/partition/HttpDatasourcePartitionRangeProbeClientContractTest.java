/**
 * @Author : Cui
 * @Date: 2026/08/13 23:58
 * @Description DataSmart Govern Backend - HttpDatasourcePartitionRangeProbeClientContractTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.partition;

import com.czh.datasmart.govern.datasync.config.DataSyncDatasourceRunOnceProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 在不依赖 Docker 或 DNS 的情况下验证范围探测的传输故障分类。
 *
 * <p>本测试刻意区分关闭的本地端口与 HTTP 业务拒绝。前者是 Autopilot 的受限瞬态候选；后者可能表示
 * 授权或契约故障，必须仍是通用的不可重试平台错误。</p>
 */
class HttpDatasourcePartitionRangeProbeClientContractTest {

    private static final String PROBE_PATH = "/internal/sync-batch-runs/partition-range-probe";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void closedTransportShouldBecomeRetryableProbeException() throws Exception {
        String baseUrl;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1,
                java.net.InetAddress.getLoopbackAddress())) {
            baseUrl = "http://127.0.0.1:" + socket.getLocalPort();
        }

        assertThatThrownBy(() -> client(baseUrl).probeRange(request(), actor()))
                .isInstanceOf(DatasourcePartitionRangeProbeTransportUnavailableException.class)
                .hasMessageContaining("transport is temporarily unavailable")
                .hasMessageNotContaining("127.0.0.1");
    }

    @Test
    void httpRejectionShouldRemainNonRetryablePlatformError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext(PROBE_PATH, exchange -> respond(exchange, 403,
                    "{\"code\":403,\"message\":\"permission denied\"}"));
            server.start();

            assertThatThrownBy(() -> client("http://127.0.0.1:" + server.getAddress().getPort())
                    .probeRange(request(), actor()))
                    .isInstanceOf(com.czh.datasmart.govern.common.error.PlatformBusinessException.class)
                    .isNotInstanceOf(DatasourcePartitionRangeProbeTransportUnavailableException.class)
                    .hasMessageNotContaining("permission denied");
        } finally {
            server.stop(0);
        }
    }

    private HttpDatasourcePartitionRangeProbeClient client(String baseUrl) {
        DataSyncDatasourceRunOnceProperties properties = new DataSyncDatasourceRunOnceProperties();
        properties.setBaseUrl(baseUrl);
        properties.setPartitionRangeProbePath(PROBE_PATH);
        properties.setConnectTimeoutMs(500L);
        properties.setReadTimeoutMs(500L);
        return new HttpDatasourcePartitionRangeProbeClient(RestClient.builder(), properties);
    }

    private DatasourcePartitionRangeProbeRequest request() {
        DatasourcePartitionRangeProbeRequest request = new DatasourcePartitionRangeProbeRequest();
        request.setDatasourceId(55L);
        request.setConnectorType("MYSQL");
        request.setObjectLocator("e2e.orders");
        request.setSplitPk("id");
        return request;
    }

    private SyncActorContext actor() {
        return new SyncActorContext(10L, 1001L, "SERVICE_ACCOUNT", "range-probe-contract-test");
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}

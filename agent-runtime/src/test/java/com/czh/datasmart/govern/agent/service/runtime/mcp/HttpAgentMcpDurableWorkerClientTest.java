/**
 * @Author : Cui
 * @Date: 2026/07/03 16:46
 * @Description DataSmart Govern Backend - HttpAgentMcpDurableWorkerClientTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime.mcp;

import com.czh.datasmart.govern.agent.config.AgentMcpDurableWorkerClientProperties;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Python MCP Durable Worker HTTP client 测试。
 *
 * <p>这组测试不是为了验证 Python worker 的业务逻辑，而是保护 Java/Python 服务间合同：</p>
 * <p>1. Java client 默认关闭，不会在本地学习环境误触发网络调用；</p>
 * <p>2. 开启后只向内部 API 发送一次 POST，并携带 traceId、source service、控制事实和客户端默认执行选项；</p>
 * <p>3. Python HTTP 失败时 Java 只返回低敏错误码，不把请求参数、Authorization、内部 URL 或远端响应正文扩散到上层。</p>
 */
class HttpAgentMcpDurableWorkerClientTest {

    @Test
    void shouldSkipWithoutHttpCallWhenClientDisabled() {
        RestClient.Builder builder = RestClient.builder();
        AgentMcpDurableWorkerClientProperties properties = properties(false);
        HttpAgentMcpDurableWorkerClient client = new HttpAgentMcpDurableWorkerClient(properties, builder);

        AgentMcpDurableWorkerCallResult result = client.run(request());

        assertFalse(result.attempted());
        assertTrue(result.skipped());
        assertEquals("MCP_DURABLE_WORKER_CLIENT_DISABLED", result.errorCode());
        assertNull(result.response());
    }

    @Test
    void shouldPostControlledFactsAndParseLowSensitiveResponse() throws IOException {
        try (ServerFixture server = ServerFixture.start(200, """
                        {
                          "schemaVersion": "datasmart.ai-runtime.mcp-durable-worker-response.v1",
                          "accepted": true,
                          "workerResult": {
                            "status": "SUCCEEDED",
                            "resultDigest": "sha256:abc"
                          },
                          "receipt": {
                            "outcome": "EXECUTION_SUCCEEDED",
                            "targetService": "python-ai-runtime-mcp-client"
                          },
                          "modelFeedback": {
                            "status": "success",
                            "artifactReference": "agent-runtime://tool-results/call-001"
                          },
                          "payloadPolicy": "MCP_ARGUMENTS_NEVER_RETURNED"
                        }
                        """)) {
            RestClient.Builder builder = RestClient.builder();
            AgentMcpDurableWorkerClientProperties properties = properties(true);
            properties.setBaseUrl(server.baseUrl());
            properties.setServiceAccountToken("unit-test-token");
            HttpAgentMcpDurableWorkerClient client = new HttpAgentMcpDurableWorkerClient(properties, builder);

            AgentMcpDurableWorkerCallResult result = client.run(request());

            assertEquals(1, server.requestCount());
            assertEquals("/internal/agent/mcp/durable-worker/run", server.requestPath());
            assertEquals("POST", server.requestMethod());
            assertEquals("agent-runtime", server.firstHeader(PlatformContextHeaders.SOURCE_SERVICE));
            assertEquals("trace-mcp-worker", server.firstHeader(PlatformContextHeaders.TRACE_ID));
            assertEquals("Bearer unit-test-token", server.firstHeader("Authorization"));
            assertTrue(server.requestBody().contains("\"serverId\":\"enterprise-search\""));
            assertTrue(server.requestBody().contains("\"internalToolName\":\"mcp.enterprise.search\""));
            assertTrue(server.requestBody().contains("\"query\":\"governance catalog\""));
            assertTrue(server.requestBody().contains("\"readinessDecision\":\"READY\""));
            assertTrue(server.requestBody().contains("\"postToJava\":false"));
            assertTrue(server.requestBody().contains("\"includeModelFeedback\":true"));

            assertTrue(result.attempted());
            assertFalse(result.skipped());
            assertTrue(result.accepted());
            assertEquals(200, result.statusCode());
            assertNull(result.errorCode());
            assertNotNull(result.response());
            assertEquals("SUCCEEDED", result.response().workerResult().get("status"));
            assertEquals("EXECUTION_SUCCEEDED", result.response().receipt().get("outcome"));
            assertEquals("MCP_ARGUMENTS_NEVER_RETURNED", result.response().payloadPolicy());
        }
    }

    @Test
    void shouldHideArgumentsAndRemoteBodyWhenPythonReturnsError() throws IOException {
        try (ServerFixture server = ServerFixture.start(500, """
                {"detail":"remote failed with secret query=governance catalog and internal endpoint"}
                """)) {
            RestClient.Builder builder = RestClient.builder();
            AgentMcpDurableWorkerClientProperties properties = properties(true);
            properties.setBaseUrl(server.baseUrl());
            HttpAgentMcpDurableWorkerClient client = new HttpAgentMcpDurableWorkerClient(properties, builder);

            AgentMcpDurableWorkerCallResult result = client.run(request());

            assertEquals(1, server.requestCount());
            assertTrue(result.attempted());
            assertFalse(result.accepted());
            assertEquals(500, result.statusCode());
            assertEquals("MCP_DURABLE_WORKER_HTTP_STATUS_NOT_SUCCESSFUL", result.errorCode());
            assertFalse(result.message().contains("governance catalog"));
            assertFalse(result.message().contains("internal endpoint"));
            assertFalse(result.message().contains(server.baseUrl()));
        }
    }

    private AgentMcpDurableWorkerClientProperties properties(boolean enabled) {
        AgentMcpDurableWorkerClientProperties properties = new AgentMcpDurableWorkerClientProperties();
        properties.setEnabled(enabled);
        properties.setBaseUrl("http://python-runtime.test");
        properties.setRunPath("/internal/agent/mcp/durable-worker/run");
        properties.setIncludeModelFeedback(true);
        properties.setPostToJava(false);
        properties.setConnectTimeoutMs(100);
        properties.setReadTimeoutMs(1000);
        return properties;
    }

    private AgentMcpDurableWorkerRunRequest request() {
        return new AgentMcpDurableWorkerRunRequest(
                "enterprise-search",
                "mcp.enterprise.search",
                Map.of("query", "governance catalog"),
                Map.of(
                        "tenantId", "10",
                        "projectId", "20",
                        "workspaceKey", "workspace-alpha",
                        "readinessDecision", "READY",
                        "permissionGranted", true,
                        "approvalVerified", true,
                        "allowedInternalToolNames", "mcp.enterprise.search"
                ),
                Map.of("source", "java-agent-runtime-test"),
                null,
                "session-001",
                "trace-mcp-worker",
                "call-001",
                "workspace-alpha",
                "workspace-alpha",
                null
        );
    }

    /**
     * 轻量本地 HTTP Server fixture。
     *
     * <p>这里没有继续使用 MockRestServiceServer，是因为生产客户端会为 RestClient 设置真实超时 requestFactory。
     * MockRestServiceServer 依赖替换 builder 的 requestFactory，二者会互相覆盖。使用 JDK 自带 HttpServer 可以在不降低生产代码
     * 超时能力的前提下，验证真实 HTTP 请求、Header、body 与错误状态处理。</p>
     */
    private static final class ServerFixture implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger requestCount = new AtomicInteger();
        private final AtomicReference<String> requestPath = new AtomicReference<>();
        private final AtomicReference<String> requestMethod = new AtomicReference<>();
        private final AtomicReference<String> requestBody = new AtomicReference<>("");
        private final AtomicReference<com.sun.net.httpserver.Headers> requestHeaders = new AtomicReference<>();

        private ServerFixture(HttpServer server) {
            this.server = server;
        }

        static ServerFixture start(int statusCode, String responseBody) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            ServerFixture fixture = new ServerFixture(server);
            server.createContext("/internal/agent/mcp/durable-worker/run", exchange -> {
                fixture.requestCount.incrementAndGet();
                fixture.requestPath.set(exchange.getRequestURI().getPath());
                fixture.requestMethod.set(exchange.getRequestMethod());
                fixture.requestHeaders.set(exchange.getRequestHeaders());
                fixture.requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(statusCode, bytes.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            server.start();
            return fixture;
        }

        String baseUrl() {
            return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
        }

        int requestCount() {
            return requestCount.get();
        }

        String requestPath() {
            return requestPath.get();
        }

        String requestMethod() {
            return requestMethod.get();
        }

        String requestBody() {
            return requestBody.get();
        }

        String firstHeader(String name) {
            return requestHeaders.get() == null ? null : requestHeaders.get().getFirst(name);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}

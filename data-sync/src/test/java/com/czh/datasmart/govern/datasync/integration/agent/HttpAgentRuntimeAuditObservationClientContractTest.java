/**
 * @Author : Cui
 * @Date: 2026/08/15
 * @Description DataSmart Govern Backend - HttpAgentRuntimeAuditObservationClientContractTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.agent;

import com.czh.datasmart.govern.datasync.config.DataSyncAgentRuntimeObservationProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用本地 HTTP 服务验证统一图对 Agent Runtime 的两个只读合同。
 *
 * <p>响应故意包含工具参数、命令 payload 和内部 endpoint 等多余字段；客户端 DTO 没有这些属性，测试只断言
 * 状态、次数和时间，防止以后为了“展示更多”把内部控制面正文带进 data-sync。</p>
 */
class HttpAgentRuntimeAuditObservationClientContractTest {

    /** 精确 auditId 与 commandId 必须分别命中各自事实，不能把 Recovery outbox 代替初始 Kafka 命令。 */
    @Test
    void shouldReadBoundedAuditAndCommandFacts() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/agent-runtime/sessions/session-1/runs/run-1/tool-executions", exchange ->
                    respond(exchange, """
                            {"code":0,"data":[{
                              "auditId":"audit-1","sessionId":"session-1","runId":"run-1",
                              "tenantId":11,"projectId":13,"state":"SUCCEEDED","toolCode":"data-sync.execute",
                              "riskLevel":"LOW","requiresApproval":false,
                              "executionStartTime":"2026-08-15T01:01:00",
                              "executionFinishTime":"2026-08-15T01:02:00",
                              "planArguments":{"sql":"不应进入本地 DTO"},"targetEndpoint":"internal-only"
                            }]}
                            """));
            server.createContext("/agent-runtime/sessions/session-1/runs/run-1/async-task-commands/command-1/observation", exchange ->
                    respond(exchange, """
                            {"code":0,"data":{"found":true,"status":"PUBLISHED","attemptCount":1,
                              "publishedAt":"2026-08-15T01:02:00Z","updatedAt":"2026-08-15T01:02:00Z",
                              "sourceStatus":"AGENT_RUNTIME_COMMAND_OUTBOX",
                              "payloadJson":"不应进入本地 DTO","targetEndpoint":"internal-only"}}
                            """));
            server.start();
            HttpAgentRuntimeAuditObservationClient client = client(server.getAddress().getPort());

            AgentRuntimeAuditObservation audit = client.observe(
                    "session-1", "run-1", "audit-1", actor());
            AgentRuntimeCommandObservation command = client.observeCommand(
                    "session-1", "run-1", "command-1", actor());

            assertThat(audit.available()).isTrue();
            assertThat(audit.found()).isTrue();
            assertThat(audit.state()).isEqualTo("SUCCEEDED");
            assertThat(audit.toolCode()).isEqualTo("data-sync.execute");
            assertThat(audit.tenantId()).isEqualTo(11L);
            assertThat(audit.projectId()).isEqualTo(13L);
            assertThat(command.available()).isTrue();
            assertThat(command.found()).isTrue();
            assertThat(command.status()).isEqualTo("PUBLISHED");
            assertThat(command.attemptCount()).isEqualTo(1);
            assertThat(command.publishedAt()).isNotNull();
            assertThat(command.updatedAt()).isNotNull();
        } finally {
            server.stop(0);
        }
    }

    private HttpAgentRuntimeAuditObservationClient client(int port) {
        DataSyncAgentRuntimeObservationProperties properties =
                new DataSyncAgentRuntimeObservationProperties();
        properties.setBaseUrl("http://127.0.0.1:" + port);
        properties.setInternalServiceToken("test-only-token");
        properties.setConnectTimeoutMs(500L);
        properties.setReadTimeoutMs(500L);
        return new HttpAgentRuntimeAuditObservationClient(RestClient.builder(), properties);
    }

    private SyncActorContext actor() {
        return new SyncActorContext(11L, 13L, null, 7L, "ORDINARY_USER", "trace-1",
                null, null, java.util.List.of(), false);
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}

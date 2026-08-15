/**
 * @Author : Cui
 * @Date: 2026/08/15
 * @Description DataSmart Govern Backend - HttpDataSyncAgentExecuteClientContractTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.service.agent.DataSyncAgentExecuteRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 task-management 调用真实 data-sync 内部入口时携带服务身份且不泄露令牌。 */
class HttpDataSyncAgentExecuteClientContractTest {

    @Test
    void shouldSendTrustedServiceIdentityToDataSync() throws Exception {
        AtomicReference<String> sourceService = new AtomicReference<>();
        AtomicReference<String> internalToken = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/internal/data-sync/agent/tasks/execute", exchange -> {
                sourceService.set(exchange.getRequestHeaders().getFirst(PlatformContextHeaders.SOURCE_SERVICE));
                internalToken.set(exchange.getRequestHeaders().getFirst(PlatformContextHeaders.INTERNAL_SERVICE_TOKEN));
                byte[] body = """
                        {"code":0,"data":{"commandId":"command-1","syncTaskId":31,
                        "syncExecutionId":41,"state":"QUEUED","created":false,"queued":true,
                        "duplicate":false,"message":"已入队"}}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(body);
                }
            });
            server.start();
            AgentAsyncToolWorkerProperties properties = new AgentAsyncToolWorkerProperties();
            properties.setDataSyncBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setDataSyncInternalServiceToken("test-only-token");
            HttpDataSyncAgentExecuteClient client =
                    new HttpDataSyncAgentExecuteClient(RestClient.builder(), properties);
            DataSyncAgentExecuteRequest request = new DataSyncAgentExecuteRequest();
            request.setCommandId("command-1");
            request.setTenantId(11L);
            request.setTraceId("trace-1");

            var response = client.execute(request);

            assertThat(response.syncExecutionId()).isEqualTo(41L);
            assertThat(sourceService.get()).isEqualTo("task-management");
            assertThat(internalToken.get()).isEqualTo("test-only-token");
        } finally {
            server.stop(0);
        }
    }
}

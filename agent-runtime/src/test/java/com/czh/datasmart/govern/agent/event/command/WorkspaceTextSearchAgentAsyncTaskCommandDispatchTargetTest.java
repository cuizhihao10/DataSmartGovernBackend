/**
 * @Author : Cui
 * @Date: 2026/08/11 23:05
 * @Description DataSmart Govern Backend - WorkspaceTextSearchAgentAsyncTaskCommandDispatchTargetTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import com.czh.datasmart.govern.agent.config.AgentWorkspaceTextSearchWorkerProperties;
import com.czh.datasmart.govern.agent.config.AgentToolActionResumeFactBundleProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolPlanArgumentsPayloadView;
import com.czh.datasmart.govern.agent.service.runtime.AgentCommandWorkerLeaseService;
import com.czh.datasmart.govern.agent.service.AgentToolPlanArgumentsPayloadService;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionRecord;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionCommandWorkerReceiptService;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionWorkerReceiptIndexService;
import com.czh.datasmart.govern.agent.service.runtime.InMemoryAgentCommandWorkerLeaseStore;
import com.czh.datasmart.govern.agent.service.runtime.InMemoryAgentRuntimeEventProjectionStore;
import com.czh.datasmart.govern.agent.service.runtime.InMemoryAgentToolActionWorkerReceiptIndexStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code workspace.text.search} 专用 durable outbox 调度目标测试。
 *
 * <p>这组测试保护四个容易被误实现的边界：只有精确工具码可以命中；模型不能指定真实文件系统根目录；
 * Python 必须返回声明过的低敏 payload policy；被接受的只读检索回执必须进入 Java timeline，同时不能把
 * 查询正文、绝对路径或文件内容持久化。</p>
 */
class WorkspaceTextSearchAgentAsyncTaskCommandDispatchTargetTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    /**
     * 验证专用 target 只按稳定工具码选择命令，不相信 topic、consumerService 或 targetService 路由提示。
     */
    @Test
    void supportsShouldRequireExactWorkspaceTextSearchToolCode() {
        WorkspaceTextSearchAgentAsyncTaskCommandDispatchTarget target = target(
                defaultProperties(),
                RestClient.builder(),
                new InMemoryAgentRuntimeEventProjectionStore(10, 100)
        );

        assertTrue(target.supports(record("workspace.text.search", searchPayload())));
        assertFalse(target.supports(record("workspace.file.read", searchPayload())));
        assertFalse(target.supports(record("workspace.text.search.extra", searchPayload())));
        assertFalse(target.supports(null));
    }

    /**
     * 验证真实根目录只由服务端配置注入。
     *
     * <p>测试 payload 故意同时放入顶层和 arguments 内的伪造根目录。生成的 worker request 中不能保留它们，
     * controlFacts.repositoryRoot 必须始终等于运维配置的只读容器挂载路径。</p>
     */
    @Test
    void serverConfiguredRootShouldOverrideEveryModelSuppliedRoot() {
        AgentWorkspaceTextSearchWorkerProperties properties = defaultProperties();
        properties.setRepositoryRoot("/repositories/backend");
        WorkspaceTextSearchAgentAsyncTaskCommandDispatchTarget target = target(
                properties,
                RestClient.builder(),
                new InMemoryAgentRuntimeEventProjectionStore(10, 100)
        );

        WorkspaceTextSearchAgentAsyncTaskCommandDispatchTarget.WorkerRequest request =
                target.toWorkerRequest(record("workspace.text.search", """
                        {
                          "repositoryRoot": "C:/model-selected-root",
                          "applicationId": "40",
                          "arguments": {
                            "query": "needle",
                            "repositoryRoot": "D:/another-model-root",
                            "relativePathPrefix": "docs"
                          }
                        }
                        """));

        assertEquals("/repositories/backend", request.controlFacts().get("repositoryRoot"));
        assertEquals("40", request.controlFacts().get("applicationId"));
        assertEquals("needle", request.arguments().get("query"));
        assertEquals("docs", request.arguments().get("relativePathPrefix"));
        assertFalse(request.arguments().containsKey("repositoryRoot"));
        assertFalse(request.controlFacts().containsValue("C:/model-selected-root"));
        assertFalse(request.controlFacts().containsValue("D:/another-model-root"));
    }

    /**
     * 验证正式 outbox 协议会通过 payloadReference 回读持久化参数，而不是依赖命令内联参数。
     *
     * <p>这条回归模拟真实生产形状：Kafka/outbox payload 只带引用和控制面元数据，查询正文只存在于
     * Java 审计参数快照。dispatcher 必须在 HTTP 调用前复核引用、会话、Run、工具、租户、项目、actor
     * 和 PLANNED 状态；任何一项不匹配都应失败关闭。</p>
     */
    @Test
    void formalPayloadReferenceShouldResolvePersistedArgumentsBeforeWorkerDispatch() {
        AgentToolPlanArgumentsPayloadService payloadService = mock(AgentToolPlanArgumentsPayloadService.class);
        String payloadReference = "agent-tool-audit://session-text-search-001/run-text-search-001/"
                + "audit-text-search-001/plan-arguments";
        when(payloadService.getPlanArgumentsPayload(
                "session-text-search-001", "run-text-search-001", "audit-text-search-001"
        )).thenReturn(new AgentToolPlanArgumentsPayloadView(
                payloadReference,
                "plan-arguments",
                "session-text-search-001",
                "run-text-search-001",
                "audit-text-search-001",
                "workspace.text.search",
                "python-ai-runtime",
                "/internal/agent/workspace-text/command-worker/run",
                10L,
                20L,
                30L,
                "1001",
                "trace-text-search-001",
                "ASYNC_TASK",
                "PLANNED",
                List.of("caseSensitive", "maxResults", "query", "relativePathPrefix", "searchMode"),
                List.of(),
                Map.of(
                        "query", "needle",
                        "relativePathPrefix", "docs",
                        "caseSensitive", false,
                        "searchMode", "LITERAL",
                        "maxResults", 5
                ),
                Map.of("toolCode", "workspace.text.search"),
                Map.of("missingFields", List.of()),
                LocalDateTime.now()
        ));

        WorkspaceTextSearchAgentAsyncTaskCommandDispatchTarget target = target(
                defaultProperties(),
                RestClient.builder(),
                new InMemoryAgentRuntimeEventProjectionStore(10, 100),
                payloadService
        );
        AgentAsyncTaskCommandOutboxRecord record = record(
                "workspace.text.search",
                """
                        {
                          "applicationId": "40",
                          "payloadReference": "%s"
                        }
                        """.formatted(payloadReference)
        );

        WorkspaceTextSearchAgentAsyncTaskCommandDispatchTarget.WorkerRequest request =
                target.toWorkerRequest(record);

        assertEquals("needle", request.arguments().get("query"));
        assertEquals("docs", request.arguments().get("relativePathPrefix"));
        assertFalse(record.payloadJson().contains("needle"));
    }

    /**
     * 缺少查询正文时必须在 Java 侧失败，不能让 Python 扫描一个隐式或空查询。
     */
    @Test
    void missingQueryShouldFailClosedBeforeHttpDispatch() {
        WorkspaceTextSearchAgentAsyncTaskCommandDispatchTarget target = target(
                defaultProperties(),
                RestClient.builder(),
                new InMemoryAgentRuntimeEventProjectionStore(10, 100)
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> target.toWorkerRequest(record("workspace.text.search", "{\"arguments\":{}}"))
        );

        assertTrue(exception.getMessage().contains("literal query"));
    }

    /**
     * 缺少服务端根目录配置时必须失败，不能回退到 Java 或 Python 的当前工作目录。
     */
    @Test
    void missingConfiguredRootShouldFailClosedBeforeHttpDispatch() {
        AgentWorkspaceTextSearchWorkerProperties properties = defaultProperties();
        properties.setRepositoryRoot(" ");
        WorkspaceTextSearchAgentAsyncTaskCommandDispatchTarget target = target(
                properties,
                RestClient.builder(),
                new InMemoryAgentRuntimeEventProjectionStore(10, 100)
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> target.toWorkerRequest(record("workspace.text.search", searchPayload()))
        );

        assertTrue(exception.getMessage().contains("repositoryRoot"));
    }

    /**
     * 内部只读挂载也属于受保护资源；服务 token 缺失时必须在网络调用前失败关闭。
     */
    @Test
    void missingServiceTokenShouldFailClosedBeforeHttpDispatch() {
        AgentWorkspaceTextSearchWorkerProperties properties = defaultProperties();
        properties.setServiceAccountToken(" ");
        WorkspaceTextSearchAgentAsyncTaskCommandDispatchTarget target = target(
                properties,
                RestClient.builder(),
                new InMemoryAgentRuntimeEventProjectionStore(10, 100)
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> target.dispatch(record("workspace.text.search", searchPayload()))
        );

        assertTrue(exception.getMessage().contains("serviceAccountToken"));
    }

    /**
     * 验证真实 HTTP 响应可以被解析并写入共享 Java 回执链路。
     *
     * <p>测试响应包含 Python 新增的 {@code workerRunnerSchemaVersion}，用于证明 Java 会容忍低敏扩展字段。
     * 最终 timeline 中应保留稳定工具码和执行结果，但不应出现原始查询或工作区绝对路径。</p>
     */
    @Test
    void acceptedWorkerResponseShouldPersistLowSensitiveReceipt() throws Exception {
        AtomicReference<Map<String, Object>> capturedRequest = new AtomicReference<>();
        HttpServer server = startServer(acceptedResponse(), capturedRequest);
        try {
            InMemoryAgentRuntimeEventProjectionStore projectionStore =
                    new InMemoryAgentRuntimeEventProjectionStore(10, 100);
            AgentWorkspaceTextSearchWorkerProperties properties = propertiesFor(server);
            WorkspaceTextSearchAgentAsyncTaskCommandDispatchTarget target = target(
                    properties,
                    RestClient.builder(),
                    projectionStore
            );

            target.dispatch(record("workspace.text.search", searchPayload()));

            Map<String, Object> controlFacts = nestedMap(capturedRequest.get(), "controlFacts");
            Map<String, Object> arguments = nestedMap(capturedRequest.get(), "arguments");
            assertEquals("/repositories/backend", controlFacts.get("repositoryRoot"));
            assertEquals("40", controlFacts.get("applicationId"));
            assertEquals("needle", arguments.get("query"));
            assertEquals("Bearer test-workspace-text-search-token", capturedRequest.get().get("_authorization"));

            AgentRuntimeEventProjectionRecord event =
                    projectionStore.listByRunId("run-text-search-001").getFirst();
            assertEquals("workspace.text.search", event.attributes().get("toolCode"));
            assertEquals("WORKSPACE_TEXT_SEARCH_COMPLETED", event.attributes().get("outcome"));
            String eventJson = OBJECT_MAPPER.writeValueAsString(event);
            assertFalse(eventJson.contains("needle"));
            assertFalse(eventJson.contains("/repositories/backend"));
        } finally {
            server.stop(0);
        }
    }

    /**
     * Python 未声明检索专属 payload policy 时，outbox 调度必须失败且不能写入回执 timeline。
     */
    @Test
    void missingPayloadPolicyShouldRejectResponseWithoutReceiptIngestion() throws Exception {
        AtomicReference<Map<String, Object>> capturedRequest = new AtomicReference<>();
        HttpServer server = startServer(responseWithPayloadPolicy("SUMMARY_ONLY"), capturedRequest);
        try {
            InMemoryAgentRuntimeEventProjectionStore projectionStore =
                    new InMemoryAgentRuntimeEventProjectionStore(10, 100);
            WorkspaceTextSearchAgentAsyncTaskCommandDispatchTarget target = target(
                    propertiesFor(server),
                    RestClient.builder(),
                    projectionStore
            );

            assertThrows(
                    IllegalStateException.class,
                    () -> target.dispatch(record("workspace.text.search", searchPayload()))
            );
            assertTrue(projectionStore.listByRunId("run-text-search-001").isEmpty());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 创建测试 target，并使用内存 projection/index/lease store 观察回执写入结果。
     *
     * @param properties worker 地址、超时和受控根目录配置
     * @param builder 将向测试 HTTP server 发请求的 RestClient builder
     * @param projectionStore 用于断言 timeline 的内存存储
     * @return 可执行真实 request/response 映射的专用调度目标
     */
    private WorkspaceTextSearchAgentAsyncTaskCommandDispatchTarget target(
            AgentWorkspaceTextSearchWorkerProperties properties,
            RestClient.Builder builder,
            InMemoryAgentRuntimeEventProjectionStore projectionStore) {
        return target(properties, builder, projectionStore, mock(AgentToolPlanArgumentsPayloadService.class));
    }

    /**
     * 创建可替换参数回读服务的测试 target，便于分别验证 legacy inline 和正式 reference 两种协议。
     */
    private WorkspaceTextSearchAgentAsyncTaskCommandDispatchTarget target(
            AgentWorkspaceTextSearchWorkerProperties properties,
            RestClient.Builder builder,
            InMemoryAgentRuntimeEventProjectionStore projectionStore,
            AgentToolPlanArgumentsPayloadService payloadService) {
        AgentToolActionCommandWorkerReceiptService receiptService =
                new AgentToolActionCommandWorkerReceiptService(
                        projectionStore,
                        new AgentToolActionWorkerReceiptIndexService(
                                receiptIndexStore()
                        ),
                        new AgentCommandWorkerLeaseService(new InMemoryAgentCommandWorkerLeaseStore())
                );
        return new WorkspaceTextSearchAgentAsyncTaskCommandDispatchTarget(
                properties,
                builder,
                receiptService,
                payloadService,
                OBJECT_MAPPER
        );
    }

    /**
     * 通过生产公开构造器创建容量受限的内存回执索引。
     *
     * <p>容量值来自同一份强类型配置对象，这与 Spring 运行时装配方式一致，也避免测试依赖包内构造器。</p>
     */
    private InMemoryAgentToolActionWorkerReceiptIndexStore receiptIndexStore() {
        AgentToolActionResumeFactBundleProperties properties =
                new AgentToolActionResumeFactBundleProperties();
        properties.setWorkerReceiptIndexMaxRecords(100);
        return new InMemoryAgentToolActionWorkerReceiptIndexStore(properties);
    }

    /**
     * 构造 fail-closed 的默认 worker 配置，测试可以按需覆盖 HTTP 地址或根目录。
     */
    private AgentWorkspaceTextSearchWorkerProperties defaultProperties() {
        AgentWorkspaceTextSearchWorkerProperties properties = new AgentWorkspaceTextSearchWorkerProperties();
        properties.setEnabled(true);
        properties.setRepositoryRoot("/repositories/backend");
        properties.setConnectTimeoutMs(1000);
        properties.setReadTimeoutMs(3000);
        properties.setServiceAccountToken("test-workspace-text-search-token");
        return properties;
    }

    /**
     * 将随机端口测试服务器转换成生产类可使用的内部 worker URL 配置。
     */
    private AgentWorkspaceTextSearchWorkerProperties propertiesFor(HttpServer server) {
        AgentWorkspaceTextSearchWorkerProperties properties = defaultProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setRunPath("/internal/agent/workspace-text/command-worker/run");
        return properties;
    }

    /**
     * 启动最小本地 HTTP worker，用真实网络序列化验证 RestClient 与 Python JSON 合同。
     *
     * @param responseBody worker 返回的 JSON
     * @param capturedRequest 保存 Java 实际发送的请求，供测试检查受控根目录覆盖行为
     * @return 已启动且监听随机本地端口的服务器
     */
    private HttpServer startServer(
            String responseBody,
            AtomicReference<Map<String, Object>> capturedRequest) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/agent/workspace-text/command-worker/run", exchange -> {
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            Map<String, Object> parsedRequest = OBJECT_MAPPER.readValue(
                    requestBytes,
                    new TypeReference<LinkedHashMap<String, Object>>() { }
            );
            parsedRequest.put("_authorization", exchange.getRequestHeaders().getFirst("Authorization"));
            capturedRequest.set(parsedRequest);
            byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    /**
     * 返回包含扩展 schema 字段和 Java 低敏回执的成功响应。
     */
    private String acceptedResponse() throws IOException {
        return responseWithPayloadPolicy(
                "WORKSPACE_TEXT_SEARCH_SUMMARY_ONLY_NO_RAW_QUERY_FIELD_NO_ABSOLUTE_PATH_NO_FILE_BODY_NO_CREDENTIAL"
        );
    }

    /**
     * 根据指定 policy 构造 worker 响应，便于同时覆盖成功和缺失合同标记两条路径。
     */
    private String responseWithPayloadPolicy(String payloadPolicy) throws IOException {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("schemaVersion", "datasmart.workspace-text-search-worker-api.v1");
        response.put("workerRunnerSchemaVersion", "datasmart.python-ai-runtime.workspace-text-search-worker.v1");
        response.put("accepted", true);
        response.put("toolCode", "workspace.text.search");
        response.put("workerResult", Map.of("status", "SUCCEEDED", "matchCount", 1));
        response.put("receipt", Map.of("outcome", "WORKSPACE_TEXT_SEARCH_COMPLETED"));
        response.put("javaReceiptPayload", javaReceiptPayload());
        response.put("payloadPolicy", payloadPolicy);
        return OBJECT_MAPPER.writeValueAsString(response);
    }

    /**
     * 构造 Java 回执服务允许持久化的低敏字段，不包含查询、路径、文件正文或凭据。
     */
    private Map<String, Object> javaReceiptPayload() {
        return Map.ofEntries(
                Map.entry("commandId", "cmd-text-search-001"),
                Map.entry("executorId", "python-text-search-worker"),
                Map.entry("tenantId", 10L),
                Map.entry("projectId", 20L),
                Map.entry("actorId", 1001L),
                Map.entry("taskStatus", "SUCCEEDED"),
                Map.entry("outcome", "WORKSPACE_TEXT_SEARCH_COMPLETED"),
                Map.entry("preCheckPassed", true),
                Map.entry("sideEffectStarted", false),
                Map.entry("sideEffectExecuted", false),
                Map.entry("workerLeaseRequired", false),
                Map.entry("commandSafetyDecision", "ALLOW_READ_ONLY_TEXT_SEARCH"),
                Map.entry("commandSafetyPolicyVersion", "text-search-policy.v1"),
                Map.entry("commandSafetyIssueCodes", List.of()),
                Map.entry("normalizedTimeoutSeconds", 0),
                Map.entry("normalizedOutputByteLimitBytes", 4096),
                Map.entry("artifactAvailable", false),
                Map.entry("errorCode", "AGENT_WORKSPACE_TEXT_SEARCH_COMPLETED"),
                Map.entry("auditId", "text-search:sha256:abcdef123456"),
                Map.entry("toolCode", "workspace.text.search"),
                Map.entry("targetService", "python-ai-runtime-text-search"),
                Map.entry("workerReceiptMode", "READ_ONLY_TEXT_SEARCH_SUMMARY"),
                Map.entry("message", "Read-only text search completed."),
                Map.entry("recommendedActions", List.of(
                        "Use a matched relative reference only when more context is required."
                )),
                Map.entry("idempotencyKey", "text-search:run-text-search-001:cmd-text-search-001:abcdef123456")
        );
    }

    /**
     * 从已解析 JSON 中读取一个必需的嵌套对象，并转换成字符串键 Map。
     */
    private Map<String, Object> nestedMap(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            throw new AssertionError(key + " should be a JSON object");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((entryKey, entryValue) -> copy.put(String.valueOf(entryKey), entryValue));
        return copy;
    }

    /**
     * 构造一个已通过治理并进入 durable outbox 的检索命令。
     */
    private AgentAsyncTaskCommandOutboxRecord record(String toolCode, String payloadJson) {
        return AgentAsyncTaskCommandOutboxRecord.pending(
                "cmd-text-search-001",
                "idem-text-search-001",
                "datasmart.agent.async-task-command.v1",
                "AGENT_WORKSPACE_TEXT_SEARCH_REQUESTED",
                "datasmart.agent.workspace-text-search.commands",
                WorkspaceTextSearchAgentAsyncTaskCommandDispatchTarget.CONSUMER_SERVICE,
                "session-text-search-001",
                "run-text-search-001",
                "audit-text-search-001",
                toolCode,
                WorkspaceTextSearchAgentAsyncTaskCommandDispatchTarget.CONSUMER_SERVICE,
                "/internal/agent/workspace-text/command-worker/run",
                10L,
                20L,
                30L,
                "1001",
                "trace-text-search-001",
                "agent-tool-audit://session-text-search-001/run-text-search-001/audit-text-search-001/plan-arguments",
                payloadJson,
                payloadJson.length(),
                Instant.now()
        );
    }

    /**
     * 返回最小合法模型参数；查询只存在于短生命周期 HTTP 请求中。
     */
    private String searchPayload() {
        return """
                {
                  "applicationId": "40",
                  "arguments": {
                    "query": "needle",
                    "relativePathPrefix": "docs",
                    "caseSensitive": false,
                    "searchMode": "LITERAL",
                    "maxResults": 5
                  }
                }
                """;
    }
}

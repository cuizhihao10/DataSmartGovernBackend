/**
 * @Author : Cui
 * @Date: 2026/07/03 17:12
 * @Description DataSmart Govern Backend - McpAgentAsyncTaskCommandDispatchTargetTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import com.czh.datasmart.govern.agent.config.AgentAsyncTaskCommandOutboxProperties;
import com.czh.datasmart.govern.agent.service.runtime.mcp.AgentMcpDurableWorkerCallResult;
import com.czh.datasmart.govern.agent.service.runtime.mcp.AgentMcpDurableWorkerClient;
import com.czh.datasmart.govern.agent.service.runtime.mcp.AgentMcpDurableWorkerRunRequest;
import com.czh.datasmart.govern.agent.service.runtime.mcp.AgentMcpDurableWorkerRunResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP command outbox target 测试。
 *
 * <p>这组测试保护的是 Java 控制面最容易出错的三个边界：路由不能广播错消费者；权限不能被传输层猜测；Python 调用失败
 * 必须抛回 dispatcher 进入既有退避重试，而不是把 outbox 错标为 PUBLISHED。</p>
 */
class McpAgentAsyncTaskCommandDispatchTargetTest {

    @Test
    void shouldOnlySupportMcpCommands() {
        McpAgentAsyncTaskCommandDispatchTarget target = target(request -> accepted());

        assertTrue(target.supports(record("mcp.enterprise.search", mcpPayload(true))));
        assertFalse(target.supports(record("data-sync.execute", "{}")));
    }

    @Test
    void shouldMapWhitelistedFactsAndArgumentsWithoutInventingPermission() {
        AgentAsyncTaskCommandOutboxProperties properties = new AgentAsyncTaskCommandOutboxProperties();
        properties.setDispatcherPreCheckEnabled(true);
        McpAgentAsyncTaskCommandDispatchTarget target = new McpAgentAsyncTaskCommandDispatchTarget(
                properties,
                request -> accepted(),
                new ObjectMapper()
        );

        AgentMcpDurableWorkerRunRequest request =
                target.toWorkerRequest(record("mcp.enterprise.search", mcpPayload(false)));

        assertEquals("enterprise", request.serverId());
        assertEquals("mcp.enterprise.search", request.internalToolName());
        assertEquals("governance catalog", request.arguments().get("query"));
        assertEquals("10", request.controlFacts().get("tenantId"));
        assertEquals("20", request.controlFacts().get("projectId"));
        assertEquals("30", request.controlFacts().get("workspaceKey"));
        assertEquals("READY", request.controlFacts().get("readinessDecision"));
        assertEquals("cmd-mcp-001", request.controlFacts().get("callId"));
        assertEquals(java.util.List.of("mcp.enterprise.search"),
                request.controlFacts().get("allowedInternalToolNames"));
        assertFalse(request.controlFacts().containsKey("permissionGranted"));
        assertFalse(request.controlFacts().containsKey("approvalVerified"));
        assertFalse(request.controlFacts().containsKey("untrustedExtraField"));
    }

    @Test
    void shouldDispatchAcceptedPythonWorkerResponse() {
        AtomicReference<AgentMcpDurableWorkerRunRequest> captured = new AtomicReference<>();
        McpAgentAsyncTaskCommandDispatchTarget target = target(request -> {
            captured.set(request);
            return accepted();
        });

        target.dispatch(record("mcp.enterprise.search", mcpPayload(true)));

        assertEquals("mcp.enterprise.search", captured.get().internalToolName());
        assertEquals(true, captured.get().controlFacts().get("permissionGranted"));
        assertEquals(true, captured.get().controlFacts().get("approvalVerified"));
    }

    @Test
    void shouldThrowWhenPythonWorkerDidNotAcceptCommand() {
        McpAgentAsyncTaskCommandDispatchTarget target = target(request ->
                AgentMcpDurableWorkerCallResult.failed(
                        503,
                        "MCP_DURABLE_WORKER_HTTP_STATUS_NOT_SUCCESSFUL",
                        "low-sensitive failure"
                )
        );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> target.dispatch(record("mcp.enterprise.search", mcpPayload(true)))
        );

        assertTrue(exception.getMessage().contains("MCP_DURABLE_WORKER_HTTP_STATUS_NOT_SUCCESSFUL"));
        assertFalse(exception.getMessage().contains("governance catalog"));
    }

    private McpAgentAsyncTaskCommandDispatchTarget target(AgentMcpDurableWorkerClient client) {
        AgentAsyncTaskCommandOutboxProperties properties = new AgentAsyncTaskCommandOutboxProperties();
        properties.setDispatcherPreCheckEnabled(true);
        return new McpAgentAsyncTaskCommandDispatchTarget(properties, client, new ObjectMapper());
    }

    private AgentMcpDurableWorkerCallResult accepted() {
        AgentMcpDurableWorkerRunResponse response = new AgentMcpDurableWorkerRunResponse(
                "datasmart.mcp-durable-worker-api.v1",
                true,
                Map.of("status", "SUCCEEDED"),
                Map.of("outcome", "EXECUTION_SUCCEEDED"),
                Map.of(),
                "MCP_ARGUMENTS_NEVER_RETURNED;TOOL_RESULT_BODY_RETURNED_ONLY_IF_FEEDBACK_ADAPTER_ALLOWED_SAFE_INLINE"
        );
        return AgentMcpDurableWorkerCallResult.accepted(200, response, "accepted");
    }

    private AgentAsyncTaskCommandOutboxRecord record(String toolCode, String payloadJson) {
        return AgentAsyncTaskCommandOutboxRecord.pending(
                "cmd-mcp-001",
                "idem-mcp-001",
                "datasmart.agent.async-task-command.v1",
                "AGENT_MCP_TOOL_CALL_REQUESTED",
                "datasmart.agent.mcp.commands",
                toolCode.startsWith("mcp.")
                        ? McpAgentAsyncTaskCommandDispatchTarget.MCP_CONSUMER_SERVICE
                        : "task-management",
                "session-mcp-001",
                "run-mcp-001",
                "audit-mcp-001",
                toolCode,
                toolCode.startsWith("mcp.")
                        ? McpAgentAsyncTaskCommandDispatchTarget.MCP_CONSUMER_SERVICE
                        : "data-sync",
                "/internal/agent/mcp/durable-worker/run",
                10L,
                20L,
                30L,
                "1001",
                "trace-mcp-001",
                "agent-tool-audit://session-mcp-001/run-mcp-001/audit-mcp-001/arguments",
                payloadJson,
                payloadJson.length(),
                Instant.now()
        );
    }

    private String mcpPayload(boolean includeAuthorizationFacts) {
        String authorizationFacts = includeAuthorizationFacts
                ? """
                  "permissionGranted": true,
                  "approvalVerified": true,
                  """
                : "";
        return """
                {
                  "serverId": "enterprise",
                  "internalToolName": "mcp.enterprise.search",
                  "arguments": {
                    "query": "governance catalog"
                  },
                  %s
                  "readinessDecision": "READY",
                  "untrustedExtraField": "must-not-enter-control-facts"
                }
                """.formatted(authorizationFacts);
    }
}

/**
 * @Author : Cui
 * @Date: 2026/07/03 19:40
 * @Description DataSmart Govern Backend - AgentMcpWorkerReceiptIngestionServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime.mcp;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandWorkerReceiptRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandWorkerReceiptResponse;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxRecord;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionCommandWorkerReceiptService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MCP receipt 白名单映射测试。
 *
 * <p>测试不重复验证通用 receipt service 的全部业务规则；那些规则已有独立测试。本测试只保证 Python Map 合同转换成 Java DTO
 * 时，lease fencing、执行状态、低敏 artifact 和幂等键不会丢失，同时 Python MCP 私有扩展字段不会污染通用 DTO。</p>
 */
class AgentMcpWorkerReceiptIngestionServiceTest {

    @Test
    void shouldMapPythonReceiptToExistingJavaReceiptService() {
        AgentToolActionCommandWorkerReceiptService receiptService =
                mock(AgentToolActionCommandWorkerReceiptService.class);
        when(receiptService.receive(
                eq("session-mcp-001"),
                eq("run-mcp-001"),
                eq("trace-mcp-001"),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(new AgentToolActionCommandWorkerReceiptResponse(
                true,
                false,
                "receipt-identity",
                "agent.tool_execution.command_worker_receipt_recorded",
                "EXECUTION_SUCCEEDED",
                true,
                "accepted"
        ));
        AgentMcpWorkerReceiptIngestionService service =
                new AgentMcpWorkerReceiptIngestionService(receiptService);

        AgentToolActionCommandWorkerReceiptResponse result = service.ingest(record(), response());

        ArgumentCaptor<AgentToolActionCommandWorkerReceiptRequest> captor =
                ArgumentCaptor.forClass(AgentToolActionCommandWorkerReceiptRequest.class);
        verify(receiptService).receive(
                eq("session-mcp-001"),
                eq("run-mcp-001"),
                eq("trace-mcp-001"),
                captor.capture()
        );
        AgentToolActionCommandWorkerReceiptRequest request = captor.getValue();
        assertEquals("cmd-mcp-001", request.commandId());
        assertEquals("EXECUTION_SUCCEEDED", request.outcome());
        assertTrue(request.preCheckPassed());
        assertTrue(request.sideEffectStarted());
        assertTrue(request.sideEffectExecuted());
        assertTrue(request.workerLeaseRequired());
        assertEquals("cmd-lease:1:0123456789abcdef", request.fencingToken());
        assertEquals(1L, request.workerLeaseVersion());
        assertEquals(4102444800000L, request.workerLeaseExpiresAtMs());
        assertEquals("agent-artifact:run-mcp-001/mcp.enterprise.search/mcp-result-abcdef", request.artifactReference());
        assertEquals("mcp-worker:run-mcp-001:cmd-mcp-001:EXECUTION_SUCCEEDED", request.idempotencyKey());
        assertTrue(result.accepted());
    }

    private AgentMcpDurableWorkerRunResponse response() {
        return new AgentMcpDurableWorkerRunResponse(
                "datasmart.mcp-durable-worker-api.v1",
                true,
                Map.of("status", "SUCCEEDED"),
                Map.of("outcome", "EXECUTION_SUCCEEDED"),
                Map.ofEntries(
                                Map.entry("commandId", "cmd-mcp-001"),
                                Map.entry("executorId", "python-mcp-durable-worker"),
                                Map.entry("tenantId", 10),
                                Map.entry("projectId", 20),
                                Map.entry("actorId", 1001),
                                Map.entry("taskStatus", "SUCCEEDED"),
                                Map.entry("outcome", "EXECUTION_SUCCEEDED"),
                                Map.entry("preCheckPassed", true),
                                Map.entry("sideEffectStarted", true),
                                Map.entry("sideEffectExecuted", true),
                                Map.entry("workerLeaseRequired", true),
                                Map.entry("fencingToken", "cmd-lease:1:0123456789abcdef"),
                                Map.entry("workerLeaseVersion", 1),
                                Map.entry("workerLeaseExpiresAtMs", 4102444800000L),
                                Map.entry("commandSafetyDecision", "ALLOW_CONTROLLED_EXECUTION"),
                                Map.entry("commandSafetyPolicyVersion", "mcp-admission-builder.v1"),
                                Map.entry("commandSafetyIssueCodes", List.of()),
                                Map.entry("normalizedTimeoutSeconds", 60),
                                Map.entry("normalizedOutputByteLimitBytes", 65536),
                                Map.entry("artifactReferenceType", "MCP_RESULT_SUMMARY"),
                                Map.entry("artifactReference",
                                        "agent-artifact:run-mcp-001/mcp.enterprise.search/mcp-result-abcdef"),
                                Map.entry("artifactAvailable", true),
                                Map.entry("errorCode", "MCP_TOOL_CALL_SUCCEEDED"),
                                Map.entry("auditId", "audit-mcp-001"),
                                Map.entry("toolCode", "mcp.enterprise.search"),
                                Map.entry("targetService", "python-ai-runtime-mcp-client"),
                                Map.entry("workerReceiptMode", "MCP_DURABLE_EXECUTION_RESULT"),
                                Map.entry("message", "MCP durable worker 已完成受控工具调用。"),
                                Map.entry("recommendedActions", List.of("回放 worker receipt")),
                                Map.entry("idempotencyKey",
                                        "mcp-worker:run-mcp-001:cmd-mcp-001:EXECUTION_SUCCEEDED"),
                                Map.entry("mcpResultSummary", Map.of("resultDigest", "abcdef"))
                ),
                Map.of(),
                "MCP_ARGUMENTS_NEVER_RETURNED"
        );
    }

    private AgentAsyncTaskCommandOutboxRecord record() {
        return AgentAsyncTaskCommandOutboxRecord.pending(
                "cmd-mcp-001",
                "idem-mcp-001",
                "datasmart.agent.async-task-command.v1",
                "AGENT_MCP_TOOL_CALL_REQUESTED",
                "datasmart.agent.mcp.commands",
                "python-ai-runtime-mcp-client",
                "session-mcp-001",
                "run-mcp-001",
                "audit-mcp-001",
                "mcp.enterprise.search",
                "python-ai-runtime-mcp-client",
                "/internal/agent/mcp/durable-worker/run",
                10L,
                20L,
                30L,
                "1001",
                "trace-mcp-001",
                "agent-tool-audit://session-mcp-001/run-mcp-001/audit-mcp-001/arguments",
                "{}",
                2,
                Instant.now()
        );
    }
}

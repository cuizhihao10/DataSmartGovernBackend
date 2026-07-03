/**
 * @Author : Cui
 * @Date: 2026/07/03 19:45
 * @Description DataSmart Govern Backend - AgentMcpCommandArgumentsResolverTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime.mcp;

import com.czh.datasmart.govern.agent.config.AgentMcpDurableWorkerClientProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolPlanArgumentsPayloadView;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxRecord;
import com.czh.datasmart.govern.agent.service.AgentToolPlanArgumentsPayloadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MCP 临执行参数解析器测试。
 *
 * <p>重点不是验证 Map 复制本身，而是保护安全边界：正式 command 不含 arguments 时必须从审计引用读取；
 * command 与审计任一隔离字段不一致都要 fail-closed；错误消息不能回显参数正文。</p>
 */
class AgentMcpCommandArgumentsResolverTest {

    @Test
    void shouldResolveArgumentsFromAuditReferenceAfterAllBoundariesMatch() {
        AgentToolPlanArgumentsPayloadService payloadService = mock(AgentToolPlanArgumentsPayloadService.class);
        when(payloadService.getPlanArgumentsPayload("session-mcp-001", "run-mcp-001", "audit-mcp-001"))
                .thenReturn(resolvedView("mcp.enterprise.search", 30L));
        AgentMcpCommandArgumentsResolver resolver = resolver(payloadService);

        Map<String, Object> arguments = resolver.resolve(
                record(),
                "mcp.enterprise.search",
                Map.of("argumentsResolutionMode", "AUDIT_REFERENCE_JUST_IN_TIME")
        );

        assertEquals("catalog-query", arguments.get("query"));
        assertFalse(record().payloadJson().contains("catalog-query"));
    }

    @Test
    void shouldRejectWorkspaceMismatchWithoutLeakingArgumentBody() {
        AgentToolPlanArgumentsPayloadService payloadService = mock(AgentToolPlanArgumentsPayloadService.class);
        when(payloadService.getPlanArgumentsPayload("session-mcp-001", "run-mcp-001", "audit-mcp-001"))
                .thenReturn(resolvedView("mcp.enterprise.search", 999L));
        AgentMcpCommandArgumentsResolver resolver = resolver(payloadService);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> resolver.resolve(record(), "mcp.enterprise.search", Map.of())
        );

        assertEquals("MCP command 与审计参数快照的 workspaceId 不一致", exception.getMessage());
        assertFalse(exception.getMessage().contains("catalog-query"));
    }

    @Test
    void shouldRejectResolvedArgumentsOverConfiguredByteLimit() {
        AgentToolPlanArgumentsPayloadService payloadService = mock(AgentToolPlanArgumentsPayloadService.class);
        when(payloadService.getPlanArgumentsPayload("session-mcp-001", "run-mcp-001", "audit-mcp-001"))
                .thenReturn(resolvedView("mcp.enterprise.search", 30L));
        AgentMcpDurableWorkerClientProperties properties = new AgentMcpDurableWorkerClientProperties();
        properties.setMaxResolvedArgumentsBytes(8);
        AgentMcpCommandArgumentsResolver resolver =
                new AgentMcpCommandArgumentsResolver(payloadService, properties, new ObjectMapper());

        assertThrows(
                IllegalArgumentException.class,
                () -> resolver.resolve(record(), "mcp.enterprise.search", Map.of())
        );
    }

    private AgentMcpCommandArgumentsResolver resolver(AgentToolPlanArgumentsPayloadService payloadService) {
        return new AgentMcpCommandArgumentsResolver(
                payloadService,
                new AgentMcpDurableWorkerClientProperties(),
                new ObjectMapper()
        );
    }

    private AgentToolPlanArgumentsPayloadView resolvedView(String toolCode, Long workspaceId) {
        return new AgentToolPlanArgumentsPayloadView(
                "agent-tool-audit://session-mcp-001/run-mcp-001/audit-mcp-001/plan-arguments",
                "plan-arguments",
                "session-mcp-001",
                "run-mcp-001",
                "audit-mcp-001",
                toolCode,
                "python-ai-runtime-mcp-client",
                null,
                10L,
                20L,
                workspaceId,
                "1001",
                "trace-mcp-001",
                "ASYNC_TASK",
                "PLANNED",
                List.of("query"),
                List.of(),
                Map.of("query", "catalog-query"),
                Map.of(),
                Map.of(),
                LocalDateTime.now()
        );
    }

    private AgentAsyncTaskCommandOutboxRecord record() {
        String payload = """
                {
                  "internalToolName": "mcp.enterprise.search",
                  "argumentsResolutionMode": "AUDIT_REFERENCE_JUST_IN_TIME"
                }
                """;
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
                null,
                10L,
                20L,
                30L,
                "1001",
                "trace-mcp-001",
                "agent-tool-audit://session-mcp-001/run-mcp-001/audit-mcp-001/plan-arguments",
                payload,
                payload.length(),
                Instant.now()
        );
    }
}

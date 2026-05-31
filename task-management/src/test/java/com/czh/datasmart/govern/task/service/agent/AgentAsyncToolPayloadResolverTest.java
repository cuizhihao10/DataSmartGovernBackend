/**
 * @Author : Cui
 * @Date: 2026/05/31 18:16
 * @Description DataSmart Govern Backend - AgentAsyncToolPayloadResolverTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;
import com.czh.datasmart.govern.task.entity.Task;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent 异步工具载荷解析测试。
 *
 * <p>测试不调用真实 agent-runtime，而是 mock payload client。
 * 这样可以把 resolver 的职责收窄到“task.params 摘要与远端参数快照是否一致、大小限制是否生效”。</p>
 */
class AgentAsyncToolPayloadResolverTest {

    private AgentRuntimePayloadReferenceClient payloadReferenceClient;
    private AgentAsyncToolWorkerProperties properties;
    private ObjectMapper objectMapper;
    private AgentAsyncToolPayloadResolver resolver;

    @BeforeEach
    void setUp() {
        payloadReferenceClient = mock(AgentRuntimePayloadReferenceClient.class);
        properties = new AgentAsyncToolWorkerProperties();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        resolver = new AgentAsyncToolPayloadResolver(payloadReferenceClient, properties, objectMapper);
    }

    @Test
    void shouldResolvePayloadWhenTaskSummaryMatchesAgentRuntimeSnapshot() throws JsonProcessingException {
        Task task = task(params("data-sync.execute", List.of("credentialRef", "datasourceId")));
        when(payloadReferenceClient.fetchPlanArguments(any(AgentToolAuditPayloadReference.class), eq("trace-worker")))
                .thenReturn(payload("data-sync.execute", List.of("credentialRef", "datasourceId"),
                        Map.of("credentialRef", "secret://mysql-prod", "datasourceId", 1001L)));

        AgentAsyncToolResolvedPayload resolved = resolver.resolve(task, "trace-worker");

        assertEquals(9001L, resolved.taskId());
        assertEquals("aatc-worker-001", resolved.commandId());
        assertEquals("data-sync.execute", resolved.toolCode());
        assertEquals(List.of("credentialRef", "datasourceId"), resolved.argumentNames());
        assertEquals(List.of("credentialRef"), resolved.sensitiveArgumentNames());
        assertEquals(Boolean.TRUE, resolved.dryRunOnly());
        assertTrue(resolved.payloadBytes() > 0);
        assertEquals("secret://mysql-prod", resolved.planArguments().get("credentialRef"));
    }

    @Test
    void shouldRejectPayloadWhenToolCodeDoesNotMatchTaskSummary() throws JsonProcessingException {
        Task task = task(params("data-sync.execute", List.of("credentialRef", "datasourceId")));
        when(payloadReferenceClient.fetchPlanArguments(any(AgentToolAuditPayloadReference.class), eq("trace-worker")))
                .thenReturn(payload("datasource.metadata.read", List.of("credentialRef", "datasourceId"), Map.of()));

        assertThrows(IllegalStateException.class, () -> resolver.resolve(task, "trace-worker"));
    }

    @Test
    void shouldRejectOversizedResolvedArguments() throws JsonProcessingException {
        properties.setMaxResolvedPayloadBytes(16);
        Task task = task(params("data-sync.execute", List.of("script")));
        when(payloadReferenceClient.fetchPlanArguments(any(AgentToolAuditPayloadReference.class), eq("trace-worker")))
                .thenReturn(payload("data-sync.execute", List.of("script"),
                        Map.of("script", "select * from very_large_table where tenant_id = 10")));

        assertThrows(IllegalStateException.class, () -> resolver.resolve(task, "trace-worker"));
    }

    private Task task(String params) {
        Task task = new Task();
        task.setId(9001L);
        task.setType(AgentAsyncToolPayloadResolver.TASK_TYPE);
        task.setStatus("PENDING");
        task.setTenantId(10L);
        task.setProjectId(20L);
        task.setParams(params);
        return task;
    }

    private String params(String toolCode, List<String> argumentNames) throws JsonProcessingException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("schemaVersion", "datasmart.agent.async-task-command.v1");
        params.put("commandId", "aatc-worker-001");
        params.put("auditId", "atea-worker-001");
        params.put("sessionId", "session-worker-001");
        params.put("runId", "run-worker-001");
        params.put("toolCode", toolCode);
        params.put("targetService", "data-sync");
        params.put("targetEndpoint", "/sync-tasks");
        params.put("workspaceId", 30L);
        params.put("payloadReference", "agent-tool-audit://session-worker-001/run-worker-001/atea-worker-001/plan-arguments");
        params.put("argumentNames", argumentNames);
        params.put("sensitiveArgumentNames", argumentNames.contains("credentialRef") ? List.of("credentialRef") : List.of());
        return objectMapper.writeValueAsString(params);
    }

    private AgentRuntimePlanArgumentsPayloadResponse payload(String toolCode,
                                                             List<String> argumentNames,
                                                             Map<String, Object> planArguments) {
        return new AgentRuntimePlanArgumentsPayloadResponse(
                "agent-tool-audit://session-worker-001/run-worker-001/atea-worker-001/plan-arguments",
                "plan-arguments",
                "session-worker-001",
                "run-worker-001",
                "atea-worker-001",
                toolCode,
                "data-sync",
                "/sync-tasks",
                10L,
                20L,
                30L,
                "actor-worker",
                "trace-worker",
                "ASYNC_TASK",
                "PLANNED",
                argumentNames,
                argumentNames.contains("credentialRef") ? List.of("credentialRef") : List.of(),
                planArguments,
                Map.of("sensitiveFields", List.of("credentialRef")),
                Map.of(),
                LocalDateTime.now()
        );
    }
}

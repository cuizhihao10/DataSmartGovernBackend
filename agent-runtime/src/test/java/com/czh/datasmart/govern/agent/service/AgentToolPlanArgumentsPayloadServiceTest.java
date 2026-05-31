/**
 * @Author : Cui
 * @Date: 2026/05/31 18:16
 * @Description DataSmart Govern Backend - AgentToolPlanArgumentsPayloadServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolPlanArgumentsPayloadView;
import com.czh.datasmart.govern.agent.event.NoopAgentToolExecutionEventPublisher;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditMemoryStore;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 工具计划参数载荷解析测试。
 *
 * <p>该测试固定 4.51 的核心契约：payloadReference 能回读到同一条审计快照；
 * 返回值包含参数值但同时携带参数名和敏感参数名，便于 task-management worker 做二次校验与脱敏保护。</p>
 */
class AgentToolPlanArgumentsPayloadServiceTest {

    @Test
    void shouldResolvePlanArgumentsPayloadFromAuditSnapshot() {
        AgentToolExecutionAuditMemoryStore auditStore = new AgentToolExecutionAuditMemoryStore();
        AgentToolExecutionAuditService auditService = new AgentToolExecutionAuditService(
                auditStore,
                new NoopAgentToolExecutionEventPublisher()
        );
        auditStore.save(audit(
                Map.of("credentialRef", "secret://mysql-prod", "datasourceId", 1001L),
                Map.of("sensitiveFields", List.of("credentialRef"))
        ));
        AgentToolPlanArgumentsPayloadService service = new AgentToolPlanArgumentsPayloadService(auditService);

        AgentToolPlanArgumentsPayloadView payload = service.getPlanArgumentsPayload(
                "session-payload-001",
                "run-payload-001",
                "atea-payload-001"
        );

        assertEquals("agent-tool-audit://session-payload-001/run-payload-001/atea-payload-001/plan-arguments",
                payload.payloadReference());
        assertEquals("plan-arguments", payload.payloadKind());
        assertEquals(List.of("credentialRef", "datasourceId"), payload.argumentNames());
        assertEquals(List.of("credentialRef"), payload.sensitiveArgumentNames());
        assertEquals("secret://mysql-prod", payload.planArguments().get("credentialRef"));
        assertEquals("data-sync.execute", payload.toolCode());
        assertTrue(payload.resolvedAt() != null);
    }

    @Test
    void unsupportedPayloadKindShouldBeRejectedByServiceLayer() {
        AgentToolExecutionAuditService auditService = new AgentToolExecutionAuditService(
                new AgentToolExecutionAuditMemoryStore(),
                new NoopAgentToolExecutionEventPublisher()
        );
        AgentToolPlanArgumentsPayloadService service = new AgentToolPlanArgumentsPayloadService(auditService);

        assertThrows(PlatformBusinessException.class, () -> service.validatePayloadKind("raw-secret"));
    }

    private AgentToolExecutionAuditRecord audit(Map<String, Object> arguments,
                                                Map<String, Object> governanceHints) {
        return new AgentToolExecutionAuditRecord(
                "atea-payload-001",
                "session-payload-001",
                "run-payload-001",
                "binding-payload-001",
                "data-sync.execute",
                "INTERNAL_API",
                "data-sync",
                "/sync-tasks",
                1001L,
                10L,
                20L,
                30L,
                "actor-payload",
                AgentToolRiskLevel.MEDIUM.name(),
                AgentToolExecutionMode.ASYNC_TASK.name(),
                false,
                true,
                true,
                List.of("CREATE_TASK"),
                "用于测试 payloadReference resolver",
                arguments,
                governanceHints,
                Map.of(),
                AgentToolExecutionState.PLANNED,
                "trace-payload",
                "工具计划已生成。",
                LocalDateTime.now()
        );
    }
}

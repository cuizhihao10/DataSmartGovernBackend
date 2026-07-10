/**
 * @Author : Cui
 * @Date: 2026/07/10 00:00
 * @Description DataSmart Govern Backend - AgentToolExecutionFailureOutputTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionResultView;
import com.czh.datasmart.govern.agent.event.NoopAgentToolExecutionEventPublisher;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditMemoryStore;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.service.tool.AgentToolAdapter;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionGuard;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionOutcome;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionOutputStore;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class AgentToolExecutionFailureOutputTest {

    @Test
    void shouldPersistStructuredPrecheckFailureOutputForUserDiagnostics() {
        AgentToolExecutionAuditMemoryStore auditStore = new AgentToolExecutionAuditMemoryStore();
        AgentToolExecutionAuditService auditService = new AgentToolExecutionAuditService(
                auditStore,
                new NoopAgentToolExecutionEventPublisher()
        );
        AgentToolExecutionOutputStore outputStore = new AgentToolExecutionOutputStore();
        AgentToolAdapter adapter = new AgentToolAdapter() {
            @Override
            public boolean supports(String toolCode) {
                return "sync.task.precheck".equals(toolCode);
            }

            @Override
            public AgentToolExecutionOutcome execute(com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionContext context) {
                return AgentToolExecutionOutcome.failed(
                        "SYNC_PRECHECK_BLOCKED",
                        "目标表缺少主键",
                        Map.of(
                                "canStartExecution", false,
                                "issueCodes", List.of("TARGET_PRIMARY_KEY_REQUIRED"),
                                "recommendedActions", List.of("为目标表增加主键或改用 INSERT 写入策略")
                        )
                );
            }
        };
        AgentToolExecutionService executionService = new AgentToolExecutionService(
                auditService,
                List.of(adapter),
                mock(AgentToolExecutionGuard.class),
                outputStore
        );
        AgentSessionRecord session = session();
        AgentRunRecord run = run(session);
        AgentToolExecutionAuditRecord audit = audit(session, run);
        auditStore.saveAll(List.of(audit));

        AgentToolExecutionResultView executed = executionService.execute(
                session, run, audit.getAuditId(), "trace-precheck-failure");
        AgentToolExecutionResultView queried = executionService.getResult(
                session.getSessionId(), run.getRunId(), audit.getAuditId());

        assertEquals("FAILED", executed.audit().state());
        assertFalse((Boolean) queried.output().get("canStartExecution"));
        assertEquals(List.of("TARGET_PRIMARY_KEY_REQUIRED"), queried.output().get("issueCodes"));
    }

    private AgentSessionRecord session() {
        return new AgentSessionRecord(
                "session-precheck-failure", 10L, 101L, null, "1001",
                "ORDINARY_USER", "USER", "101:MANAGER",
                "WEB", "同步任务预检查", WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:101", LocalDateTime.now());
    }

    private AgentRunRecord run(AgentSessionRecord session) {
        return new AgentRunRecord(
                "run-precheck-failure", session.getSessionId(), AgentRunState.PLANNING,
                "AGENT_REASONING", "同步任务预检查", false, false,
                List.of(), Map.of(), LocalDateTime.now(), "正在执行预检查");
    }

    private AgentToolExecutionAuditRecord audit(AgentSessionRecord session, AgentRunRecord run) {
        return new AgentToolExecutionAuditRecord(
                "audit-precheck-failure", session.getSessionId(), run.getRunId(), "binding-precheck-failure",
                "sync.task.precheck", "DATA_SYNC", "data-sync", "/sync-templates/{id}/precheck", 88L,
                10L, 101L, null, "1001", "LOW", "SYNC", false, true, true,
                List.of("PRECHECK"), AgentToolExecutionState.PLANNED, "trace-precheck-failure",
                "等待执行预检查", LocalDateTime.now());
    }
}

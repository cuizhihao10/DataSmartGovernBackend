/**
 * @Author : Cui
 * @Date: 2026/07/10 00:00
 * @Description DataSmart Govern Backend - AgentRunConfirmedExecutionServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunConfirmedExecutionRequest;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionMemoryStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class AgentRunConfirmedExecutionServiceTest {

    private AgentRunConfirmedExecutionService service;

    @BeforeEach
    void setUp() {
        AgentSessionMemoryStore sessionStore = new AgentSessionMemoryStore();
        AgentSessionRecord session = new AgentSessionRecord(
                "session-confirm",
                10L,
                101L,
                null,
                "1001",
                "WEB",
                "创建同步任务",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:101",
                LocalDateTime.now()
        );
        session.addRun(new AgentRunRecord(
                "run-confirm",
                session.getSessionId(),
                AgentRunState.WAITING_HUMAN,
                "AGENT_REASONING",
                "创建同步任务",
                false,
                true,
                List.of(),
                Map.of(),
                LocalDateTime.now(),
                "等待用户确认"
        ));
        sessionStore.save(session);
        service = new AgentRunConfirmedExecutionService(
                sessionStore,
                mock(AgentSessionService.class),
                mock(AgentToolExecutionAuditService.class)
        );
    }

    @Test
    void shouldRejectDifferentActorBeforeApprovingAnyTool() {
        assertThrows(PlatformBusinessException.class, () -> service.confirmAndExecute(
                "session-confirm",
                "run-confirm",
                new AgentRunConfirmedExecutionRequest(true, "确认"),
                10L,
                101L,
                "1002",
                "ORDINARY_USER",
                "USER",
                "101:MANAGER",
                "trace-confirm"
        ));
    }

    @Test
    void shouldRejectExecutionWhenCurrentProjectRoleSnapshotIsMissing() {
        assertThrows(PlatformBusinessException.class, () -> service.confirmAndExecute(
                "session-confirm",
                "run-confirm",
                new AgentRunConfirmedExecutionRequest(true, "确认"),
                10L,
                101L,
                "1001",
                "ORDINARY_USER",
                "USER",
                "",
                "trace-confirm"
        ));
    }
}

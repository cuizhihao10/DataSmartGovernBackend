/**
 * @Author : Cui
 * @Date: 2026/07/10 00:00
 * @Description DataSmart Govern Backend - AgentRunConfirmedExecutionServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunConfirmedExecutionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentPostConfirmContinuationRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentPostConfirmContinuationView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionResultView;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.answer.DeterministicAgentExecutionResultAnswerGenerator;
import com.czh.datasmart.govern.agent.service.continuation.AgentPostConfirmContinuationClient;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunConfirmedExecutionServiceTest {

    private AgentRunConfirmedExecutionService service;
    private AgentSessionService sessionService;
    private AgentToolExecutionAuditService auditService;
    private AgentToolExecutionResultQueryService resultQueryService;
    private AgentPostConfirmContinuationClient continuationClient;

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
        sessionService = mock(AgentSessionService.class);
        auditService = mock(AgentToolExecutionAuditService.class);
        resultQueryService = mock(AgentToolExecutionResultQueryService.class);
        continuationClient = mock(AgentPostConfirmContinuationClient.class);
        service = new AgentRunConfirmedExecutionService(
                sessionStore,
                sessionService,
                auditService,
                resultQueryService,
                new DeterministicAgentExecutionResultAnswerGenerator(),
                continuationClient
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

    @Test
    void shouldResumePythonWithCompleteRunResultsAfterJavaBatchSucceeds() {
        AgentToolExecutionAuditView waiting = mock(AgentToolExecutionAuditView.class);
        AgentToolExecutionAuditView planned = mock(AgentToolExecutionAuditView.class);
        AgentToolExecutionAuditView succeededAudit = mock(AgentToolExecutionAuditView.class);
        when(waiting.auditId()).thenReturn("audit-1");
        when(waiting.toolCode()).thenReturn("datasource.source.catalog.search");
        when(waiting.state()).thenReturn("WAITING_APPROVAL");
        when(planned.auditId()).thenReturn("audit-1");
        when(planned.toolCode()).thenReturn("datasource.source.catalog.search");
        when(planned.state()).thenReturn("PLANNED");
        when(succeededAudit.auditId()).thenReturn("audit-1");
        when(succeededAudit.toolCode()).thenReturn("datasource.source.catalog.search");
        when(succeededAudit.state()).thenReturn("SUCCEEDED");
        AgentToolExecutionResultView executed = new AgentToolExecutionResultView(succeededAudit, Map.of("count", 1));
        AgentToolExecutionResultView completeSnapshot = new AgentToolExecutionResultView(
                succeededAudit, Map.of("count", 1, "source", "java-fact-store")
        );
        when(auditService.listByRun("session-confirm", "run-confirm"))
                .thenReturn(List.of(waiting), List.of(planned), List.of(succeededAudit));
        when(sessionService.executeToolExecution("session-confirm", "run-confirm", "audit-1", "trace-confirm"))
                .thenReturn(executed);
        when(resultQueryService.listRunToolExecutionResults("session-confirm", "run-confirm"))
                .thenReturn(List.of(completeSnapshot));
        when(continuationClient.continueAfterConfirmedTools(any(AgentPostConfirmContinuationRequest.class)))
                .thenReturn(new AgentPostConfirmContinuationView(
                        "datasmart.post-confirm-continuation.v1",
                        "WAITING_CONFIRMATION",
                        true,
                        "request-next",
                        "session-confirm",
                        "run-confirm",
                        "run-write",
                        true,
                        "WAITING_APPROVAL",
                        "只读检查已完成，写计划等待确认。",
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        "LOW_SENSITIVE_CONTINUATION_SUMMARY_ONLY",
                        null
                ));

        var response = service.confirmAndExecute(
                "session-confirm",
                "run-confirm",
                new AgentRunConfirmedExecutionRequest(true, "确认"),
                10L,
                101L,
                "1001",
                "ORDINARY_USER",
                "USER",
                "101:MANAGER",
                "trace-confirm"
        );

        assertEquals("SUCCEEDED", response.runState());
        assertEquals("WAITING_CONFIRMATION", response.continuation().status());
        assertEquals("只读检查已完成，写计划等待确认。", response.assistantReply());
        verify(continuationClient).continueAfterConfirmedTools(any(AgentPostConfirmContinuationRequest.class));
    }

    @Test
    void shouldSendFailedToolFactsToPythonForGovernedDiagnosis() {
        AgentToolExecutionAuditView waiting = mock(AgentToolExecutionAuditView.class);
        AgentToolExecutionAuditView planned = mock(AgentToolExecutionAuditView.class);
        AgentToolExecutionAuditView failedAudit = mock(AgentToolExecutionAuditView.class);
        when(waiting.auditId()).thenReturn("audit-failed");
        when(waiting.toolCode()).thenReturn("sync.task.draft.save");
        when(waiting.state()).thenReturn("WAITING_APPROVAL");
        when(planned.auditId()).thenReturn("audit-failed");
        when(planned.toolCode()).thenReturn("sync.task.draft.save");
        when(planned.state()).thenReturn("PLANNED");
        when(failedAudit.auditId()).thenReturn("audit-failed");
        when(failedAudit.toolCode()).thenReturn("sync.task.draft.save");
        when(failedAudit.state()).thenReturn("FAILED");
        when(failedAudit.errorCode()).thenReturn("SYNC_TOOL_VALIDATION_FAILED");
        when(failedAudit.message()).thenReturn("目标表 public.customer 不存在");
        AgentToolExecutionResultView failed = new AgentToolExecutionResultView(
                failedAudit,
                Map.of(
                        "issues", List.of("目标表 public.customer 不存在"),
                        "recommendedActions", List.of("重新读取目标端元数据并选择实际存在的表")
                )
        );
        when(auditService.listByRun("session-confirm", "run-confirm"))
                .thenReturn(List.of(waiting), List.of(planned), List.of(failedAudit));
        when(sessionService.executeToolExecution(
                "session-confirm", "run-confirm", "audit-failed", "trace-confirm"))
                .thenReturn(failed);
        when(resultQueryService.listRunToolExecutionResults("session-confirm", "run-confirm"))
                .thenReturn(List.of(failed));
        when(continuationClient.continueAfterConfirmedTools(any(AgentPostConfirmContinuationRequest.class)))
                .thenReturn(new AgentPostConfirmContinuationView(
                        "datasmart.post-confirm-continuation.v1",
                        "WAITING_CONFIRMATION",
                        true,
                        "request-diagnosis",
                        "session-confirm",
                        "run-confirm",
                        "run-repair",
                        true,
                        "WAITING_APPROVAL",
                        "已确认目标表不存在，建议重新选择目标表。",
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        "LOW_SENSITIVE_CONTINUATION_SUMMARY_ONLY",
                        null
                ));

        var response = service.confirmAndExecute(
                "session-confirm",
                "run-confirm",
                new AgentRunConfirmedExecutionRequest(true, "确认"),
                10L,
                101L,
                "1001",
                "ORDINARY_USER",
                "USER",
                "101:MANAGER",
                "trace-confirm"
        );

        assertEquals("FAILED", response.runState());
        assertEquals("WAITING_CONFIRMATION", response.continuation().status());
        assertEquals("目标表 public.customer 不存在", response.failures().getFirst().message());
        org.junit.jupiter.api.Assertions.assertTrue(response.assistantReply().contains("具体失败原因"));
        org.junit.jupiter.api.Assertions.assertTrue(response.assistantReply().contains("Agent 后续诊断"));
        var requestCaptor = forClass(AgentPostConfirmContinuationRequest.class);
        verify(continuationClient).continueAfterConfirmedTools(requestCaptor.capture());
        assertEquals("FAILED", requestCaptor.getValue().toolResults().getFirst().audit().state());
    }

    @Test
    void shouldCompleteAgentGoalWhenImmediateSyncTaskIsSubmitted() {
        AgentToolExecutionAuditView waiting = mock(AgentToolExecutionAuditView.class);
        AgentToolExecutionAuditView planned = mock(AgentToolExecutionAuditView.class);
        AgentToolExecutionAuditView succeededAudit = mock(AgentToolExecutionAuditView.class);
        when(waiting.auditId()).thenReturn("audit-run");
        when(waiting.toolCode()).thenReturn("sync.task.run");
        when(waiting.state()).thenReturn("WAITING_APPROVAL");
        when(planned.auditId()).thenReturn("audit-run");
        when(planned.toolCode()).thenReturn("sync.task.run");
        when(planned.state()).thenReturn("PLANNED");
        when(succeededAudit.auditId()).thenReturn("audit-run");
        when(succeededAudit.toolCode()).thenReturn("sync.task.run");
        when(succeededAudit.state()).thenReturn("SUCCEEDED");
        AgentToolExecutionResultView executed = new AgentToolExecutionResultView(
                succeededAudit, Map.of("taskId", 901L, "state", "QUEUED")
        );
        when(auditService.listByRun("session-confirm", "run-confirm"))
                .thenReturn(List.of(waiting), List.of(planned), List.of(succeededAudit));
        when(sessionService.executeToolExecution("session-confirm", "run-confirm", "audit-run", "trace-confirm"))
                .thenReturn(executed);

        var response = service.confirmAndExecute(
                "session-confirm",
                "run-confirm",
                new AgentRunConfirmedExecutionRequest(true, "确认"),
                10L,
                101L,
                "1001",
                "ORDINARY_USER",
                "USER",
                "101:MANAGER",
                "trace-confirm"
        );

        assertEquals("BUSINESS_GOAL_REACHED", response.continuation().status());
        assertEquals("RESERVED_NOT_INVOKED", response.modelProviderStatus());
        org.junit.jupiter.api.Assertions.assertTrue(response.assistantReply().contains("已提交真实 worker 执行链路"));
        verifyNoInteractions(resultQueryService, continuationClient);
    }
}

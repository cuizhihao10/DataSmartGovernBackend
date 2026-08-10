/**
 * @Author : Cui
 * @Date: 2026/07/10 00:00
 * @Description DataSmart Govern Backend - AgentRunConfirmedExecutionServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunConfirmedExecutionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunConfirmedExecutionResponse;
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
import com.czh.datasmart.govern.agent.specialist.SpecialistTurnFactService;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunConfirmedExecutionServiceTest {

    private AgentRunConfirmedExecutionService service;
    private AgentSessionService sessionService;
    private AgentToolExecutionAuditService auditService;
    private AgentToolExecutionResultQueryService resultQueryService;
    private AgentPostConfirmContinuationClient continuationClient;
    private SpecialistTurnFactService specialistTurnFactService;
    private AgentSessionMemoryStore sessionStore;
    private AgentSessionRecord session;

    @BeforeEach
    void setUp() {
        sessionStore = spy(new AgentSessionMemoryStore());
        session = new AgentSessionRecord(
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
        clearInvocations(sessionStore);
        sessionService = mock(AgentSessionService.class);
        auditService = mock(AgentToolExecutionAuditService.class);
        resultQueryService = mock(AgentToolExecutionResultQueryService.class);
        continuationClient = mock(AgentPostConfirmContinuationClient.class);
        specialistTurnFactService = mock(SpecialistTurnFactService.class);
        service = new AgentRunConfirmedExecutionService(
                sessionStore,
                sessionService,
                auditService,
                resultQueryService,
                new DeterministicAgentExecutionResultAnswerGenerator(),
                continuationClient,
                Optional.of(specialistTurnFactService)
        );
    }

    @Test
    void shouldRejectDifferentActorBeforeApprovingAnyTool() {
        assertThrows(PlatformBusinessException.class, () -> service.confirmAndExecute(
                "session-confirm",
                "run-confirm",
                new AgentRunConfirmedExecutionRequest(true, "确认"),
                10L,
                201L,
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
                201L,
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
                .thenAnswer(ignored -> {
                    addDurableContinuationRun("run-write");
                    return new AgentPostConfirmContinuationView(
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
                    );
                });

        var response = service.confirmAndExecute(
                "session-confirm",
                "run-confirm",
                new AgentRunConfirmedExecutionRequest(true, "确认"),
                10L,
                201L,
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
        org.junit.jupiter.api.Assertions.assertTrue(sessionStore.findById("session-confirm")
                .orElseThrow().getRuns().stream().anyMatch(run -> "run-write".equals(run.getRunId())));
        // 当前 Run 终态只允许执行一次整聚合保存；Python 回调创建下一 Run 后，助手回复必须走增量消息写入。
        // 如果这里再次出现第二次 save，JDBC replaceRuns() 就可能重新引入本次缺陷。
        verify(sessionStore, times(1)).save(any(AgentSessionRecord.class));
        verify(sessionStore).appendConversationMessage(
                org.mockito.ArgumentMatchers.eq("session-confirm"),
                any(com.czh.datasmart.govern.agent.service.session.AgentConversationMessageRecord.class));
        verify(continuationClient).continueAfterConfirmedTools(any(AgentPostConfirmContinuationRequest.class));
    }

    @Test
    void shouldRejectDuplicateConfirmationAfterRunBecomesTerminal() {
        stubConfirmedSuccessfulTool(
                "audit-idempotent-confirm",
                "datasource.source.catalog.search",
                Map.of("count", 1)
        );

        confirmCurrentRun();
        clearInvocations(sessionService, auditService, resultQueryService, continuationClient);

        assertThrows(PlatformBusinessException.class, this::confirmCurrentRun);
        verifyNoInteractions(sessionService, auditService, resultQueryService, continuationClient);
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
                .thenAnswer(ignored -> {
                    addDurableContinuationRun("run-repair");
                    return new AgentPostConfirmContinuationView(
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
                    );
                });

        var response = service.confirmAndExecute(
                "session-confirm",
                "run-confirm",
                new AgentRunConfirmedExecutionRequest(true, "确认"),
                10L,
                201L,
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
                succeededAudit, Map.of("taskId", 901L, "executionId", 1901L, "state", "QUEUED")
        );
        when(auditService.listByRun("session-confirm", "run-confirm"))
                .thenReturn(List.of(waiting), List.of(planned), List.of(succeededAudit));
        when(sessionService.executeToolExecution("session-confirm", "run-confirm", "audit-run", "trace-confirm"))
                .thenReturn(executed);
        when(resultQueryService.listRunToolExecutionResults("session-confirm", "run-confirm"))
                .thenReturn(List.of(executed));
        when(continuationClient.continueAfterConfirmedTools(any(AgentPostConfirmContinuationRequest.class)))
                .thenReturn(new AgentPostConfirmContinuationView(
                        "datasmart.post-confirm-continuation.v1",
                        "BUSINESS_GOAL_REACHED",
                        false,
                        "post-confirm-request",
                        "session-confirm",
                        "run-confirm",
                        null,
                        false,
                        "TASK_SUBMITTED_OR_SCHEDULED",
                        "同步任务已经创建并提交执行；提交后预检查与运行监控已完成复核。",
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of("status", "COMPLETED"),
                        Map.of(
                                "status", "EXECUTED",
                                "taskId", "901",
                                "executionId", "1901",
                                "executedRoles", List.of("PRECHECK_AGENT", "MONITOR_AGENT")
                        ),
                        "LOW_SENSITIVE_CONTINUATION_SUMMARY_ONLY",
                        null
                ));
        when(specialistTurnFactService.hasTerminalSuccessfulEvidenceForRoles(
                10L,
                201L,
                101L,
                "1001",
                "session-confirm",
                "run-confirm",
                session.getDelegation().getDelegationId(),
                Set.of("PRECHECK_AGENT", "MONITOR_AGENT")
        )).thenReturn(true);

        var response = service.confirmAndExecute(
                "session-confirm",
                "run-confirm",
                new AgentRunConfirmedExecutionRequest(true, "确认"),
                10L,
                201L,
                101L,
                "1001",
                "ORDINARY_USER",
                "USER",
                "101:MANAGER",
                "trace-confirm"
        );

        assertEquals("BUSINESS_GOAL_REACHED", response.continuation().status());
        assertEquals("RESERVED_NOT_INVOKED", response.modelProviderStatus());
        org.junit.jupiter.api.Assertions.assertTrue(response.assistantReply().contains("提交执行"));
        var requestCaptor = forClass(AgentPostConfirmContinuationRequest.class);
        verify(continuationClient).continueAfterConfirmedTools(requestCaptor.capture());
        assertEquals("201", requestCaptor.getValue().applicationId());
        assertEquals(session.getDelegation().getDelegationId(), requestCaptor.getValue().delegationId());
        assertEquals(1901L, requestCaptor.getValue().toolResults().getFirst().output().get("executionId"));
    }

    @Test
    void shouldNotExposeContinuationRunWhenPythonReferenceWasNotPersisted() {
        AgentToolExecutionAuditView waiting = mock(AgentToolExecutionAuditView.class);
        AgentToolExecutionAuditView planned = mock(AgentToolExecutionAuditView.class);
        AgentToolExecutionAuditView failedAudit = mock(AgentToolExecutionAuditView.class);
        when(waiting.auditId()).thenReturn("audit-missing-run");
        when(waiting.toolCode()).thenReturn("sync.task.draft.save");
        when(waiting.state()).thenReturn("WAITING_APPROVAL");
        when(planned.auditId()).thenReturn("audit-missing-run");
        when(planned.toolCode()).thenReturn("sync.task.draft.save");
        when(planned.state()).thenReturn("PLANNED");
        when(failedAudit.auditId()).thenReturn("audit-missing-run");
        when(failedAudit.toolCode()).thenReturn("sync.task.draft.save");
        when(failedAudit.state()).thenReturn("FAILED");
        when(failedAudit.errorCode()).thenReturn("DUPLICATE_TASK_NAME");
        when(failedAudit.message()).thenReturn("当前项目下已经存在同名同步任务");
        AgentToolExecutionResultView failed = new AgentToolExecutionResultView(
                failedAudit, Map.of("taskName", "Agent 创建的数据同步任务")
        );
        when(auditService.listByRun("session-confirm", "run-confirm"))
                .thenReturn(List.of(waiting), List.of(planned), List.of(failedAudit));
        when(sessionService.executeToolExecution(
                "session-confirm", "run-confirm", "audit-missing-run", "trace-confirm"))
                .thenReturn(failed);
        when(resultQueryService.listRunToolExecutionResults("session-confirm", "run-confirm"))
                .thenReturn(List.of(failed));
        when(continuationClient.continueAfterConfirmedTools(any(AgentPostConfirmContinuationRequest.class)))
                .thenReturn(new AgentPostConfirmContinuationView(
                        "datasmart.post-confirm-continuation.v1",
                        "WAITING_CONFIRMATION",
                        true,
                        "request-missing-run",
                        "session-confirm",
                        "run-confirm",
                        "run-never-persisted",
                        true,
                        "WAITING_APPROVAL",
                        "建议修改任务名称后重试。",
                        Map.of(),
                        Map.of(),
                        Map.of(
                                "kind", "DUPLICATE_TASK_NAME",
                                "proposedTaskName", "Agent 创建的数据同步任务_agent_1234"
                        ),
                        "LOW_SENSITIVE_CONTINUATION_SUMMARY_ONLY",
                        null
                ));

        var response = service.confirmAndExecute(
                "session-confirm",
                "run-confirm",
                new AgentRunConfirmedExecutionRequest(true, "确认"),
                10L,
                201L,
                101L,
                "1001",
                "ORDINARY_USER",
                "USER",
                "101:MANAGER",
                "trace-confirm"
        );

        assertEquals("FAILED_RETRYABLE", response.continuation().status());
        assertEquals(null, response.continuation().nextRunId());
        assertEquals("NEXT_RUN_NOT_DURABLE", response.continuation().stoppedReason());
        assertEquals("DUPLICATE_TASK_NAME", response.continuation().repairProposal().get("kind"));
    }

    /**
     * 远端不能仅凭一个 {@code status=EXECUTED} 就宣布任务完成；两类后置专业 Agent 都必须留下执行证据。
     *
     * <p>测试刻意保留 Java 本地的 {@code sync.task.run} 成功事实，证明失败来自 continuation 契约而不是
     * 工具执行本身。这样未来即使 Python 返回 2xx，少了 MONITOR_AGENT 也不会让前端把任务显示为已完成。</p>
     */
    @Test
    void shouldRejectBusinessGoalWithoutBothPostBridgeSpecialistRoles() {
        stubConfirmedSuccessfulTool(
                "audit-post-bridge-missing-role",
                "sync.task.run",
                Map.of("taskId", 901L, "executionId", 1901L, "state", "QUEUED")
        );
        when(continuationClient.continueAfterConfirmedTools(any(AgentPostConfirmContinuationRequest.class)))
                .thenReturn(businessGoalContinuation(Map.of(
                        "status", "EXECUTED",
                        "executedRoles", List.of("PRECHECK_AGENT")
                )));

        var response = confirmCurrentRun();

        assertEquals("SUCCEEDED", response.runState());
        assertEquals("FAILED_RETRYABLE", response.continuation().status());
        assertEquals("CONTINUATION_CONTRACT_INVALID", response.continuation().stoppedReason());
        assertEquals(null, response.continuation().nextRunId());
        org.junit.jupiter.api.Assertions.assertTrue(response.assistantReply().contains("契约校验"));
    }

    /**
     * continuation 只能代表当前请求的 session/run，不能利用一个看似合法的 nextRunId 把浏览器引到别的会话。
     */
    @Test
    void shouldRejectContinuationWithDifferentSessionBeforeCheckingNextRunDurability() {
        stubConfirmedSuccessfulTool(
                "audit-continuation-scope",
                "datasource.source.catalog.search",
                Map.of("count", 1)
        );
        when(continuationClient.continueAfterConfirmedTools(any(AgentPostConfirmContinuationRequest.class)))
                .thenReturn(new AgentPostConfirmContinuationView(
                        "datasmart.post-confirm-continuation.v1",
                        "WAITING_CONFIRMATION",
                        true,
                        "request-wrong-session",
                        "another-session",
                        "run-confirm",
                        "run-from-another-session",
                        true,
                        "WAITING_APPROVAL",
                        "等待确认。",
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        "LOW_SENSITIVE_CONTINUATION_SUMMARY_ONLY",
                        null
                ));

        var response = confirmCurrentRun();

        assertEquals("FAILED_RETRYABLE", response.continuation().status());
        assertEquals("CONTINUATION_CONTRACT_INVALID", response.continuation().stoppedReason());
        assertEquals(null, response.continuation().nextRunId());
    }

    /**
     * 即使 Python 给出完整的 PRECHECK/MONITOR 角色列表，也不能跳过 Java 已记录的任务发布/提交边界。
     */
    @Test
    void shouldRejectBusinessGoalWithoutLocalTaskSubmissionBoundary() {
        stubConfirmedSuccessfulTool(
                "audit-no-submission",
                "sync.task.draft.save",
                Map.of("taskId", 901L, "state", "DRAFT")
        );
        when(continuationClient.continueAfterConfirmedTools(any(AgentPostConfirmContinuationRequest.class)))
                .thenReturn(businessGoalContinuation(Map.of(
                        "status", "EXECUTED",
                        "executedRoles", List.of("PRECHECK_AGENT", "MONITOR_AGENT")
                )));

        var response = confirmCurrentRun();

        assertEquals("FAILED_RETRYABLE", response.continuation().status());
        assertEquals("CONTINUATION_CONTRACT_INVALID", response.continuation().stoppedReason());
        assertEquals(null, response.continuation().nextRunId());
    }

    /**
     * 为后确认契约测试准备一条已批准、已执行的单工具批次。
     *
     * <p>该 helper 严格模拟真实服务的三次审计读取顺序：等待审批、可执行、最终成功。结果查询返回同一条
     * 已落库工具输出，因此 continuation 看到的是 Java 事实，而不是测试直接拼接的远端输入。</p>
     */
    private void stubConfirmedSuccessfulTool(
            String auditId,
            String toolCode,
            Map<String, Object> output) {
        AgentToolExecutionAuditView waiting = mock(AgentToolExecutionAuditView.class);
        AgentToolExecutionAuditView planned = mock(AgentToolExecutionAuditView.class);
        AgentToolExecutionAuditView succeededAudit = mock(AgentToolExecutionAuditView.class);
        when(waiting.auditId()).thenReturn(auditId);
        when(waiting.toolCode()).thenReturn(toolCode);
        when(waiting.state()).thenReturn("WAITING_APPROVAL");
        when(planned.auditId()).thenReturn(auditId);
        when(planned.toolCode()).thenReturn(toolCode);
        when(planned.state()).thenReturn("PLANNED");
        when(succeededAudit.auditId()).thenReturn(auditId);
        when(succeededAudit.toolCode()).thenReturn(toolCode);
        when(succeededAudit.state()).thenReturn("SUCCEEDED");
        AgentToolExecutionResultView executed = new AgentToolExecutionResultView(succeededAudit, output);
        when(auditService.listByRun("session-confirm", "run-confirm"))
                .thenReturn(List.of(waiting), List.of(planned), List.of(succeededAudit));
        when(sessionService.executeToolExecution("session-confirm", "run-confirm", auditId, "trace-confirm"))
                .thenReturn(executed);
        when(resultQueryService.listRunToolExecutionResults("session-confirm", "run-confirm"))
                .thenReturn(List.of(executed));
    }

    /** 构造满足 Python 协议形状的业务完成响应，调用方只需提供不同的后置复核证据。 */
    private AgentPostConfirmContinuationView businessGoalContinuation(Map<String, Object> postBridgeVerification) {
        return new AgentPostConfirmContinuationView(
                "datasmart.post-confirm-continuation.v1",
                "BUSINESS_GOAL_REACHED",
                false,
                "post-confirm-request",
                "session-confirm",
                "run-confirm",
                null,
                false,
                "TASK_SUBMITTED_OR_SCHEDULED",
                "同步任务已经创建并提交执行。",
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of("status", "COMPLETED"),
                postBridgeVerification,
                "LOW_SENSITIVE_CONTINUATION_SUMMARY_ONLY",
                null
        );
    }

    /** 以当前测试中的合法委托身份确认源 Run。 */
    private AgentRunConfirmedExecutionResponse confirmCurrentRun() {
        return service.confirmAndExecute(
                "session-confirm",
                "run-confirm",
                new AgentRunConfirmedExecutionRequest(true, "确认"),
                10L,
                201L,
                101L,
                "1001",
                "ORDINARY_USER",
                "USER",
                "101:MANAGER",
                "trace-confirm"
        );
    }

    /**
     * 模拟 Python continuation 通过 plan ingestion 回调 Java、并在原确认请求返回前创建下一 Durable Run。
     *
     * <p>测试使用 memory store 是为了把关注点放在服务调用顺序上：下一 Run 在远程回调期间出现，随后助手消息
     * 只能增量追加。JDBC 增量 SQL 是否隔离 {@code agent_run} 由专门的 Store 测试保护。</p>
     */
    private void addDurableContinuationRun(String runId) {
        sessionStore.findById("session-confirm").orElseThrow().addRun(new AgentRunRecord(
                runId,
                "session-confirm",
                AgentRunState.WAITING_HUMAN,
                "AGENT_REASONING",
                "继续执行同步任务",
                false,
                true,
                List.of(),
                Map.of(),
                LocalDateTime.now(),
                "等待用户确认"
        ));
    }
}

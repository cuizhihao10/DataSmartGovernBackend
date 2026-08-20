/**
 * @Author : Cui
 * @Date: 2026/07/10 00:00
 * @Description DataSmart Govern Backend - AgentRunConfirmedExecutionServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunConfirmedExecutionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunConfirmedExecutionResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentAutopilotPolicyRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentPostConfirmContinuationRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentPostConfirmContinuationView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionResultView;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.answer.DeterministicAgentExecutionResultAnswerGenerator;
import com.czh.datasmart.govern.agent.service.autopilot.AgentAutopilotAuthorizationService;
import com.czh.datasmart.govern.agent.service.continuation.AgentPostConfirmContinuationClient;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionMemoryStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.specialist.SpecialistTurnFactService;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
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
    private OffsetDateTime autopilotExpiresAt;

    @BeforeEach
    void setUp() {
        // The value stays identical across retries inside one test, while remaining safely outside the five-minute
        // authorization guard no matter what wall-clock time the suite starts.
        autopilotExpiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).withNano(0);
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
                Optional.of(specialistTurnFactService),
                new AgentAutopilotAuthorizationService(),
                new ObjectMapper().findAndRegisterModules()
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
        assertTrue(session.getRuns().getFirst().getVariables().containsKey("confirmedExecutionClaim"));
        org.junit.jupiter.api.Assertions.assertTrue(sessionStore.findById("session-confirm")
                .orElseThrow().getRuns().stream().anyMatch(run -> "run-write".equals(run.getRunId())));
        // Current Run convergence and the assistant reply both use narrow writes. No post-confirm aggregate save is
        // allowed, because JDBC replaceRuns() could delete the continuation Run created during the Python callback.
        verify(sessionStore).updateRunLifecycle(
                org.mockito.ArgumentMatchers.eq("session-confirm"),
                org.mockito.ArgumentMatchers.argThat(run -> run != null
                        && "run-confirm".equals(run.getRunId())
                        && run.getState() == AgentRunState.SUCCEEDED));
        verify(sessionStore, never()).save(any(AgentSessionRecord.class));
        verify(sessionStore).appendConversationMessage(
                org.mockito.ArgumentMatchers.eq("session-confirm"),
                any(com.czh.datasmart.govern.agent.service.session.AgentConversationMessageRecord.class));
        verify(continuationClient).continueAfterConfirmedTools(any(AgentPostConfirmContinuationRequest.class));
    }

    /**
     * Older callers may omit a client retry key, but they must still cross one durable Run claim before approval.
     * The server-derived key is stored only as a digest and does not enable response replay; its purpose is to stop
     * two Runtime instances from both reaching the same tool side-effect boundary.
     */
    @Test
    void shouldClaimOrdinaryConfirmationWithoutClientIdempotencyKeyBeforeToolSideEffects() {
        AgentToolExecutionAuditView waiting = durableAudit(
                "run-confirm", "audit-server-claim", "WAITING_APPROVAL", null, null);
        when(auditService.listByRun("session-confirm", "run-confirm")).thenReturn(List.of(waiting));
        org.mockito.Mockito.doThrow(new PlatformBusinessException(
                        com.czh.datasmart.govern.common.error.PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                        "approval stopped after claim"))
                .when(sessionService).approveToolExecution(
                        org.mockito.ArgumentMatchers.eq("session-confirm"),
                        org.mockito.ArgumentMatchers.eq("run-confirm"),
                        org.mockito.ArgumentMatchers.eq("audit-server-claim"),
                        any());

        assertThrows(PlatformBusinessException.class, () -> service.confirmAndExecute(
                "session-confirm", "run-confirm", new AgentRunConfirmedExecutionRequest(true, "确认"),
                10L, 201L, 101L, "1001", "ORDINARY_USER", "USER", "101:MANAGER", "trace-server-claim"));

        verify(sessionStore).putRunVariablesIfAbsent(
                org.mockito.ArgumentMatchers.eq("session-confirm"),
                org.mockito.ArgumentMatchers.eq("run-confirm"),
                org.mockito.ArgumentMatchers.eq("confirmedExecutionClaim"),
                org.mockito.ArgumentMatchers.argThat(values -> {
                    Object raw = values.get("confirmedExecutionClaim");
                    if (!(raw instanceof Map<?, ?> claim)) {
                        return false;
                    }
                    Object digest = claim.get("idempotencyKeyDigest");
                    return Boolean.FALSE.equals(claim.get("clientReplayEnabled"))
                            && digest instanceof String text
                            && text.startsWith("sha256:");
                }));
        verify(sessionStore).refreshDelegatedIdentity(
                "session-confirm", "ORDINARY_USER", "USER", "101:MANAGER");
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

    /**
     * HTTP response loss is the main real-world retry case for confirmation: the first request may have
     * committed tool execution and the Python continuation, while the browser never receives the 2xx body.
     * The second request must therefore read the receipt stored on the durable root Run, return the same
     * public result, and never approve a tool, execute a tool, issue a new AUTOPILOT grant, or call Python.
     */
    @Test
    void shouldReplayDurableConfirmationReceiptForSameIdempotencyKeyWithoutRepeatingSideEffects() throws Exception {
        stubIdempotentConfirmationTool();
        when(continuationClient.continueAfterConfirmedTools(any(AgentPostConfirmContinuationRequest.class)))
                .thenReturn(AgentPostConfirmContinuationView.disabled());
        AgentRunConfirmedExecutionRequest request = idempotentAutopilotRequest(2);

        AgentRunConfirmedExecutionResponse first = confirmWithRequest(request, "trace-confirm-first");
        clearInvocations(sessionStore, sessionService, auditService, resultQueryService, continuationClient);

        AgentRunConfirmedExecutionResponse replay = confirmWithRequest(request, "trace-confirm-retry");

        /*
         * receipt 只保存可公开回放的 JSON 形状。planArguments、governanceHints 和 approvalComment
         * 是服务器内部字段，第一次对象在 JVM 内仍可能有值，但两次 HTTP 序列化后的合同必须完全一致。
         */
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        assertEquals(mapper.writeValueAsString(first), mapper.writeValueAsString(replay));
        assertNotNull(replay.autopilotSnapshot());
        assertEquals("AUTOPILOT", replay.autopilotSnapshot().executionMode());
        assertEquals("run-confirm", replay.autopilotSnapshot().rootRunId());
        verify(sessionStore).findById("session-confirm");
        verify(sessionStore, never()).bindApplicationIdIfAbsent(any(), any());
        verify(sessionStore, never()).refreshDelegatedIdentity(any(), any(), any(), any());
        verify(sessionStore, never()).putRunVariablesIfAbsent(any(), any(), any(), any());
        verify(sessionStore, never()).putRunVariablesIfAbsentAndSessionVariableAbsent(
                any(), any(), any(), any(), any());
        verify(sessionStore, never()).updateRunLifecycle(any(), any());
        verifyNoInteractions(sessionService, auditService, resultQueryService, continuationClient);
    }

    /**
     * A retry key is bound to the actor, tenant/application/project scope, approval comment, and AUTOPILOT
     * policy facts. Reusing it with a different recovery budget must fail closed instead of treating the key
     * as permission to alter an authorization that was already committed by the first request.
     */
    @Test
    void shouldRejectIdempotencyKeyWhenConfirmationCriticalFactsChange() {
        stubIdempotentConfirmationTool();
        when(continuationClient.continueAfterConfirmedTools(any(AgentPostConfirmContinuationRequest.class)))
                .thenReturn(AgentPostConfirmContinuationView.disabled());
        confirmWithRequest(idempotentAutopilotRequest(2), "trace-confirm-first");
        clearInvocations(sessionStore, sessionService, auditService, resultQueryService, continuationClient);

        assertThrows(PlatformBusinessException.class,
                () -> confirmWithRequest(idempotentAutopilotRequest(3), "trace-confirm-retry"));

        verify(sessionStore).findById("session-confirm");
        verify(sessionStore, never()).bindApplicationIdIfAbsent(any(), any());
        verify(sessionStore, never()).refreshDelegatedIdentity(any(), any(), any(), any());
        verifyNoInteractions(sessionService, auditService, resultQueryService, continuationClient);
    }

    /**
     * A durable AUTOPILOT grant belongs to the original Run, but it must not prevent a later high-risk Run from
     * receiving its own explicit human confirmation.
     *
     * <p>The follow-up request deliberately carries an idempotency key and no {@code autopilotPolicy}. It may claim and
     * execute that new Run, while the original authorization remains the only grant in the session. This distinguishes
     * a legitimate later approval from an attempt to replace or broaden the first unattended-recovery boundary.</p>
     */
    @Test
    void shouldAllowLaterHumanConfirmationWithoutReplacingExistingAutopilotGrant() {
        stubIdempotentConfirmationTool();
        when(continuationClient.continueAfterConfirmedTools(any(AgentPostConfirmContinuationRequest.class)))
                .thenReturn(AgentPostConfirmContinuationView.disabled());
        confirmWithRequest(idempotentAutopilotRequest(2), "trace-confirm-first");

        session.addRun(new AgentRunRecord(
                "run-follow-up",
                session.getSessionId(),
                AgentRunState.WAITING_HUMAN,
                "AGENT_REASONING",
                "确认高风险后续动作",
                false,
                true,
                List.of(),
                Map.of(),
                LocalDateTime.now(),
                "等待用户确认"
        ));
        AgentToolExecutionAuditView waiting = durableAudit(
                "run-follow-up", "audit-follow-up", "WAITING_APPROVAL", null, null);
        AgentToolExecutionAuditView planned = durableAudit(
                "run-follow-up", "audit-follow-up", "PLANNED", "1001", "User confirmed follow-up");
        AgentToolExecutionAuditView succeeded = durableAudit(
                "run-follow-up", "audit-follow-up", "SUCCEEDED", "1001", "Follow-up completed");
        AgentToolExecutionResultView executed = new AgentToolExecutionResultView(
                succeeded, Map.of("state", "SUCCEEDED"));
        when(auditService.listByRun("session-confirm", "run-follow-up"))
                .thenReturn(List.of(waiting), List.of(planned), List.of(succeeded));
        when(sessionService.executeToolExecution(
                "session-confirm", "run-follow-up", "audit-follow-up", "trace-follow-up"))
                .thenReturn(executed);
        when(resultQueryService.listRunToolExecutionResults("session-confirm", "run-follow-up"))
                .thenReturn(List.of(executed));

        AgentRunConfirmedExecutionResponse response = service.confirmAndExecute(
                "session-confirm",
                "run-follow-up",
                new AgentRunConfirmedExecutionRequest(
                        true, "User confirmed follow-up", "follow-up-confirmation-key", null),
                10L,
                201L,
                101L,
                "1001",
                "ORDINARY_USER",
                "USER",
                "101:MANAGER",
                "trace-follow-up"
        );

        assertEquals("SUCCEEDED", response.runState());
        assertEquals(1L, session.getRuns().stream()
                .filter(run -> run.getVariables().containsKey("autopilotAuthorization"))
                .count());
    }

    /**
     * Verifies the store-level concurrency boundary used by two Agent Runtime instances that loaded the same
     * session before either one confirmed a different Run.
     *
     * <p>The memory store is intentionally used as a deterministic, fast regression harness. Its session monitor
     * represents the one durable serialization point that the JDBC implementation later provides with
     * {@code SELECT ... FOR UPDATE}. Both contenders begin together, receive different Run IDs and carry a full
     * AUTOPILOT authorization map. Exactly one may write the authorization, proving that a later service call
     * cannot reach its approval and tool side effects after losing the session-level claim.</p>
     */
    @Test
    void shouldAllowOnlyOneConcurrentRunToEstablishSessionAutopilotAuthorization() throws Exception {
        session.addRun(new AgentRunRecord(
                "run-concurrent", session.getSessionId(), AgentRunState.WAITING_HUMAN,
                "AGENT_REASONING", "Concurrent AUTOPILOT confirmation", false, true,
                List.of(), Map.of(), LocalDateTime.now(), "Waiting for confirmation"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> {
                start.await();
                return claimFirstAutopilotAuthorization("run-confirm");
            });
            Future<Boolean> second = executor.submit(() -> {
                start.await();
                return claimFirstAutopilotAuthorization("run-concurrent");
            });

            start.countDown();
            long winners = (first.get(5, TimeUnit.SECONDS) ? 1L : 0L)
                    + (second.get(5, TimeUnit.SECONDS) ? 1L : 0L);

            assertEquals(1L, winners);
            assertEquals(1L, session.getRuns().stream()
                    .filter(run -> run.getVariables().containsKey("autopilotAuthorization"))
                    .count());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Confirms that a later AUTOPILOT request loses before the confirmation service can approve or execute its
     * own tool plan when another Run has already established the session-wide authorization boundary.
     */
    @Test
    void shouldRejectCompetingAutopilotRunBeforeAnyToolSideEffect() {
        assertTrue(claimFirstAutopilotAuthorization("run-confirm"));
        session.addRun(new AgentRunRecord(
                "run-competing", session.getSessionId(), AgentRunState.WAITING_HUMAN,
                "AGENT_REASONING", "Competing AUTOPILOT confirmation", false, true,
                List.of(), Map.of(), LocalDateTime.now(), "Waiting for confirmation"));
        when(auditService.listByRun("session-confirm", "run-competing"))
                .thenReturn(List.of(durableAudit(
                        "run-competing", "audit-competing", "WAITING_APPROVAL", null, null)));

        assertThrows(PlatformBusinessException.class, () -> service.confirmAndExecute(
                "session-confirm", "run-competing", idempotentAutopilotRequest(2),
                10L, 201L, 101L, "1001", "ORDINARY_USER", "USER", "101:MANAGER", "trace-competing"));

        verifyNoInteractions(sessionService, resultQueryService, continuationClient);
    }

    /**
     * The pre-side-effect confirmation claim must never be followed by an aggregate replacement save.
     *
     * <p>The approval failure stops the method before the later tool-terminal snapshot save. We can therefore
     * isolate the initial persistence boundary and prove that it uses only the application binding plus atomic Run
     * variable claim. This protects continuation Runs and messages that another instance may have appended after
     * the current request loaded its session.</p>
     */
    @Test
    void shouldNotReplaceSessionAggregateAfterInitialAutopilotClaim() {
        AgentToolExecutionAuditView waiting = durableAudit(
                "run-confirm", "audit-claim", "WAITING_APPROVAL", null, null);
        when(auditService.listByRun("session-confirm", "run-confirm")).thenReturn(List.of(waiting));
        org.mockito.Mockito.doThrow(new PlatformBusinessException(
                        com.czh.datasmart.govern.common.error.PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                        "approval stopped for boundary test"))
                .when(sessionService).approveToolExecution(
                        org.mockito.ArgumentMatchers.eq("session-confirm"),
                        org.mockito.ArgumentMatchers.eq("run-confirm"),
                        org.mockito.ArgumentMatchers.eq("audit-claim"),
                        any());

        assertThrows(PlatformBusinessException.class, () -> service.confirmAndExecute(
                "session-confirm", "run-confirm", idempotentAutopilotRequest(2),
                10L, 201L, 101L, "1001", "ORDINARY_USER", "USER", "101:MANAGER", "trace-claim"));

        verify(sessionStore).bindApplicationIdIfAbsent("session-confirm", 201L);
        verify(sessionStore).putRunVariablesIfAbsentAndSessionVariableAbsent(
                org.mockito.ArgumentMatchers.eq("session-confirm"),
                org.mockito.ArgumentMatchers.eq("run-confirm"),
                org.mockito.ArgumentMatchers.eq("confirmedExecutionClaim"),
                org.mockito.ArgumentMatchers.eq("autopilotAuthorization"),
                any());
        verify(sessionStore, never()).save(any());
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
        verify(sessionStore).refreshDelegatedIdentity(
                "session-confirm", "ORDINARY_USER", "USER", "101:MANAGER");
        verify(sessionStore).updateRunLifecycle(
                org.mockito.ArgumentMatchers.eq("session-confirm"),
                org.mockito.ArgumentMatchers.argThat(run -> run != null
                        && "run-confirm".equals(run.getRunId())
                        && run.getState() == AgentRunState.FAILED));
        verify(sessionStore, never()).save(any());
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
    /**
     * Prepares concrete record DTOs for the durable idempotency tests.
     *
     * <p>The ordinary execution tests use Mockito audits because they only exercise state transitions. The
     * confirmation receipt, however, is serialized into {@code agent_run.variables} by the production JDBC
     * store and restored as JSON. Using real records here proves that the replay payload has an ordinary
     * persistence shape rather than accidentally depending on a JVM-only mock instance.</p>
     */
    private void stubIdempotentConfirmationTool() {
        AgentToolExecutionAuditView waiting = durableAudit("WAITING_APPROVAL", null, null);
        AgentToolExecutionAuditView planned = durableAudit("PLANNED", "1001", "User confirmed this plan");
        AgentToolExecutionAuditView succeeded = durableAudit("SUCCEEDED", "1001", "Tool execution completed");
        AgentToolExecutionResultView executed = new AgentToolExecutionResultView(
                succeeded,
                Map.of("taskId", 901L, "executionId", 1901L, "state", "QUEUED")
        );
        when(auditService.listByRun("session-confirm", "run-confirm"))
                .thenReturn(List.of(waiting), List.of(planned), List.of(succeeded));
        when(sessionService.executeToolExecution(
                "session-confirm", "run-confirm", "audit-idempotency-replay", "trace-confirm-first"))
                .thenReturn(executed);
        when(resultQueryService.listRunToolExecutionResults("session-confirm", "run-confirm"))
                .thenReturn(List.of(executed));
    }

    /**
     * Creates the public audit projection stored inside a confirmation receipt.
     *
     * <p>The fixture deliberately contains only execution identity, state and low-sensitive task locators.
     * It has no prompt, SQL, token accounting or log body, so a successful JSON round trip is also a direct
     * regression check for the intended replay contract.</p>
     */
    private AgentToolExecutionAuditView durableAudit(
            String state,
            String approvalOperatorId,
            String message) {
        return durableAudit("run-confirm", "audit-idempotency-replay", state, approvalOperatorId, message);
    }

    /**
     * Creates a persistence-shaped audit for an arbitrary follow-up Run without relying on Mockito serialization.
     */
    private AgentToolExecutionAuditView durableAudit(
            String runId,
            String auditId,
            String state,
            String approvalOperatorId,
            String message) {
        LocalDateTime timestamp = LocalDateTime.of(2026, 8, 11, 12, 0);
        return new AgentToolExecutionAuditView(
                auditId,
                "session-confirm",
                runId,
                "binding-" + auditId,
                "sync.task.run",
                "HTTP",
                "data-sync",
                "/api/sync/tasks/:value/run",
                901L,
                10L,
                101L,
                null,
                "1001",
                "LOW",
                "HUMAN_APPROVAL",
                true,
                false,
                true,
                List.of("RUN"),
                "Create and submit an approved sync task",
                Map.of(),
                Map.of(),
                Map.of(),
                state,
                "trace-confirm-first",
                message,
                approvalOperatorId,
                approvalOperatorId == null ? null : "User confirmed this plan",
                approvalOperatorId == null ? null : timestamp,
                "SUCCEEDED".equals(state) ? timestamp : null,
                "SUCCEEDED".equals(state) ? timestamp.plusSeconds(1) : null,
                "SUCCEEDED".equals(state) ? "Sync task submitted" : null,
                null,
                timestamp,
                timestamp
        );
    }

    /**
     * Builds one stable user confirmation request for retry tests.
     *
     * <p>The expiration is fixed instead of using {@code now()} so a second call differs only when the test
     * intentionally changes a critical AUTOPILOT fact. The service should ignore the different trace ID on
     * transport retries, but it must bind the key to this recovery budget.</p>
     */
    private AgentRunConfirmedExecutionRequest idempotentAutopilotRequest(int maxRecoveryCycles) {
        return new AgentRunConfirmedExecutionRequest(
                true,
                "User confirmed this plan",
                "confirm-idempotency-key",
                new AgentAutopilotPolicyRequest(
                        "AUTOPILOT",
                        maxRecoveryCycles,
                        30,
                        "LOW",
                        List.of("RETRY_EXECUTION"),
                        List.of("CHANGE_SCHEMA"),
                        autopilotExpiresAt
                )
        );
    }

    /**
     * Builds the minimal immutable fact set used to exercise the store's session-wide authorization primitive.
     *
     * <p>The service normally obtains the authorization map from {@link AgentAutopilotAuthorizationService}; this
     * helper keeps the concurrency test focused on the store contract by supplying only the two keys the store
     * must protect. No real authorization policy is evaluated and no tool plan is executed here.</p>
     */
    private boolean claimFirstAutopilotAuthorization(String runId) {
        return sessionStore.putRunVariablesIfAbsentAndSessionVariableAbsent(
                "session-confirm",
                runId,
                "confirmedExecutionClaim",
                "autopilotAuthorization",
                Map.of(
                        "confirmedExecutionClaim", Map.of("state", "IN_PROGRESS", "runId", runId),
                        "autopilotAuthorization", Map.of("executionMode", "AUTOPILOT", "rootRunId", runId)
                )
        );
    }

    /**
     * Calls the protected confirmation boundary with the same authenticated scope as the fixture session.
     * The trace value is intentionally an argument: a transport retry obtains a new trace and must still
     * replay the persisted receipt when all authorization and request facts are unchanged.
     */
    private AgentRunConfirmedExecutionResponse confirmWithRequest(
            AgentRunConfirmedExecutionRequest request,
            String traceId) {
        return service.confirmAndExecute(
                "session-confirm",
                "run-confirm",
                request,
                10L,
                201L,
                101L,
                "1001",
                "ORDINARY_USER",
                "USER",
                "101:MANAGER",
                traceId
        );
    }

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

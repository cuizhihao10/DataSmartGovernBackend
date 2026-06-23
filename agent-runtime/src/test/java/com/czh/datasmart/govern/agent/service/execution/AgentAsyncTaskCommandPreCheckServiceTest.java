/**
 * @Author : Cui
 * @Date: 2026/06/04 00:00
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandPreCheckServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.config.AgentToolRuntimeProtectionProperties;
import com.czh.datasmart.govern.agent.event.NoopAgentToolExecutionEventPublisher;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxRecord;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.model.AgentToolType;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.AgentToolExecutionAuditService;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditMemoryStore;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.execution.confirmation.AgentRunToolDagConfirmationRecord;
import com.czh.datasmart.govern.agent.service.execution.confirmation.AgentRunToolDagConfirmationStatus;
import com.czh.datasmart.govern.agent.service.execution.confirmation.InMemoryAgentRunToolDagConfirmationStore;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionMemoryStore;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.service.tool.sandbox.AgentToolSandboxPolicyService;
import com.czh.datasmart.govern.agent.service.tool.protection.AgentToolRuntimeProtectionLease;
import com.czh.datasmart.govern.agent.service.tool.protection.AgentToolRuntimeProtectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 异步命令执行前复核服务测试。
 *
 * <p>这些测试保护未来真实 DAG worker 的最后一道安全门：command outbox 已经形成，不代表 worker 可以无条件执行。
 * worker 执行前必须重新回查 confirmation、当前策略、沙箱和运行时保护，否则就可能把过期确认、策略漂移或熔断中的
 * 下游服务继续推向真实副作用。</p>
 */
class AgentAsyncTaskCommandPreCheckServiceTest {

    private static final String SESSION_ID = "session-precheck";
    private static final String RUN_ID = "run-precheck";
    private static final String AUDIT_ID = "audit-precheck";
    private static final String COMMAND_ID = "cmd-precheck";
    private static final String CONFIRMATION_ID = "confirmation-precheck";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 6, 4, 0, 0);

    @Test
    void confirmedAsyncCommandShouldPassPreCheck() {
        TestFixture fixture = newFixture(false);
        fixture.saveAudit(AgentToolExecutionState.PLANNED);
        fixture.saveConfirmation(Instant.now().plusSeconds(3600), List.of("policy-v1"));

        AgentAsyncTaskCommandPreCheckVerdict verdict = fixture.service.inspect(commandWithConfirmation(List.of("policy-v1")));

        assertTrue(verdict.allowed());
        assertEquals("ALLOW_EXECUTION", verdict.decision());
        assertEquals(AgentRunToolExecutionDecision.WAITING_ASYNC_EXECUTOR.name(), verdict.policyDecision());
        assertTrue(verdict.issueCodes().isEmpty());
        assertTrue(verdict.reasons().stream().anyMatch(reason -> reason.contains("复核通过")));
    }

    @Test
    void requiredCommandSafetyEvidenceShouldPassWhenLowSensitiveAllowVerdictIsPresent() {
        TestFixture fixture = newFixture(false);
        fixture.saveAudit(AgentToolExecutionState.PLANNED);
        fixture.saveConfirmation(Instant.now().plusSeconds(3600), List.of("policy-v1"));

        AgentAsyncTaskCommandPreCheckVerdict verdict = fixture.service.inspect(commandWithAllowSafetyEvidence());

        assertTrue(verdict.allowed());
        assertEquals("ALLOW_EXECUTION", verdict.decision());
        assertTrue(verdict.issueCodes().isEmpty());
        assertTrue(verdict.reasons().stream().anyMatch(reason -> reason.contains("命令安全预检证据通过")));
    }

    @Test
    void requiredCommandSafetyEvidenceMissingShouldFailClosed() {
        TestFixture fixture = newFixture(false);
        fixture.saveAudit(AgentToolExecutionState.PLANNED);
        fixture.saveConfirmation(Instant.now().plusSeconds(3600), List.of("policy-v1"));

        AgentAsyncTaskCommandPreCheckVerdict verdict = fixture.service.inspect(commandWithMissingRequiredSafetyEvidence());

        assertFalse(verdict.allowed());
        assertEquals("BLOCKED", verdict.decision());
        assertTrue(verdict.issueCodes().contains("COMMAND_SAFETY_PRECHECK_REQUIRED_MISSING"));
    }

    @Test
    void commandSafetyEvidenceWithRawCommandLineShouldFailClosed() {
        TestFixture fixture = newFixture(false);
        fixture.saveAudit(AgentToolExecutionState.PLANNED);
        fixture.saveConfirmation(Instant.now().plusSeconds(3600), List.of("policy-v1"));

        AgentAsyncTaskCommandPreCheckVerdict verdict = fixture.service.inspect(commandWithUnsafeSafetyEvidence());

        assertFalse(verdict.allowed());
        assertEquals("BLOCKED", verdict.decision());
        assertTrue(verdict.issueCodes().contains("COMMAND_SAFETY_PRECHECK_LOW_SENSITIVE_VIOLATION"));
        assertTrue(verdict.reasons().stream().anyMatch(reason -> reason.contains("commandLine")));
    }

    @Test
    void missingConfirmationShouldFailClosed() {
        TestFixture fixture = newFixture(false);
        fixture.saveAudit(AgentToolExecutionState.PLANNED);

        AgentAsyncTaskCommandPreCheckVerdict verdict = fixture.service.inspect(commandWithoutConfirmation());

        assertFalse(verdict.allowed());
        assertEquals("BLOCKED", verdict.decision());
        assertTrue(verdict.issueCodes().contains("CONFIRMATION_ID_MISSING"));
    }

    @Test
    void expiredConfirmationShouldBlockExecution() {
        TestFixture fixture = newFixture(false);
        fixture.saveAudit(AgentToolExecutionState.PLANNED);
        fixture.saveConfirmation(Instant.now().minusSeconds(1), List.of("policy-v1"));

        AgentAsyncTaskCommandPreCheckVerdict verdict = fixture.service.inspect(commandWithConfirmation(List.of("policy-v1")));

        assertFalse(verdict.allowed());
        assertEquals("BLOCKED", verdict.decision());
        assertTrue(verdict.issueCodes().contains("CONFIRMATION_EXPIRED"));
    }

    @Test
    void runtimeProtectionRejectedCommandShouldBeDeferred() {
        TestFixture fixture = newFixture(true);
        fixture.saveAudit(AgentToolExecutionState.PLANNED);
        fixture.saveConfirmation(Instant.now().plusSeconds(3600), List.of());
        AgentToolRuntimeProtectionLease lease = fixture.runtimeProtectionService.beginExecution(
                fixture.session,
                fixture.session.getRuns().getFirst(),
                fixture.auditStore.findById(AUDIT_ID).orElseThrow()
        );

        try {
            AgentAsyncTaskCommandPreCheckVerdict verdict = fixture.service.inspect(commandWithConfirmation(List.of()));

            assertFalse(verdict.allowed());
            assertEquals("DEFERRED", verdict.decision());
            assertTrue(verdict.issueCodes().contains("RUNTIME_PROTECTION_DEFERRED_BEFORE_WORKER"));
        } finally {
            lease.close();
        }
    }

    private TestFixture newFixture(boolean runtimeProtectionEnabled) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getToolServiceBaseUrls().put("datasource-management", "http://localhost:8082");
        properties.getToolRegistry().put("datasource.metadata.read", datasourceMetadataTool());
        AgentSessionMemoryStore sessionStore = new AgentSessionMemoryStore();
        AgentToolExecutionAuditMemoryStore auditStore = new AgentToolExecutionAuditMemoryStore();
        AgentToolExecutionAuditService auditService = new AgentToolExecutionAuditService(
                auditStore,
                new NoopAgentToolExecutionEventPublisher()
        );
        AgentToolRuntimeProtectionService runtimeProtectionService = runtimeProtectionEnabled
                ? new AgentToolRuntimeProtectionService(runtimeProtectionProperties())
                : AgentToolRuntimeProtectionService.disabledForTests();
        AgentRunToolExecutionPolicyService policyService = new AgentRunToolExecutionPolicyService(
                properties,
                sessionStore,
                auditService,
                new AgentToolSandboxPolicyService(properties),
                runtimeProtectionService
        );
        InMemoryAgentRunToolDagConfirmationStore confirmationStore =
                new InMemoryAgentRunToolDagConfirmationStore(20, 100);
        AgentAsyncTaskCommandPreCheckService service = new AgentAsyncTaskCommandPreCheckService(
                policyService,
                confirmationStore,
                new AgentCommandSafetyPrecheckEvidenceEvaluator(),
                new ObjectMapper()
        );
        AgentSessionRecord session = session();
        sessionStore.save(session);
        return new TestFixture(service, confirmationStore, auditStore, runtimeProtectionService, session);
    }

    private AgentToolRuntimeProtectionProperties runtimeProtectionProperties() {
        AgentToolRuntimeProtectionProperties properties = new AgentToolRuntimeProtectionProperties();
        properties.setEnabled(true);
        properties.setMaxGlobalInFlight(10);
        properties.setMaxTenantInFlight(10);
        properties.setMaxTargetServiceInFlight(1);
        return properties;
    }

    private AgentSessionRecord session() {
        AgentSessionRecord session = new AgentSessionRecord(
                SESSION_ID,
                10L,
                20L,
                30L,
                "actor-precheck",
                "PYTHON_AI_RUNTIME",
                "异步命令 pre-check 测试会话",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:20",
                BASE_TIME
        );
        session.addRun(new AgentRunRecord(
                RUN_ID,
                SESSION_ID,
                AgentRunState.PLANNING,
                "AGENT_REASONING",
                "测试 worker pre-check",
                true,
                false,
                List.of(),
                Map.of(),
                BASE_TIME,
                "Run 已创建"
        ));
        return session;
    }

    private static AgentToolExecutionAuditRecord audit(AgentToolExecutionState state) {
        return new AgentToolExecutionAuditRecord(
                AUDIT_ID,
                SESSION_ID,
                RUN_ID,
                "plan:" + RUN_ID + ":1",
                "datasource.metadata.read",
                "INTERNAL_API",
                "datasource-management",
                "/metadata",
                1001L,
                10L,
                20L,
                30L,
                "actor-precheck",
                AgentToolRiskLevel.LOW.name(),
                AgentToolExecutionMode.ASYNC_TASK.name(),
                false,
                true,
                true,
                List.of("READ"),
                "测试异步命令执行前复核",
                Map.of("datasourceId", 1001L),
                Map.of("planNodeId", "metadata-read"),
                Map.of(),
                state,
                "trace-precheck",
                "工具计划已生成",
                BASE_TIME
        );
    }

    private static AgentRunToolDagConfirmationRecord confirmation(Instant expiresAt, List<String> policyVersions) {
        return new AgentRunToolDagConfirmationRecord(
                CONFIRMATION_ID,
                SESSION_ID,
                RUN_ID,
                "fingerprint-precheck",
                List.of("metadata-read"),
                List.of(AUDIT_ID),
                policyVersions,
                List.of("delegation-precheck"),
                null,
                List.of("outbox-precheck"),
                List.of(COMMAND_ID),
                10L,
                20L,
                30L,
                "actor-precheck",
                "trace-precheck",
                true,
                AgentRunToolDagConfirmationStatus.CONFIRMED,
                expiresAt,
                Instant.now(),
                Instant.now()
        );
    }

    private AgentAsyncTaskCommandOutboxRecord commandWithConfirmation(List<String> policyVersions) {
        return command("""
                {
                  "confirmationId": "%s",
                  "policyVersions": %s
                }
                """.formatted(CONFIRMATION_ID, toJsonArray(policyVersions)));
    }

    private AgentAsyncTaskCommandOutboxRecord commandWithAllowSafetyEvidence() {
        return command("""
                {
                  "confirmationId": "%s",
                  "policyVersions": ["policy-v1"],
                  "commandSafetyPrecheckRequired": true,
                  "commandSafetyPrecheck": {
                    "required": true,
                    "decision": "ALLOW_CONTROLLED_EXECUTION",
                    "executable": true,
                    "requiresHumanApproval": false,
                    "blocked": false,
                    "riskLevel": "LOW",
                    "payloadPolicy": "LOW_SENSITIVE_COMMAND_SAFETY_PRECHECK_ONLY",
                    "policyVersion": "datasmart.agent-command-safety-precheck.v1",
                    "normalizedTimeoutSeconds": 30,
                    "normalizedOutputByteLimitBytes": 8192,
                    "issueCodes": [],
                    "reasonCodes": ["COMMAND_MATCHED_SAFE_ALLOWLIST"],
                    "pathCategories": ["WORKSPACE_ROOT_PRESENT", "WORKING_DIRECTORY_INSIDE_WORKSPACE"],
                    "commandLineReturned": false,
                    "pathValuesReturned": false,
                    "sideEffectExecuted": false
                  }
                }
                """.formatted(CONFIRMATION_ID));
    }

    private AgentAsyncTaskCommandOutboxRecord commandWithMissingRequiredSafetyEvidence() {
        return command("""
                {
                  "confirmationId": "%s",
                  "policyVersions": ["policy-v1"],
                  "commandSafetyPrecheckRequired": true
                }
                """.formatted(CONFIRMATION_ID));
    }

    private AgentAsyncTaskCommandOutboxRecord commandWithUnsafeSafetyEvidence() {
        return command("""
                {
                  "confirmationId": "%s",
                  "policyVersions": ["policy-v1"],
                  "commandSafetyPrecheckRequired": true,
                  "commandSafetyPrecheck": {
                    "required": true,
                    "decision": "ALLOW_CONTROLLED_EXECUTION",
                    "executable": true,
                    "requiresHumanApproval": false,
                    "blocked": false,
                    "payloadPolicy": "LOW_SENSITIVE_COMMAND_SAFETY_PRECHECK_ONLY",
                    "policyVersion": "datasmart.agent-command-safety-precheck.v1",
                    "normalizedTimeoutSeconds": 30,
                    "normalizedOutputByteLimitBytes": 8192,
                    "issueCodes": [],
                    "reasonCodes": ["COMMAND_MATCHED_SAFE_ALLOWLIST"],
                    "commandLine": "rm -rf /tmp/secret",
                    "commandLineReturned": false,
                    "pathValuesReturned": false,
                    "sideEffectExecuted": false
                  }
                }
                """.formatted(CONFIRMATION_ID));
    }

    private AgentAsyncTaskCommandOutboxRecord commandWithoutConfirmation() {
        return command("""
                {
                  "policyVersions": []
                }
                """);
    }

    private AgentAsyncTaskCommandOutboxRecord command(String payloadJson) {
        return AgentAsyncTaskCommandOutboxRecord.pending(
                COMMAND_ID,
                "agent-tool-async:" + SESSION_ID + ":" + RUN_ID + ":" + AUDIT_ID,
                "datasmart.agent.async-command.v1",
                "AGENT_TOOL_ASYNC_TASK_REQUESTED",
                "datasmart.agent.tool.async.commands",
                "task-management",
                SESSION_ID,
                RUN_ID,
                AUDIT_ID,
                "datasource.metadata.read",
                "datasource-management",
                "/metadata",
                10L,
                20L,
                30L,
                "actor-precheck",
                "trace-precheck",
                "agent-tool-audit://" + SESSION_ID + "/" + RUN_ID + "/" + AUDIT_ID + "/plan-arguments",
                payloadJson,
                payloadJson.length(),
                Instant.now()
        );
    }

    private String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return "[\"" + String.join("\",\"", values) + "\"]";
    }

    private AgentRuntimeProperties.ToolDefinitionProperties datasourceMetadataTool() {
        AgentRuntimeProperties.ToolDefinitionProperties tool = new AgentRuntimeProperties.ToolDefinitionProperties();
        tool.setEnabled(true);
        tool.setToolCode("datasource.metadata.read");
        tool.setToolType(AgentToolType.DATASOURCE_METADATA);
        tool.setTargetService("datasource-management");
        tool.setTargetEndpoint("/metadata");
        tool.setReadOnly(true);
        tool.setRiskLevel(AgentToolRiskLevel.LOW);
        tool.setExecutionMode(AgentToolExecutionMode.ASYNC_TASK);
        tool.setRequiresApproval(false);
        tool.setIdempotent(true);
        tool.setTimeoutMs(10000L);
        tool.setMaxRetries(0);
        tool.setAllowedActions(List.of("READ"));
        return tool;
    }

    private record TestFixture(AgentAsyncTaskCommandPreCheckService service,
                               InMemoryAgentRunToolDagConfirmationStore confirmationStore,
                               AgentToolExecutionAuditMemoryStore auditStore,
                               AgentToolRuntimeProtectionService runtimeProtectionService,
                               AgentSessionRecord session) {

        void saveAudit(AgentToolExecutionState state) {
            auditStore.save(audit(state));
        }

        void saveConfirmation(Instant expiresAt, List<String> policyVersions) {
            confirmationStore.saveIfAbsent(confirmation(expiresAt, policyVersions));
        }
    }
}

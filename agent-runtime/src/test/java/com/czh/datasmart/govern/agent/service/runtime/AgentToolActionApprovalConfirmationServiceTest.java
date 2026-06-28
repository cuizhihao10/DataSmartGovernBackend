/**
 * @Author : Cui
 * @Date: 2026/06/28 22:20
 * @Description DataSmart Govern Backend - AgentToolActionApprovalConfirmationServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具动作审批确认事实注册服务测试。
 *
 * <p>测试重点是确认事实的产品边界：只有 Host 已经物化并复核通过的 `agent-payload:` 才能生成
 * `tool-action-confirmation:`；确认记录只能保存低敏元数据，不能把治理草案正文、异常聚合、SQL、prompt 或工具参数
 * 扩散到审批事实里。</p>
 */
class AgentToolActionApprovalConfirmationServiceTest {

    @Test
    void confirmMaterializedPayloadShouldCreateLowSensitiveConfirmationFact() {
        TestFixture fixture = fixtureWithMaterializedPayload();

        AgentToolActionApprovalConfirmationRecord record = fixture.confirmationService()
                .confirmMaterializedPayload(
                        request(null),
                        proposal(fixture.payloadReference()),
                        projectOwnerContext(),
                        Duration.ofMinutes(20)
                )
                .orElseThrow();

        assertTrue(record.confirmationId().startsWith(
                AgentToolActionApprovalConfirmationEvidenceVerifier.CONFIRMATION_PREFIX));
        assertEquals("proposal-001", record.proposalId());
        assertEquals(fixture.payloadReference(), record.payloadReference());
        assertEquals("run-001", record.runId());
        assertEquals("quality-remediation-task-draft:audit-001", record.payloadKey());
        assertEquals("10", record.tenantId());
        assertEquals("20", record.projectId());
        assertEquals("1001", record.actorId());
        assertEquals("1001", record.confirmingActorId());
        assertEquals("quality.remediation.task.draft", record.toolName());
        assertTrue(record.payloadBodyAvailable());
        assertTrue(record.payloadSizeBytes() > 0);
        assertTrue(record.acceptedPayloadEvidence().contains("PAYLOAD_BODY_AVAILABLE"));
        assertFalse(record.acceptedPayloadEvidence().toString().contains("remediationTaskDraft"));
        assertFalse(record.acceptedPayloadEvidence().toString().contains("FORMAT_INVALID"));
    }

    @Test
    void confirmMaterializedPayloadShouldRejectEnvelopeWithoutPayloadBody() {
        InMemoryAgentToolActionPayloadStore payloadStore = new InMemoryAgentToolActionPayloadStore();
        AgentToolActionPayloadStoreService payloadStoreService = new AgentToolActionPayloadStoreService(payloadStore);
        AgentToolActionApprovalConfirmationService service = new AgentToolActionApprovalConfirmationService(
                payloadStoreService,
                new InMemoryAgentToolActionApprovalConfirmationStore()
        );
        String payloadReference = "agent-payload:run-001/quality-remediation-task-draft:audit-001";
        payloadStoreService.ensureEnvelope(proposal(payloadReference), request(null), projectOwnerContext());

        assertTrue(service.confirmMaterializedPayload(
                request(null),
                proposal(payloadReference),
                projectOwnerContext(),
                Duration.ofMinutes(20)
        ).isEmpty());
    }

    private TestFixture fixtureWithMaterializedPayload() {
        InMemoryAgentToolActionPayloadStore payloadStore = new InMemoryAgentToolActionPayloadStore();
        AgentToolActionPayloadStoreService payloadStoreService = new AgentToolActionPayloadStoreService(payloadStore);
        AgentToolActionPayloadMaterializationService materializationService =
                new AgentToolActionPayloadMaterializationService(payloadStore);
        InMemoryAgentToolActionApprovalConfirmationStore confirmationStore =
                new InMemoryAgentToolActionApprovalConfirmationStore();
        String payloadReference = materializationService
                .buildPayloadReference("run-001", "quality-remediation-task-draft:audit-001")
                .orElseThrow();
        materializationService.materializePayloadBody(
                new AgentToolActionPayloadMaterializationService.AgentToolActionPayloadMaterializationRequest(
                        payloadReference,
                        "run-001",
                        "10",
                        "20",
                        "1001",
                        "quality.remediation.task.draft",
                        "graph-001",
                        "quality-remediation-task-draft.v1",
                        "LOW_SENSITIVE_DRAFT_BODY",
                        List.of("remediationScope", "dryRun"),
                        List.of("remediationScope"),
                        Map.of(
                                "summary", Map.of("draftOnly", true, "anomalyCount", 18),
                                "remediationTaskDraft", Map.of(
                                        "payloadPolicy", "LOW_SENSITIVE_AGGREGATION_ONLY",
                                        "topAnomalyTypes", List.of(Map.of("key", "FORMAT_INVALID", "count", 18))
                                )
                        ),
                        Duration.ofMinutes(30)
                )
        ).orElseThrow();
        return new TestFixture(
                payloadReference,
                new AgentToolActionApprovalConfirmationService(payloadStoreService, confirmationStore)
        );
    }

    private AgentToolActionCommandProposalRequest request(String confirmationId) {
        return new AgentToolActionCommandProposalRequest(
                "graph-001",
                "quality-remediation-task-draft.v1",
                "10",
                "20",
                "1001",
                "request-001",
                "run-001",
                "session-001",
                null,
                20,
                "agent-payload:run-001/quality-remediation-task-draft:audit-001",
                confirmationId,
                null,
                "tool-readiness-policy.v1",
                "agent-tool-action-command.v1",
                "REQUIRED",
                "client-request-001"
        );
    }

    private AgentToolActionCommandProposalResponse proposal(String payloadReference) {
        return new AgentToolActionCommandProposalResponse(
                "proposal-001",
                "READY_FOR_OUTBOX_CONFIRMATION",
                true,
                "graph-001",
                "quality-remediation-task-draft.v1",
                "event-key-001",
                1L,
                "10",
                "20",
                "1001",
                "request-001",
                "run-001",
                "session-001",
                Instant.parse("2026-06-28T14:20:00Z"),
                "quality.remediation.task.draft",
                "AGENT_TOOL_ACTION_CONTROLLED_COMMAND",
                "agent-tool-action-command.v1",
                "idem-001",
                payloadReference,
                "LOW_SENSITIVE_DRAFT_BODY",
                true,
                "REQUIRED",
                "READY_TO_EXECUTE",
                "NON_TERMINAL",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private AgentRuntimeEventQueryAccessContext projectOwnerContext() {
        return new AgentRuntimeEventQueryAccessContext(
                10L,
                1001L,
                "PROJECT_OWNER",
                "trace-approval-confirmation-service-test",
                "PROJECT",
                List.of(20L)
        );
    }

    private record TestFixture(
            String payloadReference,
            AgentToolActionApprovalConfirmationService confirmationService
    ) {
    }
}

/**
 * @Author : Cui
 * @Date: 2026/06/28 22:20
 * @Description DataSmart Govern Backend - AgentToolActionApprovalConfirmationEvidenceVerifierTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalResponse;
import com.czh.datasmart.govern.agent.service.execution.confirmation.AgentRunToolDagConfirmationAccessSupport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具动作审批确认事实 writer 前校验测试。
 *
 * <p>该测试覆盖的是“确认事实已经进入主 fact evidence verifier”这一层，而不只是注册服务自身。
 * 这样可以证明 outbox writer 复用主 verifier 时，会对 `tool-action-confirmation:` 执行服务端强回查，
 * 不会退化成只检查字符串前缀和字符集。</p>
 */
class AgentToolActionApprovalConfirmationEvidenceVerifierTest {

    @Test
    void factEvidenceVerifierShouldAcceptBoundToolActionApprovalConfirmation() {
        TestFixture fixture = fixtureWithConfirmation(Duration.ofMinutes(20));
        AgentToolActionFactEvidenceVerifier verifier = new AgentToolActionFactEvidenceVerifier(
                null,
                new AgentRunToolDagConfirmationAccessSupport(),
                new AgentToolActionApprovalConfirmationEvidenceVerifier(fixture.confirmationStore())
        );

        AgentToolActionFactEvidenceVerificationResult result = verifier.verify(
                request(fixture.confirmationId()),
                proposal(fixture.payloadReference()),
                projectOwnerContext()
        );

        assertTrue(result.verifiedForWriter());
        assertTrue(result.acceptedEvidence().contains(
                "TOOL_ACTION_APPROVAL_CONFIRMATION_RECORD_FOUND:" + fixture.confirmationId()));
        assertTrue(result.acceptedEvidence().contains("TOOL_ACTION_APPROVAL_PAYLOAD_REFERENCE_MATCHED"));
        assertTrue(result.acceptedEvidence().contains("TOOL_ACTION_APPROVAL_PAYLOAD_BODY_AVAILABLE"));
        assertFalse(result.acceptedEvidence().toString().contains("remediationTaskDraft"));
        assertFalse(result.acceptedEvidence().toString().contains("FORMAT_INVALID"));
    }

    @Test
    void factEvidenceVerifierShouldRejectExpiredToolActionApprovalConfirmation() {
        TestFixture fixture = fixtureWithConfirmation(Duration.ofSeconds(-1));
        AgentToolActionFactEvidenceVerifier verifier = new AgentToolActionFactEvidenceVerifier(
                null,
                new AgentRunToolDagConfirmationAccessSupport(),
                new AgentToolActionApprovalConfirmationEvidenceVerifier(fixture.confirmationStore())
        );

        AgentToolActionFactEvidenceVerificationResult result = verifier.verify(
                request(fixture.confirmationId()),
                proposal(fixture.payloadReference()),
                projectOwnerContext()
        );

        assertFalse(result.verifiedForWriter());
        assertTrue(result.issueCodes().contains("TOOL_ACTION_APPROVAL_CONFIRMATION_EXPIRED"));
    }

    @Test
    void factEvidenceVerifierShouldRejectMissingToolActionApprovalConfirmation() {
        AgentToolActionFactEvidenceVerifier verifier = new AgentToolActionFactEvidenceVerifier(
                null,
                new AgentRunToolDagConfirmationAccessSupport(),
                new AgentToolActionApprovalConfirmationEvidenceVerifier(
                        new InMemoryAgentToolActionApprovalConfirmationStore()
                )
        );

        AgentToolActionFactEvidenceVerificationResult result = verifier.verify(
                request("tool-action-confirmation:not-found"),
                proposal("agent-payload:run-001/quality-remediation-task-draft:audit-001"),
                projectOwnerContext()
        );

        assertFalse(result.verifiedForWriter());
        assertTrue(result.issueCodes().contains("TOOL_ACTION_APPROVAL_CONFIRMATION_RECORD_NOT_FOUND"));
    }

    private TestFixture fixtureWithConfirmation(Duration ttl) {
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
        AgentToolActionApprovalConfirmationRecord record =
                new AgentToolActionApprovalConfirmationService(payloadStoreService, confirmationStore)
                        .confirmMaterializedPayload(
                                request(null),
                                proposal(payloadReference),
                                projectOwnerContext(),
                                ttl
                        )
                        .orElseThrow();
        return new TestFixture(payloadReference, record.confirmationId(), confirmationStore);
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
                "trace-approval-confirmation-verifier-test",
                "PROJECT",
                List.of(20L)
        );
    }

    private record TestFixture(
            String payloadReference,
            String confirmationId,
            InMemoryAgentToolActionApprovalConfirmationStore confirmationStore
    ) {
    }
}

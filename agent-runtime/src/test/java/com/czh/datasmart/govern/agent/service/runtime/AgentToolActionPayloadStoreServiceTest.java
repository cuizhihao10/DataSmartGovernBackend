/**
 * @Author : Cui
 * @Date: 2026/06/11 00:00
 * @Description DataSmart Govern Backend - AgentToolActionPayloadStoreServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

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
 * 工具动作 payload store 服务测试。
 *
 * <p>本测试聚焦 5.57 的关键产品边界：`agent-payload:` 不是任意字符串，而是必须由服务端登记的可审计事实。
 * 同时，该事实在当前阶段只允许携带低敏 envelope 元数据，不允许泄露真实参数正文。后续即使接入数据库、
 * Redis、对象存储或加密 vault，也必须保持这些断言成立。</p>
 */
class AgentToolActionPayloadStoreServiceTest {

    @Test
    void materializePayloadBodyShouldStoreLowSensitiveBodyBehindReference() {
        InMemoryAgentToolActionPayloadStore store = new InMemoryAgentToolActionPayloadStore();
        AgentToolActionPayloadStoreService service = new AgentToolActionPayloadStoreService(store);
        AgentToolActionPayloadMaterializationService materializationService =
                new AgentToolActionPayloadMaterializationService(store);
        String payloadReference = materializationService
                .buildPayloadReference("run-001", "quality-remediation-task-draft:audit-001")
                .orElseThrow();

        AgentToolActionPayloadRecord record = materializationService.materializePayloadBody(
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
                                List.of("remediationScope", "reason"),
                                Map.of(
                                        "summary", Map.of("draftOnly", true, "anomalyCount", 18),
                                        "remediationTaskDraft", Map.of(
                                                "payloadPolicy", "LOW_SENSITIVE_AGGREGATION_ONLY",
                                                "scope", Map.of("reportId", 77, "severity", "HIGH")
                                        )
                                ),
                                Duration.ofMinutes(30)
                        )
                )
                .orElseThrow();

        assertEquals(payloadReference, record.payloadReference());
        assertTrue(record.payloadBodyAvailable());
        assertTrue(record.payloadSizeBytes() > 0);
        assertTrue(record.payloadBody().containsKey("remediationTaskDraft"));

        AgentToolActionPayloadVerdict verdict = service.verifyReference(
                payloadReference,
                proposal(payloadReference, "quality.remediation.task.draft", "quality-remediation-task-draft.v1"),
                projectOwnerContext()
        );
        assertTrue(verdict.readableForWriter());
        assertTrue(verdict.acceptedEvidence().contains("PAYLOAD_BODY_AVAILABLE"));
        assertFalse(verdict.acceptedEvidence().toString().contains("remediationTaskDraft"));
    }

    @Test
    void ensureEnvelopeShouldRegisterLowSensitiveMetadataWithoutPayloadBody() {
        InMemoryAgentToolActionPayloadStore store = new InMemoryAgentToolActionPayloadStore();
        AgentToolActionPayloadStoreService service = new AgentToolActionPayloadStoreService(store);

        AgentToolActionPayloadRecord record = service.ensureEnvelope(
                        proposal("agent-payload:run-001/datasource-metadata-read"),
                        null,
                        projectOwnerContext()
                )
                .orElseThrow();

        assertEquals("agent-payload:run-001/datasource-metadata-read", record.payloadReference());
        assertEquals("run-001", record.runId());
        assertEquals("datasource-metadata-read", record.payloadKey());
        assertEquals("10", record.tenantId());
        assertEquals("20", record.projectId());
        assertEquals("1001", record.actorId());
        assertEquals("datasource.metadata.read", record.toolName());
        assertFalse(record.payloadBodyAvailable());
        assertEquals(0, record.payloadSizeBytes());
        assertTrue(record.payloadBody().isEmpty());

        AgentToolActionPayloadVerdict verdict = service.verifyReference(
                record.payloadReference(),
                proposal(record.payloadReference()),
                projectOwnerContext()
        );
        assertTrue(verdict.readableForWriter());
        assertTrue(verdict.acceptedEvidence().contains("AGENT_PAYLOAD_RECORD_FOUND"));
        assertTrue(verdict.acceptedEvidence().contains("PAYLOAD_BODY_NOT_MATERIALIZED"));
    }

    @Test
    void verifyReferenceShouldRejectPayloadRecordOutsideProjectScope() {
        InMemoryAgentToolActionPayloadStore store = new InMemoryAgentToolActionPayloadStore();
        AgentToolActionPayloadStoreService service = new AgentToolActionPayloadStoreService(store);
        AgentToolActionCommandProposalResponse proposal =
                proposal("agent-payload:run-001/datasource-metadata-read");
        service.ensureEnvelope(proposal, null, projectOwnerContext());

        AgentToolActionPayloadVerdict verdict = service.verifyReference(
                proposal.payloadReference(),
                proposal,
                new AgentRuntimeEventQueryAccessContext(
                        10L,
                        1001L,
                        "PROJECT_OWNER",
                        "trace-payload-store-test-denied",
                        "PROJECT",
                        List.of(999L)
                )
        );

        assertFalse(verdict.readableForWriter());
        assertTrue(verdict.issueCodes().contains("AGENT_PAYLOAD_RECORD_CONTEXT_DENIED"));
    }

    @Test
    void verifyReferenceShouldRejectExpiredPayloadRecord() {
        InMemoryAgentToolActionPayloadStore store = new InMemoryAgentToolActionPayloadStore();
        AgentToolActionPayloadStoreService service = new AgentToolActionPayloadStoreService(store);
        store.append(new AgentToolActionPayloadRecord(
                "agent-payload:run-001/datasource-metadata-read",
                "run-001",
                "datasource-metadata-read",
                "10",
                "20",
                "1001",
                "datasource.metadata.read",
                "graph-001",
                "contract-001",
                "REFERENCE_ONLY",
                List.of(),
                List.of(),
                false,
                0,
                "expired-metadata-digest",
                Instant.parse("2026-06-07T08:00:00Z"),
                Instant.parse("2026-06-07T09:00:00Z"),
                Map.of()
        ));

        AgentToolActionPayloadVerdict verdict = service.verifyReference(
                "agent-payload:run-001/datasource-metadata-read",
                proposal("agent-payload:run-001/datasource-metadata-read"),
                projectOwnerContext()
        );

        assertFalse(verdict.readableForWriter());
        assertTrue(verdict.issueCodes().contains("AGENT_PAYLOAD_RECORD_EXPIRED"));
    }

    private AgentToolActionCommandProposalResponse proposal(String payloadReference) {
        return proposal(payloadReference, "datasource.metadata.read", "contract-001");
    }

    private AgentToolActionCommandProposalResponse proposal(String payloadReference, String toolName, String contractId) {
        return new AgentToolActionCommandProposalResponse(
                "proposal-001",
                "READY_FOR_OUTBOX_CONFIRMATION",
                true,
                "graph-001",
                contractId,
                "event-key-001",
                1L,
                "10",
                "20",
                "1001",
                "request-001",
                "run-001",
                "session-001",
                Instant.parse("2026-06-07T08:00:00Z"),
                toolName,
                "AGENT_TOOL_ACTION_CONTROLLED_COMMAND",
                "agent-tool-action-command.v1",
                "idem-001",
                payloadReference,
                "REFERENCE_ONLY",
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
                "trace-payload-store-test",
                "PROJECT",
                List.of(20L)
        );
    }
}

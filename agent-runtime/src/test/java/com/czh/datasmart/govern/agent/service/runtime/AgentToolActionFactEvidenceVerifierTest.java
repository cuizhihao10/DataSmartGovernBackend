/**
 * @Author : Cui
 * @Date: 2026/06/07 15:58
 * @Description DataSmart Govern Backend - AgentToolActionFactEvidenceVerifierTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalResponse;
import com.czh.datasmart.govern.agent.service.execution.confirmation.AgentRunToolDagConfirmationAccessSupport;
import com.czh.datasmart.govern.agent.service.execution.confirmation.AgentRunToolDagConfirmationRecord;
import com.czh.datasmart.govern.agent.service.execution.confirmation.AgentRunToolDagConfirmationStatus;
import com.czh.datasmart.govern.agent.service.execution.confirmation.InMemoryAgentRunToolDagConfirmationStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具动作人工事实 verifier 测试。
 *
 * <p>该测试聚焦 {@code dag-confirmation:...} 这类已经在 agent-runtime 内部落地的确认事实。
 * 对于这类 ID，writer 前复核不能只看字符串形态，还要回查 confirmation store，确认记录存在、未过期、
 * 已确认，并且属于当前 proposal 和 gateway 访问上下文。这样可以防止跨 run 复制 confirmationId，
 * 或把过期确认重新用于新的 command outbox 写入。</p>
 */
class AgentToolActionFactEvidenceVerifierTest {

    @Test
    void dagConfirmationShouldPassWhenRecordIsConfirmedAndBoundToCurrentContext() {
        InMemoryAgentRunToolDagConfirmationStore store = new InMemoryAgentRunToolDagConfirmationStore(10, 100);
        store.saveIfAbsent(confirmation("dag-confirmation:001", Instant.now().plusSeconds(3600)));
        AgentToolActionFactEvidenceVerifier verifier = new AgentToolActionFactEvidenceVerifier(
                store,
                new AgentRunToolDagConfirmationAccessSupport()
        );

        AgentToolActionFactEvidenceVerificationResult result = verifier.verify(
                request("dag-confirmation:001"),
                proposal(),
                projectOwnerContext()
        );

        assertTrue(result.verifiedForWriter());
        assertTrue(result.acceptedEvidence().contains("DAG_CONFIRMATION_RECORD_FOUND:dag-confirmation:001"));
        assertTrue(result.acceptedEvidence().contains("DAG_CONFIRMATION_METADATA_SCOPE_VERIFIED"));
        assertTrue(result.acceptedEvidence().contains("DAG_CONFIRMATION_POLICY_VERSION_MATCHED"));
    }

    @Test
    void dagConfirmationShouldBeRejectedWhenRecordIsExpired() {
        InMemoryAgentRunToolDagConfirmationStore store = new InMemoryAgentRunToolDagConfirmationStore(10, 100);
        store.saveIfAbsent(confirmation("dag-confirmation:expired", Instant.now().minusSeconds(1)));
        AgentToolActionFactEvidenceVerifier verifier = new AgentToolActionFactEvidenceVerifier(
                store,
                new AgentRunToolDagConfirmationAccessSupport()
        );

        AgentToolActionFactEvidenceVerificationResult result = verifier.verify(
                request("dag-confirmation:expired"),
                proposal(),
                projectOwnerContext()
        );

        assertFalse(result.verifiedForWriter());
        assertTrue(result.issueCodes().contains("DAG_CONFIRMATION_EXPIRED"));
    }

    private AgentRunToolDagConfirmationRecord confirmation(String confirmationId, Instant expiresAt) {
        return new AgentRunToolDagConfirmationRecord(
                confirmationId,
                "session-001",
                "run-001",
                "fingerprint-001",
                List.of("node-001"),
                List.of("audit-001"),
                List.of("tool-readiness-policy.v1"),
                List.of("delegation:evidence:001"),
                null,
                List.of("outbox-001"),
                List.of("command-001"),
                10L,
                20L,
                30L,
                "1001",
                "trace-fact-verifier-test",
                true,
                AgentRunToolDagConfirmationStatus.CONFIRMED,
                expiresAt,
                Instant.now(),
                Instant.now()
        );
    }

    private AgentToolActionCommandProposalRequest request(String approvalConfirmationId) {
        return new AgentToolActionCommandProposalRequest(
                "graph-001",
                "contract-001",
                "10",
                "20",
                "1001",
                "request-001",
                "run-001",
                "session-001",
                null,
                20,
                "agent-payload:run-001/datasource-metadata-read",
                approvalConfirmationId,
                null,
                "tool-readiness-policy.v1",
                "agent-tool-action-command.v1",
                "REQUIRED",
                "client-request-001"
        );
    }

    private AgentToolActionCommandProposalResponse proposal() {
        return new AgentToolActionCommandProposalResponse(
                "proposal-001",
                "READY_FOR_OUTBOX_CONFIRMATION",
                true,
                "graph-001",
                "contract-001",
                "event-key-001",
                1L,
                "10",
                "20",
                "1001",
                "request-001",
                "run-001",
                "session-001",
                Instant.parse("2026-06-07T07:58:00Z"),
                "datasource.metadata.read",
                "AGENT_TOOL_ACTION_CONTROLLED_COMMAND",
                "agent-tool-action-command.v1",
                "idem-001",
                "agent-payload:run-001/datasource-metadata-read",
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
                "trace-fact-verifier-test",
                "PROJECT",
                List.of(20L)
        );
    }
}

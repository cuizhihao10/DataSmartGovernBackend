/**
 * @Author : Cui
 * @Date: 2026/06/07 15:55
 * @Description DataSmart Govern Backend - AgentToolActionPayloadReferenceVerifierTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalResponse;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditMemoryStore;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具动作 payloadReference verifier 测试。
 *
 * <p>5.54 的 verifier 只验证 payloadReference 是否像一个受控引用；5.55 开始，如果引用使用历史
 * {@code agent-tool-audit://.../plan-arguments} 协议，并且审计仓储可用，就必须进一步确认 auditId
 * 真实存在且元数据绑定当前 session/run/tenant/project/actor。这样可以避免调用方伪造一个字符串格式正确、
 * 但实际上不存在或越权的审计载荷引用。</p>
 */
class AgentToolActionPayloadReferenceVerifierTest {

    @Test
    void agentPayloadReferenceShouldRequireServerSidePayloadRecordWhenStoreIsAvailable() {
        AgentToolActionPayloadStoreService payloadStoreService =
                new AgentToolActionPayloadStoreService(new InMemoryAgentToolActionPayloadStore());
        AgentToolActionPayloadReferenceVerifier verifier =
                new AgentToolActionPayloadReferenceVerifier(null, payloadStoreService);

        AgentToolActionPayloadReferenceVerificationResult result = verifier.verify(
                proposal("agent-payload:run-001/datasource-metadata-read"),
                projectOwnerContext()
        );

        assertFalse(result.verifiedForWriter());
        assertTrue(result.issueCodes().contains("AGENT_PAYLOAD_RECORD_NOT_FOUND"));
    }

    @Test
    void agentPayloadReferenceShouldUseOnlyLowSensitivePayloadStoreVerdictAfterEnvelopeRegistered() {
        InMemoryAgentToolActionPayloadStore payloadStore = new InMemoryAgentToolActionPayloadStore();
        AgentToolActionPayloadStoreService payloadStoreService =
                new AgentToolActionPayloadStoreService(payloadStore);
        AgentToolActionCommandProposalResponse proposal =
                proposal("agent-payload:run-001/datasource-metadata-read");
        payloadStoreService.ensureEnvelope(proposal, null, projectOwnerContext());
        AgentToolActionPayloadReferenceVerifier verifier =
                new AgentToolActionPayloadReferenceVerifier(null, payloadStoreService);

        AgentToolActionPayloadReferenceVerificationResult result = verifier.verify(
                proposal,
                projectOwnerContext()
        );

        assertTrue(result.verifiedForWriter());
        assertTrue(result.acceptedEvidence().contains("AGENT_PAYLOAD_RECORD_FOUND"));
        assertTrue(result.acceptedEvidence().contains("AGENT_PAYLOAD_METADATA_SCOPE_VERIFIED"));
        assertTrue(result.acceptedEvidence().contains("PAYLOAD_BODY_NOT_MATERIALIZED"));
        assertFalse(result.acceptedEvidence().toString().contains("payloadBody"));
        assertFalse(result.acceptedEvidence().toString().contains("select * from sensitive_table"));
        assertFalse(result.acceptedEvidence().toString().contains("raw prompt"));
    }

    @Test
    void agentToolAuditReferenceShouldRequireExistingAuditRecordWhenStoreIsAvailable() {
        AgentToolExecutionAuditMemoryStore auditStore = new AgentToolExecutionAuditMemoryStore();
        AgentToolActionPayloadReferenceVerifier verifier =
                new AgentToolActionPayloadReferenceVerifier(auditStore);

        AgentToolActionPayloadReferenceVerificationResult result = verifier.verify(
                proposal("agent-tool-audit://session-001/run-001/missing-audit/plan-arguments"),
                projectOwnerContext()
        );

        assertFalse(result.verifiedForWriter());
        assertTrue(result.issueCodes().contains("AGENT_TOOL_AUDIT_RECORD_NOT_FOUND"));
    }

    @Test
    void agentToolAuditReferenceShouldExposeOnlyLowSensitiveEvidenceAfterBindingCheck() {
        AgentToolExecutionAuditMemoryStore auditStore = new AgentToolExecutionAuditMemoryStore();
        auditStore.save(auditRecord());
        AgentToolActionPayloadReferenceVerifier verifier =
                new AgentToolActionPayloadReferenceVerifier(auditStore);

        AgentToolActionPayloadReferenceVerificationResult result = verifier.verify(
                proposal("agent-tool-audit://session-001/run-001/audit-001/plan-arguments"),
                projectOwnerContext()
        );

        assertTrue(result.verifiedForWriter());
        assertTrue(result.acceptedEvidence().contains("AUDIT_RECORD_FOUND:audit-001"));
        assertTrue(result.acceptedEvidence().contains("AUDIT_METADATA_SCOPE_VERIFIED"));
        assertFalse(result.acceptedEvidence().toString().contains("select * from sensitive_table"));
        assertFalse(result.acceptedEvidence().toString().contains("raw prompt"));
    }

    /**
     * 构造一条包含敏感 planArguments 的审计记录。
     *
     * <p>测试重点不是让 verifier 读取这些参数，而是确认它即使能访问到审计记录，也只把 auditId 和范围校验结果
     * 写入 acceptedEvidence，不把 SQL、prompt、样本数据等正文扩散到 outbox 写入前的控制面结果里。</p>
     */
    private AgentToolExecutionAuditRecord auditRecord() {
        return new AgentToolExecutionAuditRecord(
                "audit-001",
                "session-001",
                "run-001",
                "binding-001",
                "datasource.metadata.read",
                "DATASOURCE",
                "datasource-management",
                "/internal/metadata/read",
                100L,
                10L,
                20L,
                30L,
                "1001",
                "LOW",
                "SYNC",
                false,
                true,
                true,
                List.of("READ_METADATA"),
                "模型计划读取数据源元数据。",
                Map.of("sql", "select * from sensitive_table", "prompt", "raw prompt"),
                Map.of("sensitiveArgumentNames", List.of("sql", "prompt")),
                Map.of("valid", true),
                AgentToolExecutionState.PLANNED,
                "trace-audit-verifier-test",
                "已规划，等待执行前复核。",
                LocalDateTime.now()
        );
    }

    private AgentToolActionCommandProposalResponse proposal(String payloadReference) {
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
                Instant.parse("2026-06-07T07:55:00Z"),
                "datasource.metadata.read",
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
                "trace-audit-verifier-test",
                "PROJECT",
                List.of(20L)
        );
    }
}

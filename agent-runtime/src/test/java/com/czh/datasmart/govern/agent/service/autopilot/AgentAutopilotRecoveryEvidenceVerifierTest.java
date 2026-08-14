/**
 * @Author : Cui
 * @Date: 2026/08/11 21:15
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryEvidenceVerifierTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证 Java 会重算 evidence digest，而不是采信 Python 的布尔标志。 */
class AgentAutopilotRecoveryEvidenceVerifierTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentAutopilotRecoveryEvidenceVerifier verifier =
            new AgentAutopilotRecoveryEvidenceVerifier(objectMapper);

    /** 结构化诊断证据的数量、来源、时间、scope 和 canonical digest 全部一致时才允许进入策略层。 */
    @Test
    void shouldAcceptFreshCanonicalDiagnosticEvidence() throws Exception {
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        Map<String, Object> audit = audit();

        boolean verified = verifier.verify(trigger, response(audit, audit.get("evidenceDigest")));

        assertThat(verified).isTrue();
    }

    /** queryDigest 只能描述查询，不能替代真正绑定 evidenceRecords 的 evidenceDigest。 */
    @Test
    void shouldRejectQueryDigestPretendingToBeEvidenceDigest() throws Exception {
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        Map<String, Object> audit = audit();

        assertThatThrownBy(() -> verifier.verify(
                trigger,
                response(audit, audit.get("queryDigest"))))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("AUTOPILOT_EVIDENCE_DIGEST_MISMATCH");
    }

    /** 没有逐条可信度及校准依据的证据，即使摘要能够复算，也不能进入自动恢复。 */
    @Test
    void shouldRejectEvidenceWithoutConfidenceMetadata() throws Exception {
        Map<String, Object> original = audit();
        List<?> originalRecords = (List<?>) original.get("evidenceRecords");
        Map<?, ?> originalRecord = (Map<?, ?>) originalRecords.get(0);
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        originalRecord.forEach((key, value) -> record.put(String.valueOf(key), value));
        record.remove("confidence");
        record.remove("confidenceBasis");

        String recordsJson = objectMapper.writeValueAsString(List.of(canonical(record)));
        Map<String, Object> replaced = new java.util.LinkedHashMap<>(original);
        replaced.put("evidenceRecords", List.of(record));
        replaced.put("evidenceDigest", "sha256:" + sha256(recordsJson));

        assertThatThrownBy(() -> verifier.verify(
                trigger(), response(replaced, replaced.get("evidenceDigest"))))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("AUTOPILOT_EVIDENCE_CONFIDENCE_INVALID");
    }

    /** RAG 与结构化诊断必须共用 sourceRef；sourceUri 只作为兼容字段存在。 */
    @Test
    void shouldAcceptSearchEvidenceWithUnifiedSourceReference() throws Exception {
        Map<String, Object> diagnosticAudit = searchDiagnosticAudit();

        boolean verified = verifier.verify(trigger(), searchResponse(diagnosticAudit, retrievalAudit(true)));

        assertThat(verified).isTrue();
    }

    /** 即使旧 sourceUri 仍存在，缺少统一 sourceRef 的 RAG 证据也必须失败关闭。 */
    @Test
    void shouldRejectSearchEvidenceWithoutUnifiedSourceReference() throws Exception {
        Map<String, Object> diagnosticAudit = searchDiagnosticAudit();

        assertThatThrownBy(() -> verifier.verify(
                trigger(), searchResponse(diagnosticAudit, retrievalAudit(false))))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessageContaining("AUTOPILOT_EVIDENCE_SOURCE_REFERENCE_MISSING");
    }

    /** 创建与 Python Recovery ``json.dumps(sort_keys=True)`` 一致的单条诊断证据摘要。 */
    private Map<String, Object> audit() throws Exception {
        String retrievedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        String queryDigest = "sha256:" + "1".repeat(64);
        Map<String, Object> record = Map.of(
                "evidenceId", "diagnostic-evidence-1",
                "sourceType", "STRUCTURED_API",
                "sourceRef", "sync-execution:31:41",
                "retrievedAt", retrievedAt,
                "queryDigest", queryDigest,
                "querySummary", Map.of("kind", "RECOVERY_DIAGNOSTIC", "fieldCount", 3),
                "confidence", 0.95d,
                "confidenceBasis", "AUTHORITATIVE_PLATFORM_FACT");
        List<Object> canonicalRecords = List.of(canonical(record));
        String recordsJson = objectMapper.writeValueAsString(canonicalRecords);
        return Map.of(
                "queryDigest", queryDigest,
                "retrievedAt", retrievedAt,
                "evidenceCount", 1,
                "sourceTypes", List.of("STRUCTURED_API"),
                "evidenceRecords", List.of(record),
                "evidenceDigest", "sha256:" + sha256(recordsJson));
    }

    /** 构造 CANDIDATE_READY 响应，并允许测试替换 evidenceDigest。 */
    private AgentAutopilotRecoveryPlanResponse response(Map<String, Object> audit, Object evidenceDigest) {
        Map<String, Object> replacedAudit = new java.util.LinkedHashMap<>(audit);
        replacedAudit.put("evidenceDigest", evidenceDigest);
        return new AgentAutopilotRecoveryPlanResponse(
                "datasmart.autopilot.recovery-candidate.v1", "event-1", "CANDIDATE_READY",
                "RECOVERY_CANDIDATE_READY", "RETRY_EXECUTION", "LOW", true,
                "b".repeat(64), "a".repeat(64), 0.91d, true,
                replacedAudit,
                Map.of(
                        "tenantId", "11",
                        "projectId", "13",
                        "workspaceKey", "workspace-13",
                        "taskId", "31",
                        "executionId", "41"),
                "SKIP", "STRUCTURED_DIAGNOSTIC", Map.of(), true,
                "autopilot-recovery:event-1",
                "LOW_SENSITIVE_AUTOPILOT_RECOVERY_CANDIDATE_ONLY");
    }

    /** 构造同时绑定权威结构化事实和 RAG 引用的诊断摘要。 */
    private Map<String, Object> searchDiagnosticAudit() throws Exception {
        Map<String, Object> original = audit();
        String retrievedAt = String.valueOf(original.get("retrievedAt"));
        String queryDigest = String.valueOf(original.get("queryDigest"));
        List<?> originalRecords = (List<?>) original.get("evidenceRecords");
        Map<String, Object> ragRecord = Map.of(
                "evidenceId", "rag-evidence-1",
                "sourceType", "RAG",
                "sourceRef", "runbook://data-sync/recovery",
                "retrievedAt", retrievedAt,
                "queryDigest", queryDigest,
                "querySummary", Map.of("kind", "RAG_RESULT", "fieldCount", 2),
                "confidence", 0.82d,
                "confidenceBasis", "HYBRID_RETRIEVAL_SCORE");
        List<Object> records = List.of(originalRecords.get(0), ragRecord);
        String recordsJson = objectMapper.writeValueAsString(records.stream().map(this::canonical).toList());
        Map<String, Object> result = new java.util.LinkedHashMap<>(original);
        result.put("evidenceCount", 2);
        result.put("sourceTypes", List.of("STRUCTURED_API", "RAG"));
        result.put("evidenceRecords", records);
        result.put("evidenceDigest", "sha256:" + sha256(recordsJson));
        return result;
    }

    /** 构造 RAG 检索摘要；includeSourceRef=false 用于证明兼容 sourceUri 不能单独放行。 */
    private Map<String, Object> retrievalAudit(boolean includeSourceRef) {
        String retrievedAt = OffsetDateTime.now(ZoneOffset.UTC).toString();
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("evidenceId", "rag-evidence-1");
        record.put("sourceType", "RUNBOOK");
        record.put("sourceUri", "runbook://data-sync/recovery");
        if (includeSourceRef) {
            record.put("sourceRef", "runbook://data-sync/recovery");
        }
        record.put("retrievedAt", retrievedAt);
        record.put("confidence", 0.82d);
        record.put("confidenceBasis", "HYBRID_RETRIEVAL_SCORE");
        return Map.of(
                "evidenceCount", 1,
                "evidenceRecords", List.of(record),
                "evidenceDigest", "sha256:" + sha256Unchecked("rag-evidence-1"),
                "retrievedAt", retrievedAt,
                "scope", Map.of(
                        "tenantId", "11",
                        "projectId", "13",
                        "workspaceKey", "workspace-13"));
    }

    /** 构造 SEARCH 候选，使测试能够独立覆盖 RAG 证据校验分支。 */
    private AgentAutopilotRecoveryPlanResponse searchResponse(
            Map<String, Object> diagnosticAudit,
            Map<String, Object> retrievalAudit) {
        return new AgentAutopilotRecoveryPlanResponse(
                "datasmart.autopilot.recovery-candidate.v1", "event-1", "CANDIDATE_READY",
                "RECOVERY_CANDIDATE_READY", "RETRY_EXECUTION", "LOW", true,
                "b".repeat(64), "a".repeat(64), 0.91d, true,
                diagnosticAudit,
                Map.of(
                        "tenantId", "11",
                        "projectId", "13",
                        "workspaceKey", "workspace-13",
                        "taskId", "31",
                        "executionId", "41"),
                "SEARCH", "EXACT_SEARCH", retrievalAudit, true,
                "autopilot-recovery:event-1",
                "LOW_SENSITIVE_AUTOPILOT_RECOVERY_CANDIDATE_ONLY");
    }

    /** 创建只包含验证器所需作用域和时间的可信触发器。 */
    private AgentAutopilotVerifiedRecoveryTrigger trigger() {
        AgentAutopilotRecoveryTriggerEvent event = new AgentAutopilotRecoveryTriggerEvent(
                "datasmart.autopilot.recovery-trigger.v1", "event-1", "session-1", "run-1",
                11L, 12L, 13L, "14", "14", "main-agent", "delegation-1",
                31L, 40L, 41L, 1, 5,
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).toString(),
                "a".repeat(64), 0, null, List.of("OBJECT_TRANSFER_FAILED"), Map.of(),
                "sha256:" + "c".repeat(64),
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1).toString());
        com.czh.datasmart.govern.agent.service.session.AgentSessionRecord session =
                mock(com.czh.datasmart.govern.agent.service.session.AgentSessionRecord.class);
        when(session.getWorkspaceKey()).thenReturn("workspace-13");
        return new AgentAutopilotVerifiedRecoveryTrigger(
                event,
                session,
                mock(com.czh.datasmart.govern.agent.service.session.AgentRunRecord.class),
                mock(AgentAutopilotAuthorizationSnapshot.class),
                OffsetDateTime.now(ZoneOffset.UTC).plusHours(1),
                OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));
    }

    /** 递归排序 Map 键，复现生产验证器的 canonical JSON。 */
    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), canonical(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonical).toList();
        }
        return value;
    }

    /** 计算测试 evidence material 的小写 SHA-256。 */
    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /** 在无需声明受检异常的夹具中计算固定 SHA-256。 */
    private String sha256Unchecked(String value) {
        try {
            return sha256(value);
        } catch (Exception exception) {
            throw new IllegalStateException("测试环境缺少 SHA-256", exception);
        }
    }
}

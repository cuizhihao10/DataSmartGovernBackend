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
                "querySummary", Map.of("kind", "RECOVERY_DIAGNOSTIC", "fieldCount", 3));
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
}

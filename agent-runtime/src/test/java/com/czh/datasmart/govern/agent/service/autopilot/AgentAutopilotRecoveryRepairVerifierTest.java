/**
 * @Author : Cui
 * @Date: 2026/08/14
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryRepairVerifierTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 验证模型修复建议在进入 data-sync 前必须通过 Java 的确定性参数和指纹复核。 */
class AgentAutopilotRecoveryRepairVerifierTest {

    /** 元数据证明型字段映射修复使用固定参数，并与事件、错误和 execution 共同绑定。 */
    @Test
    void shouldAcceptCanonicalMetadataProvenFieldMappingRepair() {
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        Map<String, Object> parameters = Map.of("repairMode", "METADATA_PROVEN_SAFE");
        AgentAutopilotRecoveryPlanResponse response = response(
                "REPAIR_FIELD_MAPPING", parameters,
                fingerprint("REPAIR_FIELD_MAPPING", "repairMode=METADATA_PROVEN_SAFE"));

        Map<String, Object> verified = new AgentAutopilotRecoveryRepairVerifier().verify(trigger, response);

        assertThat(verified).containsExactlyEntriesOf(parameters);
    }

    /** 调参不能增加并发，也不能携带白名单之外的字段。 */
    @Test
    void shouldRejectOutOfCatalogTuningParameters() {
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        Map<String, Object> parameters = Map.of("maxChannel", 2, "targetTable", "other_table");
        AgentAutopilotRecoveryPlanResponse response = response(
                "TUNE_EXECUTION_POLICY", parameters,
                fingerprint("TUNE_EXECUTION_POLICY", "maxChannel=2,targetTable=other_table"));

        assertThatThrownBy(() -> new AgentAutopilotRecoveryRepairVerifier().verify(trigger, response))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessage("AUTOPILOT_REPAIR_PARAMETERS_INVALID");
    }

    /** 参数即使合法，只要跨语言指纹不匹配，也不能进入副作用执行。 */
    @Test
    void shouldRejectRepairFingerprintMismatch() {
        AgentAutopilotRecoveryPlanResponse response = response(
                "REFRESH_METADATA", Map.of("forceRefresh", true), "f".repeat(64));

        assertThatThrownBy(() -> new AgentAutopilotRecoveryRepairVerifier().verify(trigger(), response))
                .isInstanceOf(PlatformBusinessException.class)
                .hasMessage("AUTOPILOT_REPAIR_FINGERPRINT_INVALID");
    }

    /** 构造只包含验证器所需稳定事件事实的测试触发器。 */
    private AgentAutopilotVerifiedRecoveryTrigger trigger() {
        AgentAutopilotRecoveryTriggerEvent event = new AgentAutopilotRecoveryTriggerEvent(
                "datasmart.autopilot.recovery-trigger.v1", "event-1", "session-1", "run-1",
                11L, 12L, 13L, "14", "14", "main-agent", "delegation-1",
                31L, 40L, 41L, 1, 5, "2099-01-01T00:00:00Z", "a".repeat(64),
                0, null, List.of("OBJECT_TRANSFER_FAILED"), Map.of(),
                "sha256:" + "b".repeat(64), "2026-08-12T00:00:00Z");
        return new AgentAutopilotVerifiedRecoveryTrigger(
                event,
                mock(com.czh.datasmart.govern.agent.service.session.AgentSessionRecord.class),
                mock(com.czh.datasmart.govern.agent.service.session.AgentRunRecord.class),
                mock(AgentAutopilotAuthorizationSnapshot.class),
                OffsetDateTime.parse("2099-01-01T00:00:00Z"),
                OffsetDateTime.parse("2026-08-12T00:00:00Z"));
    }

    /** 构造带显式修复参数的 Python 候选合同。 */
    private AgentAutopilotRecoveryPlanResponse response(
            String action,
            Map<String, Object> repairParameters,
            String repairFingerprint) {
        return new AgentAutopilotRecoveryPlanResponse(
                "datasmart.autopilot.recovery-candidate.v1", "event-1", "CANDIDATE_READY",
                "RECOVERY_CANDIDATE_READY", action, "LOW", true,
                repairFingerprint, "a".repeat(64), 0.91d, true,
                Map.of(), Map.of(), "SKIP", "STRUCTURED_DIAGNOSTIC", Map.of(), true,
                "autopilot-recovery:event-1", "LOW_SENSITIVE_AUTOPILOT_RECOVERY_CANDIDATE_ONLY",
                Map.of(), Map.of(), repairParameters, Map.of());
    }

    /** 复现 Python 与 data-sync 共享的 UTF-8 小写 SHA-256 规则。 */
    private String fingerprint(String action, String canonicalParameters) {
        return sha256(String.join("|", "event-1", "a".repeat(64), "41", action, canonicalParameters));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

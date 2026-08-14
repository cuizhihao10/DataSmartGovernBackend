package com.czh.datasmart.govern.agent.service.autopilot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Agent Runtime 侧恢复事实门禁测试。 */
class AgentAutopilotRecoveryFactsVerifierTest {

    /** Java 控制面不能因 Python 把外键错误标成 retryable 就放行自动重试。 */
    @Test
    void foreignKeyViolationCannotPassAutomaticRetryVerifier() {
        Map<String, Object> facts = Map.of(
                "failureClass", "TRANSIENT_CONNECTOR_OR_WORKER",
                "retryable", true,
                "eligibleForAutomaticRetry", true,
                "failedObjectCount", 1,
                "rootCauseCodes", List.of("TARGET_FOREIGN_KEY_VIOLATION"));

        assertThat(AgentAutopilotRecoveryFactsVerifier.eligibleForAutomaticRetry(facts)).isFalse();
    }
}

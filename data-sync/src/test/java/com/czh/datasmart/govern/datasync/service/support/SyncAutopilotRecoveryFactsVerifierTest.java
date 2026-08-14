package com.czh.datasmart.govern.datasync.service.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 双重恢复事实门禁中的 data-sync 侧测试。 */
class SyncAutopilotRecoveryFactsVerifierTest {

    /** 外键约束错误不属于瞬态连接器故障，不能进入无人值守普通重试。 */
    @Test
    void foreignKeyViolationCannotBeClassifiedAsAutomaticTransientRetry() {
        Map<String, Object> facts = Map.of(
                "failureClass", "TRANSIENT_CONNECTOR_OR_WORKER",
                "retryable", true,
                "eligibleForAutomaticRetry", true,
                "failedObjectCount", 1,
                "rootCauseCodes", List.of("CONNECTOR_OR_NETWORK_UNAVAILABLE", "TARGET_FOREIGN_KEY_VIOLATION"));

        assertThat(SyncAutopilotRecoveryFactsVerifier.eligibleForAutomaticRetry(facts)).isFalse();
    }
}

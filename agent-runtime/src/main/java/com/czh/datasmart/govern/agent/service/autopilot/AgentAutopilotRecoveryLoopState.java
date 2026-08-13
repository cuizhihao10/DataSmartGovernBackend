/**
 * @Author : Cui
 * @Date: 2026/08/10 23:59
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryLoopState.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import java.time.OffsetDateTime;

/** Durable counters and evidence facts for one recovery case. */
public record AgentAutopilotRecoveryLoopState(
        int recoveryCycle,
        OffsetDateTime recoveryStartedAt,
        String lastErrorFingerprint,
        int repeatedErrorCount,
        boolean evidenceSufficient,
        boolean scopeVerified,
        double confidence) {
}

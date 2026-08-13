/**
 * @Author : Cui
 * @Date: 2026/08/10 23:59
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryDecision.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

/** Auditable, low-sensitive result of the deterministic recovery gate. */
public record AgentAutopilotRecoveryDecision(
        AgentAutopilotRecoveryDecisionType decision,
        String reasonCode,
        String policyId,
        String action) {
}

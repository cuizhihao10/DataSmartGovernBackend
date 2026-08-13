/**
 * @Author : Cui
 * @Date: 2026/08/10 23:59
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryDecisionType.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

/** Deterministic outcomes produced by the Java Autopilot policy gate. */
public enum AgentAutopilotRecoveryDecisionType {
    AUTO_APPROVED,
    WAITING_APPROVAL,
    ATTENTION_REQUIRED,
    REJECTED
}

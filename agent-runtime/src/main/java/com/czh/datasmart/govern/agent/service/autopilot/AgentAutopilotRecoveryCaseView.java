/**
 * @Author : Cui
 * @Date: 2026/08/11 20:25
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryCaseView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

/**
 * data-sync 持久化 recovery case 后返回给 agent-runtime 的最小视图。
 *
 * <p>``version`` 是后续 transition 的乐观锁依据；``state`` 是 data-sync 第二次策略评估的权威结果，
 * agent-runtime 不能仅凭本地 AUTO_APPROVED 跳过它。</p>
 */
public record AgentAutopilotRecoveryCaseView(
        Long caseId,
        Long syncTaskId,
        Long rootExecutionId,
        Long currentExecutionId,
        String state,
        Long version,
        Integer cycle,
        Integer maxCycles,
        String recoveryAction,
        String attentionReason,
        String authorizationDigest,
        String policyDigest) {
}

/**
 * @Author : Cui
 * @Date: 2026/06/06 23:08
 * @Description DataSmart Govern Backend - GatewayAgentToolPolicyEnvelopeView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.agent;

import lombok.Data;

import java.util.Map;

/**
 * gateway 侧 Agent 工具治理策略评估结果。
 *
 * <p>该对象来自 permission-admin 远程评估，或 gateway 本地保守 fallback。
 * 它最终不会原样进入 Header，而是会由 `GatewayAgentToolPolicyEnvelopeFactory` 再做一次白名单裁剪，
 * 只保留 Python Runtime 可以消费的 `toolCallBudget` 与 `toolExecutionReadinessPolicy`。</p>
 */
@Data
public class GatewayAgentToolPolicyEnvelopeView {

    /**
     * 当前策略是否允许继续进入 Python Runtime。
     *
     * <p>如果 permission-admin 明确返回 false，gateway 不应继续转发请求。
     * 这给未来“租户冻结、项目冻结、workspace 锁定、策略强制阻断”预留了控制面刹车。</p>
     */
    private Boolean allowed;

    /**
     * 策略来源。
     */
    private String policySource;

    /**
     * 策略版本。
     */
    private String policyVersion;

    /**
     * 模型工具调用预算。
     *
     * <p>Map 中只应包含 maxProposedToolCalls、maxAutoExecutableToolCalls、maxHighRiskToolCalls、
     * maxSingleArgumentsBytes、maxTotalArgumentsBytes。即使远程响应出现其他字段，factory 也会继续裁剪。</p>
     */
    private Map<String, Integer> toolCallBudget;

    /**
     * 工具执行准备度策略。
     */
    private GatewayAgentToolExecutionReadinessPolicyView toolExecutionReadinessPolicy;
}

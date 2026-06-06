/**
 * @Author : Cui
 * @Date: 2026/06/06 12:40
 * @Description DataSmart Govern Backend - AgentA2aTaskTransitionView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * A2A Task 状态流转视图。
 *
 * <p>状态流转是 Agent 产品最容易“看起来简单、实际很危险”的部分。例如 `working -> canceled` 并不一定意味着
 * worker 已经立即停下：如果工具副作用已经进入下游系统，取消请求只能先变成 cancel-requested，再由 worker
 * 判断是否可安全停止或是否需要补偿。因此每条流转都必须写明触发条件、守卫规则、内部阶段和回放策略。</p>
 *
 * @param fromState 起始 A2A 状态。终态不会出现在这里
 * @param toState 目标 A2A 状态
 * @param trigger 触发事件或产品动作，例如 task.accepted、approval.denied、client.cancel.requested
 * @param allowed 是否允许该流转。当前预览只返回允许流转；保留字段便于后续展示被禁止流转
 * @param guardrail 流转守卫条件，解释哪些权限、审批、幂等、worker 或副作用约束必须满足
 * @param datasmartInternalPhases 流转过程中可能经过的内部阶段编码
 * @param replayPolicy 回放策略，说明 runtime event、幂等键和补偿如何支撑断线恢复
 * @param timeoutPolicy 超时策略，说明超过等待窗口后如何进入 failed、rejected 或 canceled
 * @param eventPolicy 事件策略，明确只允许低敏状态摘要进入通用 timeline
 * @param notes 补充说明，通常用于解释为什么当前不直接执行或为什么要等待审批/输入
 */
public record AgentA2aTaskTransitionView(
        String fromState,
        String toState,
        String trigger,
        boolean allowed,
        String guardrail,
        List<String> datasmartInternalPhases,
        String replayPolicy,
        String timeoutPolicy,
        String eventPolicy,
        List<String> notes
) {
}

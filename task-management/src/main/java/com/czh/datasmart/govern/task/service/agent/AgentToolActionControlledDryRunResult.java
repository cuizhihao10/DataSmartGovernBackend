/**
 * @Author : Cui
 * @Date: 2026/06/11 22:00
 * @Description DataSmart Govern Backend - AgentToolActionControlledDryRunResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import java.util.List;
import java.util.Map;

/**
 * 受控工具动作单次调度结果。
 *
 * <p>类名中仍保留 DryRun，是为了兼容已有 controller、测试和运维入口命名；但 5.115 之后该结果已经可以表达
 * “审批后真实 Host 提交成功”。因此调用方不要再把它简单理解为永远不产生副作用，而应查看
 * {@code sideEffectExecuted} 和 {@code outcome} 判断本轮到底停在 dry-run/pre-check，还是已经完成受控提交。</p>
 *
 * <p>该结果面向内部运维入口和单元测试，表达“本轮有没有认领任务、做了哪些前置判断、最终把任务如何处置”。
 * 它不包含 payload body、工具参数值、SQL、prompt、模型输出、凭证或内部 endpoint。</p>
 *
 * @param claimed 本轮是否认领到 `AGENT_TOOL_ACTION_CONTROLLED` 任务。
 * @param taskId 被认领的任务 ID。
 * @param runId 本次 task_execution_run ID。
 * @param commandId agent-runtime writer 生成的 commandId。
 * @param toolCode 工具编码。
 * @param outcome 本轮结果，例如 NO_TASK、DEFERRED_WAITING_PAYLOAD_BODY、FAILED_PRECHECK。
 * @param preCheckPassed 低敏契约 pre-check 是否通过。
 * @param sideEffectExecuted 是否执行了真实业务副作用；只有命中受控 Host submit 且 receipt 被接受后才可能为 true。
 * @param message 人读说明。
 * @param diagnostics 低敏诊断数据。
 * @param recommendedActions 后续建议动作。
 * @param receiptDelivery receipt 回写 agent-runtime timeline 的投递摘要；它只表达是否投递成功，不包含事件正文。
 */
public record AgentToolActionControlledDryRunResult(
        Boolean claimed,
        Long taskId,
        Long runId,
        String commandId,
        String toolCode,
        String outcome,
        Boolean preCheckPassed,
        Boolean sideEffectExecuted,
        String message,
        Map<String, Object> diagnostics,
        List<String> recommendedActions,
        AgentToolActionControlledReceiptDelivery receiptDelivery
) {

    public AgentToolActionControlledDryRunResult {
        claimed = Boolean.TRUE.equals(claimed);
        preCheckPassed = Boolean.TRUE.equals(preCheckPassed);
        sideEffectExecuted = Boolean.TRUE.equals(sideEffectExecuted);
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
        recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
        receiptDelivery = receiptDelivery == null
                ? AgentToolActionControlledReceiptDelivery.skipped("未尝试回写 dry-run receipt")
                : receiptDelivery;
    }
}

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
 * 受控工具动作 dry-run 单次调度结果。
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
 * @param sideEffectExecuted 是否执行了真实业务副作用；当前 dry-run 永远为 false。
 * @param message 人读说明。
 * @param diagnostics 低敏诊断数据。
 * @param recommendedActions 后续建议动作。
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
        List<String> recommendedActions
) {

    public AgentToolActionControlledDryRunResult {
        claimed = Boolean.TRUE.equals(claimed);
        preCheckPassed = Boolean.TRUE.equals(preCheckPassed);
        sideEffectExecuted = Boolean.TRUE.equals(sideEffectExecuted);
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
        recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
    }
}

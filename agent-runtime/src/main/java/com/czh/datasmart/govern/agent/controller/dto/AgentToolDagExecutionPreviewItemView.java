/**
 * @Author : Cui
 * @Date: 2026/05/31 23:40
 * @Description DataSmart Govern Backend - AgentToolDagExecutionPreviewItemView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 单个 DAG 节点的执行预览项。
 *
 * <p>该 DTO 不代表工具已经执行，也不代表 Java 已经创建异步任务。
 * 它只是把 DAG、execution-policy、同步自动执行守卫、异步 command plan 四类只读事实合并起来，
 * 告诉调用方“如果下一阶段要推进这个节点，应该走哪条受控路径，以及当前还缺什么条件”。</p>
 *
 * @param nodeId DAG 节点 ID，来自 Python `planNodeId` 或 Java 兼容生成值。
 * @param auditId 工具审计 ID，真实审批、执行、结果查询仍以 auditId 定位。
 * @param toolCode 工具编码。
 * @param state 当前工具审计状态。
 * @param executionMode 工具执行模式。
 * @param executionDecision execution-policy 给出的当前策略决策。
 * @param previewAction DAG-aware preview 给出的下一步动作分类。
 * @param executionPath 如果未来触发执行，应走的受控入口说明；当前 preview 不会调用该入口。
 * @param readyForExecution DAG 依赖和 execution-policy 是否已让该节点进入 ready 候选。
 * @param wouldTriggerSideEffect 如果未来按该预览真实执行，是否可能产生下游副作用。
 * @param parallelGroup 并行组提示，后续调度器可按它和租户配额做批次划分。
 * @param riskLevel 工具风险等级。
 * @param readOnly 是否只读。
 * @param idempotent 是否幂等。
 * @param requiresApproval 是否要求审批。
 * @param asyncDispatchable 异步命令草案是否可下发；非异步工具为 false。
 * @param asyncCommandId 异步命令 ID；只有 ASYNC_TASK 且存在命令草案时有值。
 * @param blockedByNodeIds 当前 DAG 前置阻断节点。
 * @param reasons 预览判断原因。
 * @param recommendedActions 推荐下一步动作。
 */
public record AgentToolDagExecutionPreviewItemView(
        String nodeId,
        String auditId,
        String toolCode,
        String state,
        String executionMode,
        String executionDecision,
        String previewAction,
        String executionPath,
        Boolean readyForExecution,
        Boolean wouldTriggerSideEffect,
        String parallelGroup,
        String riskLevel,
        Boolean readOnly,
        Boolean idempotent,
        Boolean requiresApproval,
        Boolean asyncDispatchable,
        String asyncCommandId,
        List<String> blockedByNodeIds,
        List<String> reasons,
        List<String> recommendedActions
) {
}

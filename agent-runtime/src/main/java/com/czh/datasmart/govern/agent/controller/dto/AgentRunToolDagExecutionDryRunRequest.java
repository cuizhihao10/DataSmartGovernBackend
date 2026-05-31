/**
 * @Author : Cui
 * @Date: 2026/05/31 23:58
 * @Description DataSmart Govern Backend - AgentRunToolDagExecutionDryRunRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent Run 级 DAG-aware 执行干运行请求。
 *
 * <p>这个请求用于“真实推进 DAG 工具节点之前”的第二层确认。它和只读
 * {@code dag-execution-preview} 的区别是：preview 面向全量观察，告诉调用方每个节点当前处于什么执行建议；
 * dry-run 面向一次拟执行批次，允许前端、Python Runtime 或未来智能网关明确传入想推进的 nodeId/auditId，
 * 服务端再把这些节点翻译成“如果真实执行，将调用哪个既有受控入口”。</p>
 *
 * <p>重要边界：本请求即使命中可执行候选，也不会执行工具、不会创建 task-management 任务、不会写 outbox、
 * 不会投递 Kafka，也不会推进审计状态。它的商业化价值在于让高自动化 Agent 先具备“行动预案透明度”，
 * 再进入人工确认、策略授权或后台 worker 调度，避免模型一次性直接触发不可回滚副作用。</p>
 *
 * @param nodeIds 可选 DAG 节点 ID 白名单；适合前端从 DAG 图上选中 ready 节点后提交。
 * @param auditIds 可选工具审计 ID 白名单；适合后端或 Python Runtime 已持有 auditId 时精确提交。
 * @param maxNodes 本次 dry-run 最多纳入多少个已匹配节点；用于防止一次请求把大 DAG 全部推入拟执行批次。
 * @param includeUnselectedPreviewItems 是否把未选中的 preview 节点也返回；默认 false，减少 Agent loop 的响应体噪音。
 */
public record AgentRunToolDagExecutionDryRunRequest(
        List<String> nodeIds,
        List<String> auditIds,
        Integer maxNodes,
        Boolean includeUnselectedPreviewItems
) {
}

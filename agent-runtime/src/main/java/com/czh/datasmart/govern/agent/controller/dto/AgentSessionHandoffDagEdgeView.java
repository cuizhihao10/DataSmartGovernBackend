/**
 * @Author : Cui
 * @Date: 2026/06/05 00:00
 * @Description DataSmart Govern Backend - AgentSessionHandoffDagEdgeView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * Master Agent 交接 DAG 的有向边视图。
 *
 * <p>边表达“控制面建议的前后依赖”，不是实际网络调用链。比如 MASTER_TO_SPECIALIST 表示 Master
 * 应先把会话意图拆解给专家 Agent；TOOL_TO_FEEDBACK 表示工具治理完成后，结果应回到反馈/二轮推理阶段。
 * 先把边做成只读契约，可以避免早期直接绑定某一种 Agent 框架或协议实现。</p>
 *
 * @param fromNodeId 起点节点 ID。
 * @param toNodeId 终点节点 ID。
 * @param edgeType 边类型，用于前端分组展示和后续执行器解释。
 * @param reason 中文解释，说明为什么这两个节点存在依赖关系。
 */
public record AgentSessionHandoffDagEdgeView(
        String fromNodeId,
        String toNodeId,
        String edgeType,
        String reason
) {
}

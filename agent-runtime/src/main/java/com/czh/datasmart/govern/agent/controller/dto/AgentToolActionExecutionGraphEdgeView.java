/**
 * @Author : Cui
 * @Date: 2026/06/07 14:27
 * @Description DataSmart Govern Backend - AgentToolActionExecutionGraphEdgeView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * 工具动作执行图中的有向边。
 *
 * <p>边表达的是“治理依赖关系”，不是网络调用链。例如 READINESS_TO_APPROVAL 表示 readiness 判断发现需要人工确认，
 * 因此必须先产生审批事实；OUTBOX_TO_WORKER_RECEIPT 表示只有持久化命令真正入箱并被 worker 接单后，才允许进入
 * worker receipt 阶段。这样设计可以避免把预览图误解成已经开始执行。</p>
 *
 * @param fromNodeId 起点节点 ID。
 * @param toNodeId 终点节点 ID。
 * @param edgeType 边类型，用于前端分组展示和后续执行器解释。
 * @param condition 该边成立的条件说明，例如 ALWAYS、WHEN_APPROVAL_REQUIRED、WHEN_CONTRACT_READY。
 * @param reason 中文解释，说明两个节点之间为什么存在先后关系。
 */
public record AgentToolActionExecutionGraphEdgeView(
        String fromNodeId,
        String toNodeId,
        String edgeType,
        String condition,
        String reason
) {
}

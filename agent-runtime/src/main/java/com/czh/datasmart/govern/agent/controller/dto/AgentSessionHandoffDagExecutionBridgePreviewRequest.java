/**
 * @Author : Cui
 * @Date: 2026/06/05 00:00
 * @Description DataSmart Govern Backend - AgentSessionHandoffDagExecutionBridgePreviewRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Master Agent handoff DAG 到 Tool DAG 执行链路的桥接预检请求。
 *
 * <p>该请求不是执行请求，而是“把会话级 handoff DAG 节点翻译为现有工具 DAG dry-run 选择器”的请求。
 * 当前系统已经有成熟的工具 dry-run、selected-node confirmation 和 command outbox，如果 handoff DAG
 * 再单独发明一套执行入口，会造成权限、审计、幂等、限流和 outbox 语义分裂。因此本请求只允许调用方
 * 提供低敏选择意图，由 Java 控制面重新走现有 dry-run 链路生成下一步模板。</p>
 *
 * @param handoffNodeIds 调用方在 handoff DAG 上选中的节点 ID，例如 tool-control、feedback、second-turn。
 *                       第一版只有 tool-control 会映射到工具 dry-run；其他节点会返回解释，不产生副作用。
 * @param toolNodeIds 可选的 Tool DAG nodeId 选择器。如果为空且 handoffNodeIds 包含 tool-control，则使用 dry-run 默认候选规则。
 * @param toolAuditIds 可选的工具审计 ID 选择器。用于前端已经从 Tool DAG 视图里选中具体工具节点的场景。
 * @param maxNodes 本次最多预检多少个工具节点；服务端仍会应用 dry-run 自身的默认值和硬上限。
 * @param includeUnselectedPreviewItems 是否在 dry-run 响应中包含未被选中的 preview 项，通常用于诊断界面。
 * @param buildSelectedNodeOutboxTemplate 是否生成 selected-node outbox 入箱请求模板。模板只用于展示和下一步调用建议，不会自动提交。
 */
public record AgentSessionHandoffDagExecutionBridgePreviewRequest(
        List<String> handoffNodeIds,
        List<String> toolNodeIds,
        List<String> toolAuditIds,
        Integer maxNodes,
        Boolean includeUnselectedPreviewItems,
        Boolean buildSelectedNodeOutboxTemplate
) {
}

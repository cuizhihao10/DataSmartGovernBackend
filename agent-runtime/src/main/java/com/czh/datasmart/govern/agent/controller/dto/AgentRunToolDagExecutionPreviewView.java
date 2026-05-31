/**
 * @Author : Cui
 * @Date: 2026/05/31 23:41
 * @Description DataSmart Govern Backend - AgentRunToolDagExecutionPreviewView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent Run 级 DAG-aware 执行预览。
 *
 * <p>这是 DAG 真正自动执行之前的安全观察窗。它把 Java 4.57 的 `dag-plan` 与已有
 * `execution-policy`、`async-command-plans` 合并成执行建议，但不会调用工具、不会创建任务、
 * 不会投递 Kafka，也不会推进审计状态。后续前端、Python Runtime 或自动调度器可以先读取该视图，
 * 再根据租户策略、服务间授权和用户确认决定是否调用真实执行入口。</p>
 *
 * @param sessionId Agent 会话 ID。
 * @param runId Agent Run ID。
 * @param dryRunOnly 标识该接口只做 dry-run 预览。
 * @param totalNodes DAG 节点总数。
 * @param readyNodeCount DAG ready 节点数。
 * @param syncAutoExecutableCount 可进入现有同步自动执行入口的节点数。
 * @param asyncDispatchableCount 可进入异步 command dispatcher 的节点数。
 * @param humanActionCount 需要人工审批、复核或草稿确认的节点数。
 * @param blockedCount 当前被依赖、参数、终态或策略阻断的节点数。
 * @param unsupportedCount 当前没有可用执行路径的节点数。
 * @param hasExecutableCandidates 是否存在至少一个可推进候选。
 * @param summaryReasons Run 级解释。
 * @param recommendedActions Run 级推荐动作。
 * @param items 节点级预览项。
 */
public record AgentRunToolDagExecutionPreviewView(
        String sessionId,
        String runId,
        Boolean dryRunOnly,
        Integer totalNodes,
        Integer readyNodeCount,
        Integer syncAutoExecutableCount,
        Integer asyncDispatchableCount,
        Integer humanActionCount,
        Integer blockedCount,
        Integer unsupportedCount,
        Boolean hasExecutableCandidates,
        List<String> summaryReasons,
        List<String> recommendedActions,
        List<AgentToolDagExecutionPreviewItemView> items
) {
}

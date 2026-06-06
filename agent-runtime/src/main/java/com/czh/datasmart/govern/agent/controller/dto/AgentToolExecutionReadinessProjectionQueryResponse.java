/**
 * @Author : Cui
 * @Date: 2026/06/06 00:00
 * @Description DataSmart Govern Backend - AgentToolExecutionReadinessProjectionQueryResponse.java
 * @Version:1.0.0
 *
 * <p>5.44 新增的 `graphBranchCounts` 只聚合低敏图谱分支名，用来快速判断本次窗口主要卡在
 * READY、审批、澄清、预算等待还是执行前阻断，不读取完整 graph nodes/edges 或工具参数。</p>
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * 工具执行准备度投影查询响应。
 *
 * <p>响应除了返回快照列表，还提供本次查询窗口的低敏聚合摘要，让管理台无需遍历自由 attributes 就能
 * 回答：多少次可执行、多少次等待审批、多少次需要澄清、多少次被限流或阻断，以及常见工具与决策分布。</p>
 *
 * <p>当前聚合只基于本次返回窗口，不代表全量历史报表。后续如果接入 dedicated index，可以继续使用
 * 相同字段承载数据库聚合结果。</p>
 */
public record AgentToolExecutionReadinessProjectionQueryResponse(
        Integer limit,
        Integer totalMatched,
        String indexSource,
        Long executableWindowCount,
        Long approvalRequiredWindowCount,
        Long clarificationRequiredWindowCount,
        Long draftOnlyWindowCount,
        Long queuedAsyncWindowCount,
        Long throttledWindowCount,
        Long blockedWindowCount,
        Map<String, Long> decisionCounts,
        Map<String, Long> graphBranchCounts,
        Map<String, Long> toolNameCounts,
        Map<String, Long> nextActionCounts,
        List<AgentToolExecutionReadinessProjectionView> snapshots
) {
}

/**
 * @Author : Cui
 * @Date: 2026/07/02 00:00
 * @Description DataSmart Govern Backend - AgentExecutionSessionProjectionQueryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * 受控多 Agent 执行会话投影查询响应。
 *
 * <p>响应不只返回事件列表，还返回当前窗口内的低敏聚合摘要。真实管理台通常需要先回答：
 * 有多少会话等待审批、多少等待控制面反馈、多少被恢复阻断、哪些 deliveryTier/resumeAction
 * 最常出现、必做 Agent 是否大量 standby。聚合字段让前端和审计导出不必解析自由 Map。</p>
 *
 * <p>当前聚合基于本次返回窗口，不代表全量历史报表。后续如果迁移到 MySQL、ClickHouse 或审计中心，
 * 可以保持该 API 契约不变，把聚合下沉到持久索引。</p>
 */
public record AgentExecutionSessionProjectionQueryResponse(
        /** 服务端应用后的查询上限，默认和最大值由 AgentRuntimeEventProjectionQuery 控制。 */
        Integer limit,

        /** 本次窗口命中的 execution session 事件数。 */
        Integer totalMatched,

        /** 当前数据来源。第一版使用通用 runtime event 热投影，后续可扩展为 dedicated index。 */
        String indexSource,

        Long waitingApprovalOrHandoffCount,
        Long waitingControlPlaneFeedbackCount,
        Long blockedWaitingRecoveryCount,
        Long readyForAgentTurnsCount,
        Long readyForControlPlaneHandoffCount,
        Long degradedDraftOnlyCount,
        Long completedOrSummarizedCount,
        Long handoffRequiredSessionCount,
        Map<String, Long> statusCounts,
        Map<String, Long> durablePhaseCounts,
        Map<String, Long> deliveryTierCounts,
        Map<String, Long> resumeActionCounts,
        Map<String, Long> activeMustDoRoleCounts,
        Map<String, Long> standbyMustDoRoleCounts,
        List<AgentExecutionSessionProjectionView> snapshots
) {
}

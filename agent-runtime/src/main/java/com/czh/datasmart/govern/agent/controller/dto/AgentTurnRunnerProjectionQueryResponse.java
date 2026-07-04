/**
 * @Author : Cui
 * @Date: 2026/07/02 18:08
 * @Description DataSmart Govern Backend - AgentTurnRunnerProjectionQueryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * 受控多 Agent Turn Runner 投影查询响应。
 *
 * <p>响应同时返回快照列表和当前窗口聚合摘要。聚合摘要不是为了替代 Prometheus，而是为了让管理台、审计台
 * 和 E2E 验收脚本快速判断：当前多 Agent turn 是否大量卡在审批、控制面 handoff、worker receipt 或恢复
 * 事实缺失上。Prometheus 更适合趋势告警，本接口更适合按 run/session/request 做交互式排障。</p>
 *
 * <p>所有聚合都基于本次返回窗口，后续如果建立 dedicated MySQL/ClickHouse 索引，可以保持该响应结构不变，
 * 把聚合逻辑下沉到数据库查询层。</p>
 */
public record AgentTurnRunnerProjectionQueryResponse(
        /** 服务端应用后的查询上限，默认和最大值由 AgentRuntimeEventProjectionQuery 控制。 */
        Integer limit,

        /** 本次窗口命中的 agent_turn_runner_recorded 事件数。 */
        Integer totalMatched,

        /** 当前数据来源。第一版复用通用 runtime event 热投影，后续可替换为专用索引。 */
        String indexSource,

        Long waitingRunnerCount,
        Long blockedRunnerCount,
        Long readyRunnerCount,
        Long sideEffectViolationCount,
        Long waitingAttemptTotal,
        Long blockedAttemptTotal,
        Long controlPlaneHandoffAttemptTotal,
        Long managerAsToolsTotal,
        Long checkpointLinkedCount,
        Map<String, Long> statusCounts,
        Map<String, Long> runStatusCounts,
        Map<String, Long> sessionStatusCounts,
        Map<String, Long> durablePhaseCounts,
        Map<String, Long> checkpointStatusCounts,
        Map<String, Long> checkpointNodeCounts,
        Map<String, Long> checkpointGraphCounts,
        Map<String, Long> checkpointResumeRequirementCounts,
        Map<String, Long> turnStatusCounts,
        Map<String, Long> deliveryTierCounts,
        Map<String, Long> resumeActionCounts,
        Map<String, Long> requiredEvidenceCounts,
        Map<String, Long> nextActionCounts,
        List<AgentTurnRunnerProjectionView> snapshots
) {
}

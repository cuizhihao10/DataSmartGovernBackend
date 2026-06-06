/**
 * @Author : Cui
 * @Date: 2026/06/05 00:00
 * @Description DataSmart Govern Backend - AgentSessionSchedulingProjectionQueryResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;
import java.util.Map;

/**
 * 多 Agent 会话调度投影查询响应。
 *
 * <p>该响应不只返回事件列表，还返回本次窗口的低敏聚合摘要。真实管理台通常先需要回答：
 * 本轮或某段时间内调度了多少次、多少次需要 handoff、是否出现 BLOCKED、哪些 Agent 角色经常参与、
 * 哪些工具或 Skill 经常触发调度。聚合字段让前端不必自己遍历自由 Map。</p>
 *
 * <p>当前聚合只基于本次返回窗口，不代表全量历史报表。后续如果增加 MySQL/ClickHouse 专用索引，
 * 可以把相同字段迁移为数据库聚合，API 契约保持不变。</p>
 */
public record AgentSessionSchedulingProjectionQueryResponse(
        /**
         * 服务端应用后的查询上限。默认 100，最大值由 `AgentRuntimeEventProjectionQuery` 约束。
         */
        Integer limit,

        /**
         * 本次返回窗口命中的调度事件数量。
         */
        Integer totalMatched,

        /**
         * 本次查询使用的数据来源。当前为 `runtime-event-projection-fallback`，表示从通用投影热窗口读取。
         * 后续补 dedicated index 后可以扩展为 `dedicated-agent-session-scheduling-index`。
         */
        String indexSource,

        Long readyCount,
        Long degradedCount,
        Long approvalRequiredCount,
        Long blockedCount,
        Long handoffRequiredCount,

        /**
         * 本次返回窗口中携带 A2A task planning 事实的调度事件数量。
         *
         * <p>它用于回答“这些多 Agent 会话里，有多少是由外部 A2A task 委派状态驱动的”。</p>
         */
        Long a2aTaskPlanningCount,

        /**
         * A2A planning mode 分布，例如 WAIT_FOR_AUTHORIZATION、WAIT_FOR_USER_INPUT、REJECTED_OR_DIAGNOSTIC。
         */
        Map<String, Long> a2aTaskPlanningModeCounts,

        /**
         * A2A 标准 task state 分布，例如 TASK_STATE_AUTH_REQUIRED、TASK_STATE_UNSPECIFIED。
         */
        Map<String, Long> a2aTaskStateCounts,

        Map<String, Long> primaryAgentRoleCounts,
        Map<String, Long> participatingAgentRoleCounts,
        Map<String, Long> intentDomainCounts,
        Map<String, Long> plannedToolNameCounts,
        Map<String, Long> selectedSkillCodeCounts,
        List<AgentSessionSchedulingProjectionView> snapshots
) {
}

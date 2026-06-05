/**
 * @Author : Cui
 * @Date: 2026/06/05 00:00
 * @Description DataSmart Govern Backend - AgentSessionSchedulingProjectionView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 多 Agent 会话调度 runtime event 的 Java 控制面视图。
 *
 * <p>Python AI Runtime 会把 `intelligentGatewayGovernance.agentSessionScheduling` 压缩为
 * `agent_session_scheduling_recorded` 事件。该 DTO 的职责是把自由 Map attributes 转换为强类型、
 * 低敏、适合管理台和审计台消费的视图。</p>
 *
 * <p>为什么需要专用视图，而不是让前端直接解析通用 runtime event：</p>
 * <p>1. 多 Agent 调度是产品语义，不只是技术日志。前端需要稳定字段展示主控 Agent、专家 Agent、handoff 和降级原因；</p>
 * <p>2. attributes 是跨语言自由 Map，Java 控制面应负责类型兜底、默认值和字段裁剪；</p>
 * <p>3. 后续如果 Python 事件 payload 升级，服务层可以兼容新旧字段，避免前端、审计导出和报表全部跟着改。</p>
 *
 * <p>安全边界：本视图只返回 Agent 角色、工具名、Skill code、治理域、记忆类型、状态和计数等低敏控制面事实。
 * 它不会返回用户 objective、prompt、SQL、工具参数、样本数据、模型输出、完整推荐动作或长期记忆正文。</p>
 */
public record AgentSessionSchedulingProjectionView(
        /**
         * Java 投影层生成的幂等键。用于排查重复消费，不作为业务对象 ID。
         */
        String identityKey,

        /**
         * Python Runtime 事件契约版本，例如 `agent-runtime-event.v1`。
         */
        String schemaVersion,

        /**
         * 事件来源服务。当前通常为 `python-ai-runtime`。
         */
        String source,

        /**
         * 固定为 `agent_session_scheduling_recorded`，用于调用方确认视图来源。
         */
        String eventType,

        String severity,
        String tenantId,
        String projectId,
        String actorId,
        String requestId,
        String runId,
        String sessionId,

        /**
         * Python 生产者原始 sequence，只保证生产方局部有序。
         */
        Long sequence,

        /**
         * Java 控制面分配的 replay 游标。断线恢复、增量查询和审计回放应优先使用它。
         */
        Long replaySequence,

        Instant createdAt,
        Instant consumedAt,

        /**
         * 当前事件 payload 版本。用于服务层未来兼容 v2/v3 结构。
         */
        String eventPayloadVersion,

        /**
         * 固定为 `AGENT_SESSION_SCHEDULING_POLICY_VIEW`，表示这是多 Agent 会话调度策略视图。
         */
        String snapshotType,

        /**
         * 本轮调度是否可用。可用不等于所有工具可自动执行，只代表没有进入 BLOCKED。
         */
        Boolean available,

        /**
         * 调度状态：READY、DEGRADED、APPROVAL_REQUIRED 或 BLOCKED。
         */
        String status,

        /**
         * 主控 Agent 角色，当前通常为 MASTER_ORCHESTRATOR。
         */
        String primaryAgentRole,

        /**
         * 本轮参与 Agent 总数。
         */
        Integer participatingAgentCount,

        /**
         * 参与 Agent 角色列表。Python 侧已做数量裁剪，Java 只按低敏列表展示。
         */
        List<String> participatingAgentRoles,

        /**
         * 因裁剪未进入事件的 Agent 角色数量。
         */
        Integer participatingAgentRolesTruncatedCount,

        /**
         * 参与模式分布，例如 PRIMARY/SPECIALIST/GUARDRAIL/OBSERVER。
         */
        Map<String, Integer> participationModeCounts,

        /**
         * Agent 状态分布，例如 READY/DEGRADED/APPROVAL_REQUIRED/BLOCKED。
         */
        Map<String, Integer> agentStatusCounts,

        /**
         * 是否存在需要 Java 控制面审批、人工接管或后续 handoff 的 Agent。
         */
        Boolean handoffRequired,

        /**
         * 需要 handoff 的 Agent 角色列表。
         */
        List<String> handoffAgentRoles,

        List<String> intentDomains,
        List<String> selectedSkillCodes,
        List<String> visibleSkillCodes,
        List<String> plannedToolNames,
        List<String> memoryDependencies,
        Boolean modelGatewayAvailable,
        Boolean skillAdmissionAllowed,
        Boolean toolBudgetAllowed,
        Boolean approvalRequired,
        Boolean tenantScoped,
        Boolean projectScoped,
        String displaySummary,
        Integer recommendedActionCount
) {
}

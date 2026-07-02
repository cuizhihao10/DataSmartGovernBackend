/**
 * @Author : Cui
 * @Date: 2026/07/02 18:08
 * @Description DataSmart Govern Backend - AgentTurnRunnerAttemptProjectionView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 受控多 Agent Turn Runner 中单个 Agent turn attempt 的 Java 控制面视图。
 *
 * <p>该 DTO 固定来自 Python Runtime 的 `agent_turn_runner_recorded` 事件。它表达的是“下一轮 Agent
 * 工作项是否具备继续推进的控制面条件”，而不是某个 Agent 已经真实执行完毕的业务结果。真实执行仍必须由
 * Java outbox、审批事实、worker receipt、checkpoint 和审计链路共同确认。</p>
 *
 * <p>字段全部采用白名单方式暴露：只保留角色、状态、恢复动作、证据缺口和数量统计，不保留 prompt、SQL、
 * 工具参数、样本数据、模型输出、token、内部 endpoint、payloadReference 正文、checkpointId 或 commandId。
 * 这样即使 Python event attributes 未来临时追加了调试字段，管理台和审计导出也不会被动泄露敏感数据。</p>
 */
public record AgentTurnRunnerAttemptProjectionView(
        /** turn attempt 的低敏编号；它用于页面展示和日志关联，不应作为权限判断或幂等主键。 */
        String turnId,

        /** 来源 work item 编号，帮助控制面把 turn attempt 与 agentExecutionSession.workItems 对齐。 */
        String workItemId,

        /** Agent 角色，例如 MASTER_ORCHESTRATOR、DATA_QUALITY_AGENT、PERMISSION_AGENT。 */
        String agentRole,

        /** 交付层级：must_do、controlled_scope、lightweight 或 runtime_governance。 */
        String deliveryTier,

        /** 当前 turn 推进状态，例如 WAITING_APPROVAL、READY_FOR_CONTROL_PLANE_HANDOFF、BLOCKED。 */
        String turnStatus,

        /** 下一步恢复动作编码，供 Java 控制面或人工操作台选择后续处理入口。 */
        String resumeAction,

        /** manager-as-tools 视角下的虚拟工具名，只描述“主控如何调度子 Agent”，不是可执行工具。 */
        String managerToolName,

        /** 真实推进前必须补齐的 host fact 编码，例如 outbox、worker receipt、approval decision。 */
        List<String> requiredEvidenceCodes,

        /** 等待原因编码，用于管理台解释为什么本 attempt 暂时不能继续。 */
        List<String> waitingReasonCodes,

        /** 阻断原因编码，只允许稳定 code，不允许异常堆栈或业务正文。 */
        List<String> blockedBy,

        /** 计划工具数量，只保留计数，不保留工具名、参数或执行结果。 */
        Integer plannedToolCount,

        /** 当前 Agent 可见 Skill 数量，只保留计数，避免把 Skill 描述当作运行时 payload 暴露。 */
        Integer visibleSkillCount,

        /** 记忆依赖数量，只保留计数，不返回 memoryId、namespace 或记忆正文。 */
        Integer memoryDependencyCount,

        /** payload 策略声明，用于审计确认该 attempt 只包含低敏控制面字段。 */
        String payloadPolicy
) {
}

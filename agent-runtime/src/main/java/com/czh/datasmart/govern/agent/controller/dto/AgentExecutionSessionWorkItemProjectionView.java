/**
 * @Author : Cui
 * @Date: 2026/07/02 00:00
 * @Description DataSmart Govern Backend - AgentExecutionSessionWorkItemProjectionView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 受控多 Agent 执行会话中的单个 Agent work item 投影视图。
 *
 * <p>Python Runtime 在 `agentExecutionSession.workItems` 中已经完成低敏裁剪；Java 控制面仍然再次使用
 * 强类型白名单，只读取角色、状态、恢复动作、依赖角色和数量字段。这样即使未来 Python work item
 * 临时增加了工具参数、SQL、样本数据或模型输出，Java 投影也不会把这些字段带到管理台或审计导出。</p>
 *
 * <p>该 DTO 的业务定位不是“真实执行任务”，而是“执行会话状态事实”：它告诉控制面某个 Agent 当前
 * 等待审批、等待控制面反馈、可进入二轮、只允许草案还是可交给 Java outbox。真正执行仍必须由
 * permission、readiness、outbox、worker receipt 和审计链路共同确认。</p>
 */
public record AgentExecutionSessionWorkItemProjectionView(
        /** 会话内 work item 编号；它不是数据库主键，也不应作为权限判断依据。 */
        String workItemId,

        /** Agent 运行角色，例如 MASTER_ORCHESTRATOR、DATA_QUALITY_AGENT、PERMISSION_AGENT。 */
        String agentRole,

        /** 交付层级：must_do、controlled_scope、lightweight 或 runtime_governance。 */
        String deliveryTier,

        /** 参与模式：PRIMARY、SPECIALIST、GUARDRAIL、OBSERVER 等。 */
        String participationMode,

        /** work item 当前会话状态，例如 WAITING_APPROVAL_OR_HANDOFF 或 READY_FOR_AGENT_TURN。 */
        String sessionStatus,

        /** 下一步恢复动作编码，供 Java 控制面、runner 或人工操作台识别。 */
        String resumeAction,

        /** 执行泳道摘要，用于区分主控、专家、权限守门、观察者等产品语义。 */
        String executionLane,

        /** 当前 work item 依赖的上游 Agent 角色，只保留角色名，不保留输出正文。 */
        List<String> dependsOnRoles,

        /** 是否需要 Java 控制面审批、人工接管或 Agent 间 handoff。 */
        Boolean handoffRequired,

        /** 计划工具数量，只保留计数，不保留工具参数。 */
        Integer plannedToolCount,

        /** 可见 Skill 数量，只保留计数，不保留完整 Skill 描述。 */
        Integer visibleSkillCount,

        /** 记忆依赖数量，只保留计数，不保留 memoryId、namespace 或记忆正文。 */
        Integer memoryDependencyCount,

        /** 等待原因码，例如 CONTROL_PLANE_FEEDBACK_NOT_COLLECTED。 */
        List<String> waitingReasonCodes,

        /** 阻断或降级原因码，只允许稳定 code，不允许异常堆栈。 */
        List<String> blockedBy,

        /** 对齐 Durable Agent Loop 的阶段，缺失时通常为 not_recorded。 */
        String durablePhase,

        /** Python 执行前计划里的原始低敏状态摘要。 */
        String sourceStatus,

        /** payload 策略声明，用于审计确认该 work item 只含低敏字段。 */
        String payloadPolicy
) {
}

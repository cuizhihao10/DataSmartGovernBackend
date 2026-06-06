/**
 * @Author : Cui
 * @Date: 2026/06/06 00:00
 * @Description DataSmart Govern Backend - AgentA2aTaskSchedulingProjectionView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * A2A task planning 在多 Agent 会话调度投影中的低敏子视图。
 *
 * <p>Python Runtime 5.33 已经把 A2A task planning decision 纳入
 * `agent_session_scheduling_recorded` 事件 attributes。Java 控制面需要把这些自由 Map 字段转换成
 * 稳定 DTO，方便前端 timeline、审计导出和后续 handoff DAG 解释“为什么本轮会话等待授权、等待用户、
 * 进入预检或被诊断阻断”。</p>
 *
 * <p>安全边界非常重要：本 DTO 不包含 taskPublicId、contextPublicId、artifactRef、decisionReason、
 * prompt、工具参数、SQL、样本数据、artifact 正文、模型输出、凭证或内部 endpoint。它只保存 mode、状态、
 * guardrail code 和计数等控制面摘要。</p>
 */
public record AgentA2aTaskSchedulingProjectionView(
        /**
         * 本事件是否携带 A2A task planning 事实。没有携带时，说明该会话不是外部 A2A task 委派场景。
         */
        Boolean available,

        /**
         * planning decision 来源，例如 TRUSTED_CONTROL_PLANE 或 REQUEST_VARIABLES_COMPATIBILITY_PREVIEW。
         * 生产环境应优先使用 TRUSTED_CONTROL_PLANE，兼容来源只适合迁移和本地联调。
         */
        String source,

        /**
         * Python adapter 计算出的规划模式，例如 WAIT_FOR_AUTHORIZATION、WAIT_FOR_USER_INPUT。
         */
        String mode,

        /**
         * planning decision 的产品状态，例如 WAITING_FOR_CONTROL_PLANE、BLOCKED。
         */
        String status,

        /**
         * A2A 标准 task 状态摘要，例如 TASK_STATE_AUTH_REQUIRED。
         */
        String a2aState,

        /**
         * DataSmart 内部治理阶段摘要，例如 APPROVAL_WAITING、INPUT_WAITING 或 DEAD_LETTER。
         */
        String internalPhase,

        Boolean terminal,
        Boolean interrupted,
        Boolean executable,
        Boolean shouldWaitForHuman,

        /**
         * 低敏建议动作编码，例如 REQUEST_AUTHORIZATION、QUERY_TASK_HISTORY。
         */
        List<String> suggestedActions,

        /**
         * 低敏 guardrail code。这里保存 code，不保存自由文本原因或原始 payload。
         */
        List<String> guardrailCodes,

        Integer historyEventCount,
        Integer artifactReferenceCount,
        Integer sensitiveFieldIgnoredCount,
        String payloadPolicy
) {
}

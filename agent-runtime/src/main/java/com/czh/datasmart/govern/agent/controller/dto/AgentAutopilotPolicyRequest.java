/**
 * @Author : Cui
 * @Date: 2026/08/10 16:10
 * @Description DataSmart Govern Backend - AgentAutopilotPolicyRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 用户在首次任务确认时授予的 Autopilot 策略。
 *
 * <p>该请求只表达授权上限，不是模型执行指令。服务端会把动作列表与平台固定白名单求交集，
 * 并把租户、应用、项目、用户、Agent、委派、会话和根 Run 一并写入授权快照。后续模型只能
 * 在这个快照内提出动作，不能靠返回一个相同 action 字符串扩大权限。</p>
 */
public record AgentAutopilotPolicyRequest(
        @Size(max = 24) String executionMode,
        @Min(1) @Max(10) Integer maxRecoveryCycles,
        @Min(5) @Max(1440) Integer maxTotalDurationMinutes,
        @Size(max = 16) String maxAutomaticRiskLevel,
        @Size(max = 16) List<@Size(max = 80) String> allowedRecoveryActions,
        @Size(max = 16) List<@Size(max = 80) String> requireApprovalFor,
        OffsetDateTime expiresAt) {
}

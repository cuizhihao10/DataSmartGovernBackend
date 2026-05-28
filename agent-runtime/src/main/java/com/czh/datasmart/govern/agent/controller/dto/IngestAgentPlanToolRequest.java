/**
 * @Author : Cui
 * @Date: 2026/05/24 00:00
 * @Description DataSmart Govern Backend - IngestAgentPlanToolRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Python AgentPlan 中单个 ToolPlan 的接入请求。
 *
 * <p>这个 DTO 对应 Python AI Runtime 规划出的工具调用节点。
 * 它只描述“Python 认为接下来可能要调用什么工具、为什么调用、参数是什么、治理提示是什么”，
 * 不代表 Java 会立刻执行该工具。
 *
 * <p>核心安全原则：
 * 1. `toolCode` 必须在 Java agent-runtime 工具目录中注册并启用；
 * 2. `riskLevel`、`executionMode`、`requiresHumanApproval` 可以表达 Python 的风险判断，但最终会与 Java 工具目录合并；
 * 3. `arguments` 只作为计划快照进入审计，真实执行前仍由 Java 工具适配器二次校验；
 * 4. `governanceHints` 与 `parameterValidation` 用于审批展示、审计学习和后续策略升级，不作为绕过权限的凭证。
 *
 * @param toolCode 工具编码，例如 datasource.metadata.read、quality.rule.suggest、task.create。
 * @param reason Python 规划器给出的调用理由，便于审批人理解“为什么 Agent 想用这个工具”。
 * @param targetResourceId 可选目标资源 ID，例如数据源 ID、任务 ID、质量规则 ID。
 * @param riskLevel Python 计划判断出的风险等级，支持 LOW/MEDIUM/HIGH/CRITICAL。
 * @param executionMode Python 计划建议的执行模式，例如 SYNC、ASYNC_TASK、DRAFT_ONLY、APPROVAL_REQUIRED。
 * @param requiresHumanApproval Python 计划是否认为该工具必须人工确认。
 * @param arguments 工具参数快照。
 * @param governanceHints 治理提示，例如租户/项目范围、敏感字段、记忆写入策略、缓存范围。
 * @param parameterValidation 参数校验与上下文补齐结果，例如 missingFields、filledFromContext、sensitiveFields。
 */
public record IngestAgentPlanToolRequest(
        @NotBlank(message = "toolCode 不能为空")
        @Size(max = 128, message = "toolCode 最多 128 个字符")
        String toolCode,

        @Size(max = 2000, message = "reason 最多 2000 个字符")
        String reason,

        Long targetResourceId,

        @Size(max = 32, message = "riskLevel 最多 32 个字符")
        String riskLevel,

        @Size(max = 64, message = "executionMode 最多 64 个字符")
        String executionMode,

        Boolean requiresHumanApproval,

        @Size(max = 50, message = "arguments 最多 50 个键")
        Map<@Size(max = 128, message = "参数名最多 128 个字符") String, Object> arguments,

        @Size(max = 50, message = "governanceHints 最多 50 个键")
        Map<@Size(max = 128, message = "治理提示名最多 128 个字符") String, Object> governanceHints,

        @Size(max = 50, message = "parameterValidation 最多 50 个键")
        Map<@Size(max = 128, message = "参数校验字段名最多 128 个字符") String, Object> parameterValidation) {
}

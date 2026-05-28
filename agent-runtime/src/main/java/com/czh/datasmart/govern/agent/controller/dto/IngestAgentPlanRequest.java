/**
 * @Author : Cui
 * @Date: 2026/05/24 00:00
 * @Description DataSmart Govern Backend - IngestAgentPlanRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Python AgentPlan 接入 Java 控制面的请求。
 *
 * <p>该请求的产品定位是“计划入库/入审计”，不是“执行工具”。
 * Python AI Runtime 负责调用模型、Skill、记忆和工具描述符生成 AgentPlan；
 * Java agent-runtime 负责把这个计划纳入平台治理：会话、Run、工具审计、审批、权限边界和后续可回放事件。
 *
 * <p>为什么需要这个接口：
 * 1. 如果 Python 计划直接调用工具，Java 的 permission-admin、gateway、审批、审计都会被绕过；
 * 2. 如果 Java 不保存 Python 计划，后续用户只会看到“Agent 做了什么”，看不到“Agent 为什么这么做”；
 * 3. 如果模型网关、记忆检索、工具参数校验都只留在 Python 日志里，商业化审计和运营排障会断链；
 * 4. 用 Java Run 承接 Python AgentPlan，可以让前端、任务中心、审批流和审计中心围绕统一 runId 工作。
 *
 * @param sessionId 可选已有会话 ID；为空时 Java 会创建新的受控会话。
 * @param tenantId 租户 ID，必须与 Python 请求上下文一致。
 * @param projectId 项目 ID，工具计划和记忆检索默认继承该项目边界。
 * @param workspaceId 工作空间 ID，可为空，表示项目默认工作空间。
 * @param actorId 触发计划的用户、服务账号或系统 actor。
 * @param channel 来源渠道，例如 WEB、API、SYSTEM、KAFKA。
 * @param objective 会话目标，用于新建会话和审计展示。
 * @param userInput 本轮用户输入或 Python Plan 摘要输入，用于创建 Java Run。
 * @param workloadType 模型工作负载类型，例如 AGENT_REASONING。
 * @param idempotencyKey AgentPlan 接入幂等键，用于 HTTP 重试、Kafka 重复消费和跨运行时 ack 丢失后的去重。
 * @param pythonRequestId Python Runtime 本次请求 ID，用于跨运行时追踪。
 * @param stateTrace Python 编排状态轨迹摘要，例如 MODEL_GATEWAY_ROUTED、TOOL_PLANNED。
 * @param responseSummary Python AgentPlan 对用户或上游系统的响应摘要。
 * @param requiresHumanApproval Python 计划整体是否要求人工确认。
 * @param isolationLevel 新建会话时使用的工作空间隔离级别，默认 PROJECT。
 * @param toolPlans Python 规划出的工具计划列表。
 * @param modelGatewayGovernance 模型网关治理摘要，包括 provider、fallback、budget、cacheScope 等。
 * @param memoryPlan 记忆写入/读取计划摘要。
 * @param memoryRetrievalReport 记忆检索报告摘要。
 */
public record IngestAgentPlanRequest(
        @Size(max = 128, message = "sessionId 最多 128 个字符")
        String sessionId,

        @NotNull(message = "tenantId 不能为空")
        Long tenantId,

        @NotNull(message = "projectId 不能为空")
        Long projectId,

        Long workspaceId,

        @NotBlank(message = "actorId 不能为空")
        @Size(max = 128, message = "actorId 最多 128 个字符")
        String actorId,

        @Size(max = 64, message = "channel 最多 64 个字符")
        String channel,

        @NotBlank(message = "objective 不能为空")
        @Size(max = 2000, message = "objective 最多 2000 个字符")
        String objective,

        @NotBlank(message = "userInput 不能为空")
        @Size(max = 8000, message = "userInput 最多 8000 个字符")
        String userInput,

        @Size(max = 64, message = "workloadType 最多 64 个字符")
        String workloadType,

        @Size(max = 128, message = "idempotencyKey 最多 128 个字符")
        String idempotencyKey,

        @Size(max = 128, message = "pythonRequestId 最多 128 个字符")
        String pythonRequestId,

        @Size(max = 50, message = "stateTrace 最多 50 个状态")
        List<@Size(max = 128, message = "stateTrace 单项最多 128 个字符") String> stateTrace,

        @Size(max = 4000, message = "responseSummary 最多 4000 个字符")
        String responseSummary,

        Boolean requiresHumanApproval,

        WorkspaceIsolationLevel isolationLevel,

        @Valid
        @Size(max = 20, message = "单个 AgentPlan 最多接入 20 个工具计划")
        List<IngestAgentPlanToolRequest> toolPlans,

        @Size(max = 50, message = "modelGatewayGovernance 最多 50 个键")
        Map<@Size(max = 128, message = "模型网关治理字段名最多 128 个字符") String, Object> modelGatewayGovernance,

        @Size(max = 50, message = "memoryPlan 最多 50 个键")
        Map<@Size(max = 128, message = "记忆计划字段名最多 128 个字符") String, Object> memoryPlan,

        @Size(max = 50, message = "memoryRetrievalReport 最多 50 个键")
        Map<@Size(max = 128, message = "记忆检索字段名最多 128 个字符") String, Object> memoryRetrievalReport) {
}

/**
 * @Author : Cui
 * @Date: 2026/06/06 12:40
 * @Description DataSmart Govern Backend - AgentA2aTaskStateMachinePreviewResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * A2A Task 状态机只读预览响应。
 *
 * <p>这个 DTO 是后续真实 A2A task endpoint 的“产品合同草案”，不是任务对象本身。它把 A2A 标准状态、
 * DataSmart 内部治理阶段、状态流转、取消/超时/幂等/审批策略集中返回给管理台、网关、Python Runtime
 * 或架构评审工具使用。</p>
 *
 * <p>为什么先做 preview：A2A task 一旦开放，就会涉及外部 Agent 委派、长期任务、取消、streaming、
 * push notification、人类审批、工具副作用、worker 并发和审计回放。如果没有先固定状态机，后续实现
 * `message:send`、`tasks/get`、`tasks/cancel` 时很容易把协议状态、内部执行状态和业务审批状态混在一起，
 * 造成接口反复重构。</p>
 *
 * @param schemaVersion DataSmart 对该状态机预览响应的 schema 版本，用于前端和自动化测试识别字段兼容性
 * @param generatedAt 生成时间；该接口不读数据库，因此时间仅表示本次控制面预览构建时刻
 * @param protocolFamily 协议族，当前固定为 A2A，后续如果 MCP tool task 共享该状态机，也可以做桥接说明
 * @param protocolVersion 当前对齐的 A2A 协议主版本；DataSmart 内部版本和 A2A 版本需要分开维护
 * @param previewOnly 是否只读预览；true 表示不会创建 A2A task，也不会改变任何任务状态
 * @param taskEndpointEnabled 真实 task endpoint 是否已启用；当前为 false，避免调用方误认为可直接委派任务
 * @param payloadPolicy 载荷策略，明确响应只包含状态和治理摘要，不包含 prompt、工具参数或资源正文
 * @param states A2A 标准状态与 DataSmart 解释
 * @param internalPhases DataSmart 内部治理阶段，以及这些阶段如何映射回 A2A 标准状态
 * @param transitions 允许的状态流转。终态不会出现在 fromState 中，防止终态重启
 * @param policy 取消、超时、幂等、权限、重试、stream/push 等策略摘要
 * @param productOptions 后续产品化可选项，用来提醒我们不要只实现最窄场景
 * @param performanceReliabilityRequirements 性能与可靠性需求草案，便于后续压测和容量规划
 * @param nextSteps 推荐下一步，不让协议适配层直接跳到有副作用执行
 */
public record AgentA2aTaskStateMachinePreviewResponse(
        String schemaVersion,
        Instant generatedAt,
        String protocolFamily,
        String protocolVersion,
        boolean previewOnly,
        boolean taskEndpointEnabled,
        String payloadPolicy,
        List<AgentA2aTaskStateView> states,
        List<AgentA2aTaskInternalPhaseView> internalPhases,
        List<AgentA2aTaskTransitionView> transitions,
        AgentA2aTaskPolicyView policy,
        List<String> productOptions,
        List<String> performanceReliabilityRequirements,
        List<String> nextSteps
) {
}

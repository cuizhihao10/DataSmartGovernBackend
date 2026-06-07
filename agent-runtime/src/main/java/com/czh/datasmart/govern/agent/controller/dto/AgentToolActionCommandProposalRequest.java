/**
 * @Author : Cui
 * @Date: 2026/06/07 14:48
 * @Description DataSmart Govern Backend - AgentToolActionCommandProposalRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * 工具动作 command proposal 请求。
 *
 * <p>该请求不是工具执行请求，也不是 outbox 写入请求。它只把前端确认页、智能网关或未来 execution graph runner
 * 已经收集到的低敏证据提交给 Java 控制面做预备校验，判断是否可以进入“正式 command builder + outbox writer”
 * 阶段。这样可以避免 MCP tools/call、A2A action 或模型 tool_call 在缺少证据时绕过平台治理直接触发副作用。</p>
 *
 * <p>安全边界：请求体不允许携带原始 arguments、SQL、prompt、样本数据、模型输出或凭证。真实载荷只能通过
 * payloadReference 表达，并由后续正式 writer 在服务端二次鉴权后读取。</p>
 *
 * @param graphId 执行图 ID。调用方可以用 graphId 精确选择某张图。
 * @param contractId durable action contract ID。调用方也可以用 contractId 精确选择契约。
 * @param tenantId 查询范围租户 ID，可为空，由 header 访问上下文进一步收口。
 * @param projectId 查询范围项目 ID，可为空，由 header 访问上下文进一步收口。
 * @param actorId 查询范围主体 ID。
 * @param requestId 查询范围请求 ID。
 * @param runId 查询范围 run ID。
 * @param sessionId 查询范围 session ID。
 * @param afterSequence 增量 replay 游标，便于前端只对新事件生成 proposal。
 * @param limit 查询执行图窗口上限。
 * @param payloadReference 受控载荷引用，不能是原始 payload。后续正式 writer 需要凭它读取工具参数快照。
 * @param approvalConfirmationId 人工审批或前端确认事实 ID。需要审批的图必须提供该引用，但本接口只做存在性校验。
 * @param clarificationFactId 用户或上游 Agent 澄清事实 ID。需要澄清的图必须提供该引用。
 * @param policyVersion 控制面策略版本，用于把 proposal 与当时的权限/预算/readiness 策略关联起来。
 * @param commandSchemaVersion 调用方期望的命令 schema 版本。正式 writer 会再次校验兼容性。
 * @param workerReceiptMode worker 回执策略，例如 REQUIRED，表示真实副作用必须回写 receipt 事实。
 * @param clientRequestId 调用方幂等请求 ID，用于前端确认页或智能网关重放时关联同一次 proposal。
 */
public record AgentToolActionCommandProposalRequest(
        String graphId,
        String contractId,
        String tenantId,
        String projectId,
        String actorId,
        String requestId,
        String runId,
        String sessionId,
        Long afterSequence,
        Integer limit,
        String payloadReference,
        String approvalConfirmationId,
        String clarificationFactId,
        String policyVersion,
        String commandSchemaVersion,
        String workerReceiptMode,
        String clientRequestId
) {
}

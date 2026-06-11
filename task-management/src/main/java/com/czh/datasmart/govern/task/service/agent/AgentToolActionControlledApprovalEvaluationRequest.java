/**
 * @Author : Cui
 * @Date: 2026/06/11 23:30
 * @Description DataSmart Govern Backend - AgentToolActionControlledApprovalEvaluationRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

/**
 * task-management 调用 permission-admin 评估受控工具动作审批事实的请求。
 *
 * <p>该 record 是 task-management 本地契约，不直接 import permission-admin DTO。
 * 微服务边界通过 HTTP/JSON 协作，避免 Java 包依赖穿透模块边界。字段全部是低敏控制面 ID，
 * 不包含工具参数、payload body、SQL、prompt、样本数据、模型输出、凭证或内部 endpoint。</p>
 *
 * @param approvalFactId task.params 中的 confirmationId。
 * @param tenantId 当前任务租户。
 * @param projectId 当前任务项目。
 * @param actorId 上游业务 actor。
 * @param sessionId Agent session ID。
 * @param runId Agent run ID。
 * @param commandId 受控工具动作 commandId。
 * @param toolCode 工具编码。
 * @param requestedPolicyVersion 当前任务携带的策略版本快照。
 * @param traceId 链路 ID。
 */
public record AgentToolActionControlledApprovalEvaluationRequest(
        String approvalFactId,
        Long tenantId,
        Long projectId,
        String actorId,
        String sessionId,
        String runId,
        String commandId,
        String toolCode,
        String requestedPolicyVersion,
        String traceId
) {
}

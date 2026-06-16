/**
 * @Author : Cui
 * @Date: 2026/06/16 00:00
 * @Description DataSmart Govern Backend - AgentToolActionApprovalFactRemoteRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

/**
 * agent-runtime 调用 permission-admin 审批事实评估接口时使用的本地请求契约。
 *
 * <p>本类与 permission-admin 的 HTTP DTO 字段保持一致，但不直接 import permission-admin 模块代码。
 * 微服务之间应通过 JSON/API 契约耦合，而不是通过 Java 编译依赖耦合；这样 permission-admin 内部包结构调整时，
 * agent-runtime 只要外部 API 兼容就不需要重编业务逻辑。</p>
 *
 * <p>该请求只允许携带低敏定位字段：租户、项目、actor、session/run/command/tool 和策略版本。
 * 不允许携带 prompt、SQL、工具 arguments、payload body、样本数据、模型输出、凭证或内部 endpoint。</p>
 *
 * @param approvalFactId 需要回查的审批事实 ID，仅发往 permission-admin，不会从事实包 API 回显给调用方。
 * @param tenantId 当前恢复动作所属租户，用于防止跨租户复用审批事实。
 * @param projectId 当前恢复动作所属项目，用于防止跨项目复用审批事实。
 * @param actorId 当前恢复动作代表的上游 actor。
 * @param sessionId 当前 Agent session ID。
 * @param runId 当前 Agent run ID。
 * @param commandId 当前受控工具动作 commandId。
 * @param toolCode 当前工具编码。
 * @param requestedPolicyVersion 调用方携带的策略版本快照，用于发现审批事实和执行策略不一致。
 * @param traceId 当前请求链路 ID，只用于 HTTP Header 透传和排障串联。
 */
public record AgentToolActionApprovalFactRemoteRequest(
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

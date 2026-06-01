/**
 * @Author : Cui
 * @Date: 2026/06/01 23:58
 * @Description DataSmart Govern Backend - AgentAsyncToolPermissionAuthorizationRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

/**
 * Agent 异步工具 worker 调用 permission-admin 前整理出的授权问题。
 *
 * <p>该对象不是 permission-admin 的内部 DTO，而是 task-management 自己的“授权请求快照”。
 * 这样 worker pre-check 只需要表达业务语义：哪个租户、哪个服务账号、代表哪个 actor、
 * 准备对哪个内部执行入口做哪个动作。真正的 HTTP JSON 字段转换交给客户端完成，避免业务服务直接依赖权限模块包。</p>
 *
 * @param tenantId 授权判定所属租户；缺失时 permission-admin 无法做租户级策略收口
 * @param serviceAccountActorId task-management Agent worker 的服务账号主体 ID
 * @param serviceAccountRole 服务账号角色，默认 SERVICE_ACCOUNT
 * @param requestPath 权限中心用于匹配策略的虚拟执行路径，不暴露外部 API，只表达 worker 执行语义
 * @param resourceType 权限资源类型，当前 worker 执行安全门统一用 AI_RUNTIME
 * @param action 权限动作，当前为 EXECUTE_CONFIRMED_ASYNC_TOOL
 * @param serviceAccountCode 服务账号可读编码，用于 permission-admin 委托证据和审计
 * @param representedActorId 被 worker 代表的上游用户或主体 ID
 * @param delegationType 委托类型，例如 SERVICE_ACCOUNT_ON_BEHALF_OF_ACTOR
 * @param delegationReason 委托原因，必须保持低敏，只写工具编码、任务 ID、commandId、auditId 等审计上下文
 * @param requestedPolicyVersion 入箱或确认阶段已携带的策略版本；权限中心当前返回最新版本，worker 负责对比
 * @param traceId 链路 ID，用于串联 task-management、permission-admin、agent-runtime 和下游工具日志
 */
public record AgentAsyncToolPermissionAuthorizationRequest(
        Long tenantId,
        Long serviceAccountActorId,
        String serviceAccountRole,
        String requestPath,
        String resourceType,
        String action,
        String serviceAccountCode,
        String representedActorId,
        String delegationType,
        String delegationReason,
        String requestedPolicyVersion,
        String traceId
) {
}

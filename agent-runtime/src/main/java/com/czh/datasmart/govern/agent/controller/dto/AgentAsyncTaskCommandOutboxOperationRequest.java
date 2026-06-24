/**
 * @Author : Cui
 * @Date: 2026/06/24 23:45
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxOperationRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * Agent 异步命令 outbox 人工补偿请求。
 *
 * <p>该请求面向平台管理员、运维人员或后续补偿台，用于对 FAILED/BLOCKED/DEAD_LETTER 命令执行人工动作。
 * 与普通用户动作不同，补偿动作会改变 durable command 的恢复策略，因此必须提供 reason，避免命令被静默重放、
 * 静默死信或静默忽略。</p>
 *
 * <p>安全边界：reason 只能是低敏说明，例如“下游 topic ACL 已修复，安排 60 秒后重试”。不要放入命令行、
 * SQL、prompt、stdout/stderr、异常堆栈、URL、payload body、凭据或内部 endpoint。服务层会做基本敏感词拦截，
 * 真实生产环境还应由 gateway/permission-admin 限定为运维角色。</p>
 *
 * @param reason 人工操作原因。
 * @param operatorId 操作人 ID；可由请求体提供，也可由 gateway 通过 Actor Header 注入。
 * @param retryDelaySeconds 仅 requeue 使用。为空或 0 表示立即重排，正数表示延迟后再允许 dispatcher 领取。
 */
public record AgentAsyncTaskCommandOutboxOperationRequest(
        String reason,
        String operatorId,
        Integer retryDelaySeconds
) {
}

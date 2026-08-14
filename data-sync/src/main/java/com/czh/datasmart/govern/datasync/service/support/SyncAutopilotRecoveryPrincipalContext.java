/**
 * @Author : Cui
 * @Date: 2026/08/12
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryPrincipalContext.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

/**
 * 由可信 Agent Runtime 内部调用携带的双主体事实。
 *
 * <p>{@code representedActorId} 回答“正在使用谁的首次授权”，Agent 与 delegation 标识回答“哪个自治主体依据哪次
 * 委派执行动作”。这些字段既是审计事实，也是重新绑定持久授权的输入，不能扩大任务范围；控制器先验证内部服务令牌，
 * 服务层仍会逐项比较授权 JSON，并执行项目管理权限复核。</p>
 *
 * @param representedActorId 被 Agent 代表的首次授权用户
 * @param actorRole 用户在当前调用中的低敏角色快照
 * @param agentId 实际执行恢复动作的 Agent
 * @param delegationId 用户授予 Agent 的持久委派标识
 * @param traceId 跨 Kafka、Agent Runtime 与 data-sync 的链路标识
 */
public record SyncAutopilotRecoveryPrincipalContext(
        String representedActorId,
        String actorRole,
        String agentId,
        String delegationId,
        String traceId) {
}

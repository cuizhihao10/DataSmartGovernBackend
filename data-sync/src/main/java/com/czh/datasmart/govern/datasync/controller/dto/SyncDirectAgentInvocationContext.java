/**
 * @Author : Cui
 * @Date: 2026/08/15
 * @Description DataSmart Govern Backend - SyncDirectAgentInvocationContext.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/**
 * Agent Runtime 直接调用同步任务工具时的低敏关联上下文。
 *
 * <p>它不参与授权判定，也不携带 prompt、工具参数、SQL 或凭据。Controller 只有在来源服务与内部令牌
 * 都通过校验后才会构造该对象；Service 在创建 execution 的同一事务内保存这些 ID，供统一生命周期图
 * 反查真实 Java 审计。</p>
 *
 * @param sessionId Agent 会话 ID
 * @param runId Agent Run ID
 * @param auditId 当前工具执行审计 ID
 * @param traceId 跨服务追踪 ID
 * @param sourceService 固定为 agent-runtime 的可信来源服务
 */
public record SyncDirectAgentInvocationContext(
        String sessionId,
        String runId,
        String auditId,
        String traceId,
        String sourceService) {
}

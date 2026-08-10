/**
 * @Author : Cui
 * @Date: 2026/07/27 00:00
 * @Description DataSmart Govern Backend - AgentPostConfirmContinuationRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Java 向 Python 续跑入口提交的结构化事实。
 *
 * <p>{@code toolResults} 必须来自 Java 工具执行事实源，而不是前端请求体；这保证模型只会看到已经由
 * 权限、审计和适配器状态机确认过的结果。</p>
 */
public record AgentPostConfirmContinuationRequest(
        /** 当前任务所属租户，由 Gateway 校验 JWT 后重建并交给 Java 控制面。 */
        String tenantId,
        /** 当前产品应用边界；Python 持久化 Specialist fact 时必须与租户、项目一起绑定。 */
        String applicationId,
        /** 当前任务所属项目，由 Java 会话归属校验再次确认。 */
        String projectId,
        /** 被 Agent 代表的业务用户，而不是 Python 或 Java 服务账号。 */
        String actorId,
        /**
         * 当前主会话的父委托 ID。
         *
         * <p>Python 不能把该值直接当作 Specialist 的执行身份，而是必须把它纳入每个 turn 的
         * 确定性子委托派生。Java 在读取后置事实时会独立重算子委托，从而证明 PRECHECK/MONITOR
         * 事实属于本次用户委托，而不是另一个会话中碰巧同名的角色。</p>
         */
        String delegationId,
        /** 本次受治理 Agent 会话定位符。 */
        String sessionId,
        /** 已经由用户确认并执行终态工具批次的源 Run。 */
        String runId,
        /** 原始业务目标；续跑只能继续该目标，不能替换成新的用户请求。 */
        String objective,
        /** 工具输出隔离键，只定位上下文，不替代租户/应用/项目授权。 */
        String workspaceKey,
        /** 跨 Java/Python 服务关联审计事件的低敏追踪标识。 */
        String traceId,
        /** Java 已持久化的终态工具审计与结果，浏览器不能直接构造。 */
        List<AgentToolExecutionResultView> toolResults) {
}

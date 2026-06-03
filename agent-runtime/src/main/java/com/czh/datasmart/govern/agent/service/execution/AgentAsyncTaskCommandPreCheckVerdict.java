/**
 * @Author : Cui
 * @Date: 2026/06/04 00:00
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandPreCheckVerdict.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import java.time.Instant;
import java.util.List;

/**
 * Agent 异步命令执行前复核结果。
 *
 * <p>该值对象服务于未来真实 DAG worker / task-management worker：command outbox 只能证明“命令已经被确认入箱”，
 * 不能证明“此刻仍然允许执行”。在真实执行副作用前，worker 需要重新合并确认事实、当前执行策略、沙箱 verdict、
 * 运行时保护 verdict 和 permission-admin 策略版本，形成一个最终准入结论。</p>
 *
 * @param commandId 异步命令 ID，用于和 outbox、task-management inbox、审计事件关联。
 * @param auditId 工具审计 ID，用于定位当前工具计划和策略项。
 * @param confirmationId selected-node 确认 ID；缺失时通常表示历史 Run 级命令或绕过确认链路。
 * @param allowed 是否允许 worker 继续执行真实副作用。
 * @param decision 稳定机器决策：ALLOW_EXECUTION、BLOCKED、DEFERRED。
 * @param policyDecision 当前 Run 级策略项决策，例如 WAITING_ASYNC_EXECUTOR。
 * @param sandboxAllowed 沙箱当前是否允许。
 * @param runtimeProtectionAllowed 运行时保护当前是否允许；容量/熔断类问题通常会导致 DEFERRED。
 * @param confirmationStatus confirmation 当前状态。
 * @param confirmationExpiresAt confirmation 过期时间；worker 可以据此解释为什么旧命令不能继续执行。
 * @param issueCodes 低基数问题码，适合进入 runtime event、指标、告警和测试断言。
 * @param reasons 中文原因说明，面向学习、排障和审计。
 * @param recommendedActions 下一步处理建议，面向 worker、运营台或管理员补偿入口。
 */
public record AgentAsyncTaskCommandPreCheckVerdict(
        String commandId,
        String auditId,
        String confirmationId,
        Boolean allowed,
        String decision,
        String policyDecision,
        Boolean sandboxAllowed,
        Boolean runtimeProtectionAllowed,
        String confirmationStatus,
        Instant confirmationExpiresAt,
        List<String> issueCodes,
        List<String> reasons,
        List<String> recommendedActions
) {

    public AgentAsyncTaskCommandPreCheckVerdict {
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
    }
}

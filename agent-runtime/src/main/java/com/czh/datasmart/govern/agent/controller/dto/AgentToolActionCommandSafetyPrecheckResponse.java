/**
 * @Author : Cui
 * @Date: 2026-06-23 01:37
 * @Description DataSmart Govern Backend - AgentToolActionCommandSafetyPrecheckResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * Agent 工具动作命令安全预检响应。
 *
 * <p>该响应是低敏控制面 verdict，用于告诉调用方“这条命令是否具备进入受控执行链路的最低安全条件”。
 * 它不是命令执行结果，也不是审批结果，更不是 worker receipt。真实执行仍必须在后续 outbox/worker 中再次
 * 校验 permission-admin、workspace lease、输出裁剪和 receipt 回写。</p>
 *
 * <p>低敏边界：本响应不会返回 commandLine、真实路径、环境变量、stdout/stderr、脚本正文、SQL、prompt、
 * 样本数据、模型输出、凭据或内部 endpoint。pathCategories 只返回分类，例如 WORKSPACE_BOUNDARY_OK、
 * WORKSPACE_ESCAPE、BLOCKED_PATH_FRAGMENT。</p>
 *
 * @param decision 最终预检决策：ALLOW_CONTROLLED_EXECUTION、REQUIRE_HUMAN_APPROVAL、BLOCKED_BY_COMMAND_SAFETY。
 * @param executable 当前是否具备进入受控 worker 的最低条件。true 也不表示已经执行。
 * @param requiresHumanApproval 是否需要人工审批或审批事实回查。
 * @param blocked 是否被安全策略硬阻断。
 * @param riskLevel 风险等级快照：LOW、MEDIUM、HIGH、CRITICAL。
 * @param payloadPolicy 响应低敏策略说明，便于前端和审计台理解字段边界。
 * @param policyVersion 命令预检策略版本。后续 outbox/worker 可把该版本写入审计事实。
 * @param evaluatedAt 服务端生成 verdict 的时间。
 * @param normalizedTimeoutSeconds 服务端裁剪后的超时时间。
 * @param normalizedOutputByteLimitBytes 服务端裁剪后的输出字节上限。
 * @param issueCodes 机器可读问题码，用于测试、前端状态和告警聚合。
 * @param reasonCodes 低敏原因码，用于解释为什么允许、审批或阻断。
 * @param pathCategories 路径分类结果，不包含真实路径值。
 * @param guardrailNotes 中文防护说明，帮助用户学习命令安全控制面的设计意图。
 * @param recommendedActions 下一步建议，例如补审批事实、改用 artifactReference 或转人工执行。
 * @param commandLineReturned 是否返回了 commandLine。固定 false，用于测试低敏边界。
 * @param pathValuesReturned 是否返回了真实路径值。固定 false，用于测试低敏边界。
 * @param sideEffectExecuted 是否已产生副作用。固定 false，因为预检不执行命令。
 */
public record AgentToolActionCommandSafetyPrecheckResponse(
        String decision,
        Boolean executable,
        Boolean requiresHumanApproval,
        Boolean blocked,
        String riskLevel,
        String payloadPolicy,
        String policyVersion,
        Instant evaluatedAt,
        Integer normalizedTimeoutSeconds,
        Integer normalizedOutputByteLimitBytes,
        List<String> issueCodes,
        List<String> reasonCodes,
        List<String> pathCategories,
        List<String> guardrailNotes,
        List<String> recommendedActions,
        Boolean commandLineReturned,
        Boolean pathValuesReturned,
        Boolean sideEffectExecuted
) {
}

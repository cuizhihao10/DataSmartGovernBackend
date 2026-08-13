/**
 * @Author : Cui
 * @Date: 2026/08/10 23:59
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryPolicyEvaluator.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;

/**
 * Applies the non-model governance boundary to an Autopilot recovery proposal.
 * RAG is deliberately absent from this policy: Python decides whether a search
 * tool is useful, while Java decides whether the resulting side effect is safe.
 */
@Service
public class AgentAutopilotRecoveryPolicyEvaluator {

    static final int MAX_REPEATED_ERROR_COUNT = 3;
    static final double MIN_AUTOMATIC_CONFIDENCE = 0.70d;

    /**
     * 用确定性治理规则评估一个恢复候选是否可以自动执行、需要审批、需要人工关注或必须拒绝。
     *
     * <p>输入是持久化授权快照、低敏候选和当前恢复循环事实；输出是带稳定原因码的决策，而不是执行命令。
     * 方法不访问模型、RAG、数据库或 data-sync，也不写入状态，因此没有直接副作用。它检查授权状态与过期时间、
     * 六个主体范围、循环/总时长预算、重复错误、证据充分性、置信度、动作白名单、风险等级及修复指纹。</p>
     *
     * <p>这是权限的最后一道本地收口：模型即使给出高置信度建议，也不能扩大授权动作或把高风险动作转成自动
     * 执行。候选必须声明幂等并同时携带修复/错误指纹，才可能得到 {@code AUTO_APPROVED}。同一输入在同一
     * 时刻得到相同决策；由于授权和总时长与当前 UTC 时间比较，时间流逝可能使后续调用降级。治理不通过时
     * 返回明确决策而非抛出业务异常，使调用方可以安全记录 case；异常只会来自违反本对象不变式的调用错误。</p>
     *
     * @param authorization 首次确认后持久化的权限和预算边界
     * @param candidate Python 提议的低敏恢复动作
     * @param loopState 当前 case 的循环、证据和重复错误事实
     * @return 可审计的确定性治理决策
     */
    public AgentAutopilotRecoveryDecision evaluate(AgentAutopilotAuthorizationSnapshot authorization,
                                                    AgentAutopilotRecoveryCandidate candidate,
                                                    AgentAutopilotRecoveryLoopState loopState) {
        if (authorization == null || candidate == null || loopState == null) {
            return decision(AgentAutopilotRecoveryDecisionType.REJECTED,
                    "AUTOPILOT_CONTEXT_MISSING", authorization, candidate);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!"ACTIVE".equals(code(authorization.state())) || authorization.expiresAt() == null
                || !authorization.expiresAt().isAfter(now)) {
            return decision(AgentAutopilotRecoveryDecisionType.REJECTED,
                    "AUTOPILOT_AUTHORIZATION_INACTIVE", authorization, candidate);
        }
        if (!sameScope(authorization, candidate)) {
            return decision(AgentAutopilotRecoveryDecisionType.REJECTED,
                    "AUTOPILOT_SCOPE_MISMATCH", authorization, candidate);
        }
        if (!loopState.scopeVerified()) {
            return decision(AgentAutopilotRecoveryDecisionType.REJECTED,
                    "RECOVERY_SCOPE_NOT_VERIFIED", authorization, candidate);
        }
        if (loopState.recoveryCycle() >= authorization.maxRecoveryCycles()) {
            return decision(AgentAutopilotRecoveryDecisionType.ATTENTION_REQUIRED,
                    "RECOVERY_CYCLE_LIMIT_REACHED", authorization, candidate);
        }
        if (loopState.recoveryStartedAt() == null
                || !loopState.recoveryStartedAt().plusMinutes(authorization.maxTotalDurationMinutes()).isAfter(now)) {
            return decision(AgentAutopilotRecoveryDecisionType.ATTENTION_REQUIRED,
                    "RECOVERY_TIME_BUDGET_EXHAUSTED", authorization, candidate);
        }
        if (loopState.repeatedErrorCount() >= MAX_REPEATED_ERROR_COUNT
                && Objects.equals(loopState.lastErrorFingerprint(), candidate.errorFingerprint())) {
            return decision(AgentAutopilotRecoveryDecisionType.ATTENTION_REQUIRED,
                    "RECOVERY_ERROR_REPEATED", authorization, candidate);
        }
        if (!loopState.evidenceSufficient() || loopState.confidence() < MIN_AUTOMATIC_CONFIDENCE) {
            return decision(AgentAutopilotRecoveryDecisionType.ATTENTION_REQUIRED,
                    "RECOVERY_EVIDENCE_MISSING", authorization, candidate);
        }

        String action = code(candidate.action());
        if (authorization.requireApprovalFor().contains(action)) {
            return decision(AgentAutopilotRecoveryDecisionType.WAITING_APPROVAL,
                    "RECOVERY_ACTION_REQUIRES_APPROVAL", authorization, candidate);
        }
        if (!authorization.allowedRecoveryActions().contains(action)) {
            return decision(AgentAutopilotRecoveryDecisionType.REJECTED,
                    "RECOVERY_ACTION_NOT_AUTHORIZED", authorization, candidate);
        }
        if (!"LOW".equals(code(candidate.riskLevel()))
                || !"LOW".equals(code(authorization.maxAutomaticRiskLevel()))) {
            return decision(AgentAutopilotRecoveryDecisionType.WAITING_APPROVAL,
                    "RECOVERY_RISK_REQUIRES_APPROVAL", authorization, candidate);
        }
        if (!candidate.idempotent() || blank(candidate.repairFingerprint()) || blank(candidate.errorFingerprint())) {
            return decision(AgentAutopilotRecoveryDecisionType.REJECTED,
                    "RECOVERY_IDEMPOTENCY_EVIDENCE_MISSING", authorization, candidate);
        }
        if ("RETRY_EXECUTION".equals(code(candidate.action()))
                && !AgentAutopilotRecoveryFactsVerifier.eligibleForAutomaticRetry(
                candidate.autopilotRecoveryFacts())) {
            // 动作名称只是模型的建议。只有先提供结构化的瞬时故障事实，
            // 才能进入无人值守执行路径。
            return decision(AgentAutopilotRecoveryDecisionType.ATTENTION_REQUIRED,
                    "RECOVERY_AUTOMATIC_RETRY_FACTS_REQUIRED", authorization, candidate);
        }
        return decision(AgentAutopilotRecoveryDecisionType.AUTO_APPROVED,
                "RECOVERY_PREAUTHORIZED", authorization, candidate);
    }

    /**
     * 比较授权与候选是否属于完全相同的用户、Agent 和资源范围。
     *
     * <p>输入是授权快照和模型候选，输出只有范围是否完全相等。该纯函数不修改任一对象、不会读取外部状态，
     * 也不自行授予权限；它要求 tenant、application、project、user、agent 和 delegation 全部匹配，
     * 防止模型将一个已批准的恢复动作移植到其他主体或资源。</p>
     *
     * <p>比较可重复执行且没有幂等副作用。空字段只有在两边均相等时才可能通过，因此上层持久化验证仍须确保
     * 授权快照本身完整；范围不匹配会由上层策略评估转换为拒绝决策。</p>
     *
     * @param authorization 已持久化的授权范围
     * @param candidate 待评估的恢复候选范围
     * @return 两个范围是否逐字段完全一致
     */
    private boolean sameScope(AgentAutopilotAuthorizationSnapshot authorization,
                              AgentAutopilotRecoveryCandidate candidate) {
        return Objects.equals(authorization.tenantId(), candidate.tenantId())
                && Objects.equals(authorization.applicationId(), candidate.applicationId())
                && Objects.equals(authorization.projectId(), candidate.projectId())
                && Objects.equals(authorization.userId(), candidate.userId())
                && Objects.equals(authorization.agentId(), candidate.agentId())
                && Objects.equals(authorization.delegationId(), candidate.delegationId());
    }

    /**
     * 将策略分支收敛为统一、低敏且可审计的决策对象。
     *
     * <p>输入是决策类型、稳定原因码以及可选的授权/候选；输出保留 policyId 和规范化动作，供 data-sync
     * 记录治理证据。该纯函数不写 case、不触发审批或重试，也不会因为候选为空而伪造动作。重复调用不会改变
     * 状态，因而可安全用于所有早退分支。</p>
     *
     * @param type 最终治理结果类型
     * @param reasonCode 解释该结果的稳定原因码
     * @param authorization 关联授权；上下文缺失时可为空
     * @param candidate 关联候选；上下文缺失时可为空
     * @return 不含敏感证据正文的决策记录
     */
    private AgentAutopilotRecoveryDecision decision(AgentAutopilotRecoveryDecisionType type,
                                                     String reasonCode,
                                                     AgentAutopilotAuthorizationSnapshot authorization,
                                                     AgentAutopilotRecoveryCandidate candidate) {
        return new AgentAutopilotRecoveryDecision(
                type,
                reasonCode,
                authorization == null ? null : authorization.policyId(),
                candidate == null ? null : code(candidate.action()));
    }

    /**
     * 规范化策略比较使用的枚举式文本。
     *
     * <p>输入为空时输出 {@code null}，其他输入会去空白、转大写并替换连字符。该函数没有副作用，不做权限或
     * 证据验证，只避免相同动作因大小写差异绕过白名单或产生不同审计动作字段。</p>
     *
     * @param value 待规范化的文本，可为空
     * @return 规范化编码，或 {@code null}
     */
    private String code(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    /**
     * 判断文本是否缺失或只包含空白。
     *
     * <p>输入可为空，输出为简单布尔结果；没有 I/O、权限或状态副作用。策略用它拒绝没有修复/错误指纹的
     * 候选，避免所谓幂等动作缺少可比较证据时仍被自动批准。</p>
     *
     * @param value 待检查的文本，可为空
     * @return 文本是否为空或空白
     */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
